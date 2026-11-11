package com.pulsarnav.app.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.pulsarnav.app.media.MediaControlManager
import com.pulsarnav.app.model.Maneuver
import com.pulsarnav.app.parser.ParsedNavigationStep
import com.pulsarnav.app.protocol.CallState
import com.pulsarnav.app.protocol.OtcPacketBuilder
import com.pulsarnav.app.telephony.CallManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.Calendar
import java.util.UUID

sealed class BleConnectionState {
    object Disconnected : BleConnectionState()
    object Scanning : BleConnectionState()
    data class Connecting(val deviceName: String) : BleConnectionState()
    data class Connected(val deviceName: String, val address: String) : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}

data class DiscoveredBike(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int
)

private sealed class QueuedGattOp(
    var attempts: Int = 0,
    val priority: Boolean = false
) {
    class WriteCharacteristic(
        val characteristic: BluetoothGattCharacteristic,
        val data: ByteArray,
        val writeType: Int,
        priority: Boolean = false
    ) : QueuedGattOp(priority = priority)

    class WriteDescriptor(
        val descriptor: BluetoothGattDescriptor,
        val value: ByteArray,
        priority: Boolean = false
    ) : QueuedGattOp(priority = priority)
}

@SuppressLint("MissingPermission")
class BajajBleManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BajajBleManager"
        private const val PREFS_NAME = "pulsarnav_prefs"
        private const val PREF_LAST_BIKE_ADDR = "last_connected_bike_address"
        private const val PREF_LAST_BIKE_NAME = "last_connected_bike_name"

        // Official OTC Engineering / Bajaj Bluetooth Service & Characteristic UUIDs
        val OTC_SERVICE_UUID: UUID = UUID.fromString("0010676e-6972-6565-6e69-676e4543544f")
        val TBT_NAV_CHAR_UUID: UUID = UUID.fromString("0110676e-6972-6565-6e69-676e4543544f")
        val GENERAL_CHAR_UUID: UUID = UUID.fromString("0210676e-6972-6565-6e69-676e4543544f")
        val MISSED_CALL_CHAR_UUID: UUID = UUID.fromString("0310676e-6972-6565-6e69-676e4543544f")
        val ALERTS_CHAR_UUID: UUID = UUID.fromString("0410676e-6972-6565-6e69-676e4543544f")
        val MEDIA_INFO_CHAR_UUID: UUID = UUID.fromString("0610676e-6972-6565-6e69-676e4543544f")
        val BUTTON_CONTROL_CHAR_UUID: UUID = UUID.fromString("0a10676e-6972-6565-6e69-676e4543544f")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TBT_STREAM_CADENCE_MS = 500L
        private const val GEN_HEARTBEAT_PERIOD_MS = 5000L
        private const val CTL_POLL_MS = 700L

        // Timeout-based auto-connect & Android 7+ scan throttle protection constants
        private const val AUTO_CONNECT_SCAN_DURATION_MS = 10000L  // 10s active scanning
        private const val AUTO_CONNECT_RETRY_INTERVAL_MS = 15000L // 15s pause between scans
        private const val DISCONNECT_RECONNECT_DELAY_MS = 4000L   // 4s backoff before scanning on bike disconnect
        private const val SCAN_THROTTLE_WINDOW_MS = 30000L
        private const val MAX_SCANS_PER_WINDOW = 4

        private val BIKE_NAME_PATTERNS = listOf("pulsar", "rouser", "dominar", "ns400", "ns200", "ns160", "n250", "f250", "otc", "bajaj")

        @Volatile
        private var INSTANCE: BajajBleManager? = null

        fun getInstance(context: Context): BajajBleManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BajajBleManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val bleThread = HandlerThread("BajajBleGatt").apply { start() }
    private val bleHandler = Handler(bleThread.looper)

    private var bluetoothGatt: BluetoothGatt? = null
    private var isWriting = false
    @Volatile
    private var userRequestedDisconnect = false

    private var isAutoConnectLoopRunning = false
    private var isScanningInternal = false
    private val scanStartTimestamps = ArrayDeque<Long>()

    private fun resolveSavedBikeAddress(): String? {
        val settingsMac = com.pulsarnav.app.data.AppSettings.getInstance(context).lastConnectedBikeMac
        if (settingsMac.isNotBlank()) return settingsMac
        return prefs.getString(PREF_LAST_BIKE_ADDR, null)?.takeIf { it.isNotBlank() }
    }

    var isAutoConnectEnabled: Boolean
        get() = com.pulsarnav.app.data.AppSettings.getInstance(context).isAutoConnectEnabled
        set(value) {
            com.pulsarnav.app.data.AppSettings.getInstance(context).isAutoConnectEnabled = value
            if (!value) {
                stopAutoConnect()
            } else if (bluetoothAdapter?.isEnabled == true && _connectionState.value is BleConnectionState.Disconnected) {
                startAutoConnect(force = true)
            }
        }

    private val scanTimeoutRunnable = Runnable {
        stopScanInternal()
        if (_connectionState.value is BleConnectionState.Scanning) {
            _connectionState.value = BleConnectionState.Disconnected
        }
        if (isAutoConnectLoopRunning && !userRequestedDisconnect && bluetoothAdapter?.isEnabled == true) {
            scheduleNextAutoConnect(AUTO_CONNECT_RETRY_INTERVAL_MS)
        }
    }

    private val nextAutoConnectRunnable = Runnable {
        if (isAutoConnectLoopRunning && !userRequestedDisconnect && bluetoothAdapter?.isEnabled == true) {
            if (_connectionState.value is BleConnectionState.Disconnected) {
                executeScanAttempt()
            }
        }
    }

    @Volatile
    var isLinkReady: Boolean = false
        private set

    // Characteristics
    private var tbtCharacteristic: BluetoothGattCharacteristic? = null
    private var generalCharacteristic: BluetoothGattCharacteristic? = null
    private var missedCallCharacteristic: BluetoothGattCharacteristic? = null
    private var alertsCharacteristic: BluetoothGattCharacteristic? = null
    private var mediaInfoCharacteristic: BluetoothGattCharacteristic? = null
    private var buttonCharacteristic: BluetoothGattCharacteristic? = null

    private var isManualScan = false
    private var connectedDeviceName = ""

    private val writeQueue = ArrayDeque<QueuedGattOp>()
    private var ctlReadPending = false

    private val discoverServicesRunnable = Runnable {
        bluetoothGatt?.discoverServices()
    }

    private val mtuFallbackRunnable = Runnable {
        if (!isLinkReady && bluetoothGatt != null) {
            Log.w(TAG, "MTU callback timed out — marking link ready via fallback")
            onLinkReady("mtu-fallback")
        }
    }

    // State flows
    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState

    private val _discoveredBikes = MutableStateFlow<List<DiscoveredBike>>(emptyList())
    val discoveredBikes: StateFlow<List<DiscoveredBike>> = _discoveredBikes

    private val _lastSentPacketHex = MutableStateFlow<String?>(null)
    val lastSentPacketHex: StateFlow<String?> = _lastSentPacketHex

    private val _lastSentPacket = MutableStateFlow<ByteArray?>(null)
    val lastSentPacket: StateFlow<ByteArray?> = _lastSentPacket

    private val _isSimulationRunning = MutableStateFlow(false)
    val isSimulationRunning: StateFlow<Boolean> = _isSimulationRunning

    private val _currentActiveManeuver = MutableStateFlow<Maneuver?>(null)
    val currentActiveManeuver: StateFlow<Maneuver?> = _currentActiveManeuver

    // Telemetry cache
    private var currentBattery = 85
    private var currentVolume = 8
    private var currentSignal = 4
    private var currentCallState = CallState.NO_CALL
    private var currentCallerName: String? = null
    private var currentMissedCount = 0

    // Control button edge tracking (Pulsar LCD & NS400Z TFT switchgear)
    private var lastControlBytes: ByteArray? = null
    private var bootstrappedControls = false
    private var callAcceptCount = 0
    private var callRejectCount = 0
    private var resumeSongCount = 0
    private var pauseSongCount = 0
    private var skipToNextCount = 0
    private var skipToPrevCount = 0
    private var stopSongCount = 0
    private var launchMediaPlayerCount = 0
    private var resumeSongAckCount = 0
    private var pauseSongAckCount = 0
    private var lastClusterVolumeNibble = -1
    private var lastMusicSnapshot: MediaControlManager.MediaMetadataSnapshot? = null

    private val delayedMediaSyncRunnable = Runnable {
        if (isLinkReady && isTftCluster()) {
            dispatchGeneralPacketOnBle()
            dispatchMusicInfoOnBle(force = true)
        }
    }

    // Active Navigation / Simulation stream
    private var activeNavStep: ParsedNavigationStep? = null
    private var isNavigationActive = false
    private var alertDisplayHoldUntilMs = 0L

    // Runnables
    private val tbtStreamRunnable = object : Runnable {
        override fun run() {
            if (_isSimulationRunning.value || (isLinkReady && isNavigationActive)) {
                val isRinging = (currentCallState == CallState.INCOMING_CALL)
                val isAlertHeld = (System.currentTimeMillis() < alertDisplayHoldUntilMs)

                if (!isRinging && !isAlertHeld) {
                    val step = activeNavStep
                    if (step != null) {
                        val frame = OtcPacketBuilder.buildTbtFrame(
                            type = 1,
                            maneuverIcon = step.maneuver.opcode.toInt(),
                            isAm = step.etaIsAm,
                            stepMeters = step.distanceMeters,
                            totalMeters = step.totalDistanceMeters,
                            etaHour = step.etaHour,
                            etaMinute = step.etaMinute,
                            roundaboutExit = step.roundaboutExit,
                            streetText = step.streetName
                        )
                        _lastSentPacket.value = frame
                        _lastSentPacketHex.value = OtcPacketBuilder.toHex(frame)
                        if (isLinkReady) {
                            tbtCharacteristic?.let { enqueueWrite(it, frame, priority = true) }
                        }
                    }
                }
                bleHandler.postDelayed(this, TBT_STREAM_CADENCE_MS)
            }
        }
    }

    private val heartbeatRunnable = Runnable {
        if (isLinkReady && bluetoothGatt != null) {
            if (com.pulsarnav.app.data.AppSettings.getInstance(context).isTelemetrySyncEnabled ||
                currentCallState != CallState.NO_CALL
            ) {
                dispatchGeneralPacketOnBle()
            }
            if (isTftCluster()) {
                dispatchMusicInfoOnBle()
            }
            scheduleHeartbeat()
        }
    }

    private fun scheduleHeartbeat() {
        bleHandler.removeCallbacks(heartbeatRunnable)
        bleHandler.postDelayed(heartbeatRunnable, GEN_HEARTBEAT_PERIOD_MS)
    }

    private val ctlPollRunnable = object : Runnable {
        override fun run() {
            if (isLinkReady && buttonCharacteristic != null && bluetoothGatt != null) {
                // Read control characteristic only when write queue is idle to avoid GATT collision
                if (!isWriting && writeQueue.isEmpty() && !ctlReadPending) {
                    ctlReadPending = true
                    try {
                        val ok = bluetoothGatt?.readCharacteristic(buttonCharacteristic) ?: false
                        if (!ok) ctlReadPending = false
                    } catch (e: Exception) {
                        ctlReadPending = false
                    }
                }
                bleHandler.postDelayed(this, CTL_POLL_MS)
            }
        }
    }

    private val writeTimeoutWatchdog = Runnable {
        if (isWriting) {
            Log.w(TAG, "GATT write timeout watchdog triggered. Resetting lock.")
            isWriting = false
            processNextWrite()
        }
    }

    // =========================================================================
    // Scanning & Connection
    // =========================================================================

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { handleScanResult(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan failed: code $errorCode")
            isScanningInternal = false
            if (_connectionState.value is BleConnectionState.Scanning) {
                _connectionState.value = BleConnectionState.Disconnected
            }
            if (isAutoConnectLoopRunning && !userRequestedDisconnect && bluetoothAdapter?.isEnabled == true) {
                scheduleNextAutoConnect(AUTO_CONNECT_RETRY_INTERVAL_MS)
            }
        }
    }

    private fun runOnBle(block: () -> Unit) {
        if (android.os.Looper.myLooper() == bleThread.looper) {
            block()
        } else {
            bleHandler.post(block)
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val rawName = device.name ?: result.scanRecord?.deviceName
        val name = sanitizeBleName(rawName).ifEmpty { "Unknown Bike" }
        val lowerName = name.lowercase()

        val savedAddress = resolveSavedBikeAddress()

        val isSavedBike = !savedAddress.isNullOrBlank() && savedAddress.equals(device.address, ignoreCase = true)
        val isBajajBike = isSavedBike ||
                BIKE_NAME_PATTERNS.any { lowerName.contains(it) } ||
                result.scanRecord?.serviceUuids?.any { it.uuid == OTC_SERVICE_UUID } == true

        if (isBajajBike) {
            val bike = DiscoveredBike(device, name, device.address, result.rssi)
            val current = _discoveredBikes.value.toMutableList()
            val existingIndex = current.indexOfFirst { it.address == device.address }
            if (existingIndex >= 0) {
                current[existingIndex] = bike
            } else {
                current.add(bike)
            }
            _discoveredBikes.value = current

            if (isSavedBike && (_connectionState.value is BleConnectionState.Scanning || _connectionState.value is BleConnectionState.Disconnected)) {
                Log.i(TAG, "Recognized saved motorcycle: $name ($savedAddress) -> Auto-connecting")
                connect(device)
            }
        }
    }

    fun startAutoConnect(force: Boolean = false) = runOnBle {
        if (force) {
            userRequestedDisconnect = false
        }
        if (userRequestedDisconnect || !isAutoConnectEnabled) {
            return@runOnBle
        }
        val adapter = bluetoothAdapter ?: return@runOnBle
        if (!adapter.isEnabled) {
            return@runOnBle
        }
        if (_connectionState.value is BleConnectionState.Connected || _connectionState.value is BleConnectionState.Connecting) {
            return@runOnBle
        }

        isManualScan = false
        isAutoConnectLoopRunning = true
        bleHandler.removeCallbacks(nextAutoConnectRunnable)
        executeScanAttempt()
    }

    fun stopAutoConnect() = runOnBle {
        isAutoConnectLoopRunning = false
        bleHandler.removeCallbacks(nextAutoConnectRunnable)
        bleHandler.removeCallbacks(scanTimeoutRunnable)
        stopScanInternal()
        if (_connectionState.value is BleConnectionState.Scanning) {
            _connectionState.value = BleConnectionState.Disconnected
        }
    }

    fun onBluetoothEnabled() = runOnBle {
        userRequestedDisconnect = false
        if (isAutoConnectEnabled && _connectionState.value is BleConnectionState.Disconnected) {
            Log.i(TAG, "Bluetooth active -> Initiating auto-connect scan cycle")
            startAutoConnect(force = true)
        }
    }

    fun onBluetoothDisabled() = runOnBle {
        Log.i(TAG, "Bluetooth disabled -> Suspending auto-connect scan cycle")
        stopScanInternal()
        bleHandler.removeCallbacks(nextAutoConnectRunnable)
        bleHandler.removeCallbacks(scanTimeoutRunnable)
        closeGatt(callDisconnect = false)
        _connectionState.value = BleConnectionState.Disconnected
    }

    private fun executeScanAttempt() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            _connectionState.value = BleConnectionState.Disconnected
            return
        }
        if (_connectionState.value is BleConnectionState.Connected || _connectionState.value is BleConnectionState.Connecting) {
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            if (isAutoConnectLoopRunning && !userRequestedDisconnect) {
                scheduleNextAutoConnect(AUTO_CONNECT_RETRY_INTERVAL_MS)
            }
            return
        }

        if (isScanningInternal) {
            return
        }

        val now = System.currentTimeMillis()
        while (true) {
            val head = scanStartTimestamps.peekFirst() ?: break
            if (now - head >= SCAN_THROTTLE_WINDOW_MS) {
                scanStartTimestamps.pollFirst()
            } else {
                break
            }
        }
        if (scanStartTimestamps.size >= MAX_SCANS_PER_WINDOW) {
            val oldest = scanStartTimestamps.peekFirst() ?: now
            val waitMs = (SCAN_THROTTLE_WINDOW_MS - (now - oldest) + 300L).coerceAtLeast(1000L)
            Log.w(TAG, "BLE scan throttle guard triggered; deferring scan attempt by ${waitMs}ms")
            _connectionState.value = BleConnectionState.Disconnected
            if (isAutoConnectLoopRunning && !userRequestedDisconnect) {
                scheduleNextAutoConnect(waitMs)
            }
            return
        }

        _discoveredBikes.value = emptyList()
        _connectionState.value = BleConnectionState.Scanning

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val savedAddress = resolveSavedBikeAddress()

        // Only filter by saved MAC during background auto-connect. Never pass OTC_SERVICE_UUID as a
        // hardware ScanFilter because NS400Z TFT clusters (e.g. PULSAR1854) advertise only Flags +
        // Local Name and expose 0010676e-... solely in their GATT database after connecting.
        val filters = mutableListOf<ScanFilter>()
        if (!isManualScan && !savedAddress.isNullOrBlank()) {
            try {
                filters.add(ScanFilter.Builder().setDeviceAddress(savedAddress).build())
            } catch (_: Exception) {}
        }

        try {
            if (filters.isNotEmpty()) {
                scanner.startScan(filters, settings, scanCallback)
            } else {
                scanner.startScan(null, settings, scanCallback)
            }
            scanStartTimestamps.addLast(now)
            isScanningInternal = true
        } catch (_: Exception) {
            try {
                scanner.startScan(scanCallback)
                scanStartTimestamps.addLast(now)
                isScanningInternal = true
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to start BLE scan: ${ex.message}")
                isScanningInternal = false
                _connectionState.value = BleConnectionState.Disconnected
                if (isAutoConnectLoopRunning && !userRequestedDisconnect) {
                    scheduleNextAutoConnect(AUTO_CONNECT_RETRY_INTERVAL_MS)
                }
                return
            }
        }

        bleHandler.removeCallbacks(scanTimeoutRunnable)
        bleHandler.postDelayed(scanTimeoutRunnable, AUTO_CONNECT_SCAN_DURATION_MS)
    }

    private fun stopScanInternal() {
        bleHandler.removeCallbacks(scanTimeoutRunnable)
        if (isScanningInternal) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: Exception) {}
            isScanningInternal = false
        }
    }

    private fun scheduleNextAutoConnect(delayMs: Long) {
        bleHandler.removeCallbacks(nextAutoConnectRunnable)
        bleHandler.postDelayed(nextAutoConnectRunnable, delayMs)
    }

    fun startScan() = runOnBle {
        userRequestedDisconnect = false
        isManualScan = true
        isAutoConnectLoopRunning = true
        bleHandler.removeCallbacks(nextAutoConnectRunnable)
        stopScanInternal()
        executeScanAttempt()
    }

    fun stopScan() = runOnBle {
        isManualScan = false
        isAutoConnectLoopRunning = false
        bleHandler.removeCallbacks(nextAutoConnectRunnable)
        bleHandler.removeCallbacks(scanTimeoutRunnable)
        stopScanInternal()
        if (_connectionState.value is BleConnectionState.Scanning) {
            _connectionState.value = BleConnectionState.Disconnected
        }
    }

    fun connect(device: BluetoothDevice) = runOnBle {
        bleHandler.removeCallbacks(scanTimeoutRunnable)
        bleHandler.removeCallbacks(nextAutoConnectRunnable)
        stopScanInternal()
        isManualScan = false
        userRequestedDisconnect = false
        val deviceName = sanitizeBleName(device.name).ifEmpty { "Bajaj Cluster" }

        prefs.edit()
            .putString(PREF_LAST_BIKE_ADDR, device.address)
            .putString(PREF_LAST_BIKE_NAME, deviceName)
            .apply()
        com.pulsarnav.app.data.AppSettings.getInstance(context).lastConnectedBikeMac = device.address

        _connectionState.value = BleConnectionState.Connecting(deviceName)

        closeGatt(callDisconnect = true)
        connectedDeviceName = deviceName
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() = runOnBle {
        userRequestedDisconnect = true
        isManualScan = false
        isAutoConnectLoopRunning = false
        bleHandler.removeCallbacks(nextAutoConnectRunnable)
        bleHandler.removeCallbacks(scanTimeoutRunnable)
        stopScanInternal()
        stopNavigation()
        stopSimulation()
        bleHandler.removeCallbacks(heartbeatRunnable)
        bleHandler.removeCallbacks(ctlPollRunnable)
        bleHandler.removeCallbacks(writeTimeoutWatchdog)
        closeGatt(callDisconnect = true)
        _connectionState.value = BleConnectionState.Disconnected
    }

    private fun closeGatt(callDisconnect: Boolean = true) {
        bleHandler.removeCallbacks(discoverServicesRunnable)
        bleHandler.removeCallbacks(mtuFallbackRunnable)
        bleHandler.removeCallbacks(heartbeatRunnable)
        bleHandler.removeCallbacks(ctlPollRunnable)
        bleHandler.removeCallbacks(writeTimeoutWatchdog)
        bleHandler.removeCallbacks(delayedMediaSyncRunnable)

        isLinkReady = false
        isWriting = false
        ctlReadPending = false
        writeQueue.clear()
        bootstrappedControls = false
        lastControlBytes = null
        connectedDeviceName = ""
        resumeSongCount = 0
        pauseSongCount = 0
        skipToNextCount = 0
        skipToPrevCount = 0
        stopSongCount = 0
        launchMediaPlayerCount = 0
        resumeSongAckCount = 0
        pauseSongAckCount = 0
        lastClusterVolumeNibble = -1
        lastMusicSnapshot = null

        tbtCharacteristic = null
        generalCharacteristic = null
        missedCallCharacteristic = null
        alertsCharacteristic = null
        mediaInfoCharacteristic = null
        buttonCharacteristic = null

        bluetoothGatt?.let { g ->
            if (callDisconnect) {
                try { g.disconnect() } catch (_: Exception) {}
            }
            try { g.close() } catch (_: Exception) {}
        }
        bluetoothGatt = null
    }

    // =========================================================================
    // GATT Callbacks
    // =========================================================================

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            runOnBle {
                val device = gatt.device
                val name = sanitizeBleName(device.name).ifEmpty {
                    sanitizeBleName(prefs.getString(PREF_LAST_BIKE_NAME, null)).ifEmpty { "Bajaj Cluster" }
                }

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Connected to $name (${device.address}), requesting priority & discovering services...")
                    connectedDeviceName = name
                    bleHandler.removeCallbacks(nextAutoConnectRunnable)
                    bleHandler.removeCallbacks(scanTimeoutRunnable)
                    _connectionState.value = BleConnectionState.Connected(name, device.address)
                    com.pulsarnav.app.data.AppSettings.getInstance(context).lastConnectedBikeMac = device.address
                    prefs.edit()
                        .putString(PREF_LAST_BIKE_ADDR, device.address)
                        .putString(PREF_LAST_BIKE_NAME, name)
                        .apply()

                    try {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                    } catch (_: Exception) {}

                    if (isTftCluster(name) && device.bondState == BluetoothDevice.BOND_NONE) {
                        try {
                            val bondStarted = device.createBond()
                            Log.i(TAG, "Initiated Just-Works SMP bonding for TFT cluster ($name): $bondStarted")
                        } catch (se: SecurityException) {
                            Log.w(TAG, "createBond skipped due to missing BT permission: ${se.message}")
                        }
                    }

                    bleHandler.removeCallbacks(discoverServicesRunnable)
                    bleHandler.postDelayed(discoverServicesRunnable, 350)
                    com.pulsarnav.app.tracking.RideTracker.getInstance(context).onBikeConnected()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.w(TAG, "Disconnected from $name (status=$status)")
                    closeGatt(callDisconnect = false)
                    _connectionState.value = BleConnectionState.Disconnected

                    val trip = com.pulsarnav.app.tracking.RideTracker.getInstance(context).onBikeDisconnected()
                    if (trip != null) {
                        com.pulsarnav.app.maintenance.MaintenanceManager.getInstance(context).addDistance(trip.distanceKm)
                    }
                    com.pulsarnav.app.location.ParkingManager.getInstance(context).onBikeDisconnected()

                    // Auto-reconnect if not manual disconnect
                    if (!userRequestedDisconnect && isAutoConnectEnabled) {
                        Log.i(TAG, "Bike disconnected; initiating timeout-based auto-reconnect cycle in ${DISCONNECT_RECONNECT_DELAY_MS}ms")
                        isAutoConnectLoopRunning = true
                        scheduleNextAutoConnect(DISCONNECT_RECONNECT_DELAY_MS)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            runOnBle {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "onServicesDiscovered error status=$status")
                    return@runOnBle
                }

                val service = gatt.getService(OTC_SERVICE_UUID)
                if (service != null) {
                    tbtCharacteristic = service.getCharacteristic(TBT_NAV_CHAR_UUID)
                    generalCharacteristic = service.getCharacteristic(GENERAL_CHAR_UUID)
                    mediaInfoCharacteristic = service.getCharacteristic(MEDIA_INFO_CHAR_UUID)
                    missedCallCharacteristic = service.getCharacteristic(MISSED_CALL_CHAR_UUID)
                    alertsCharacteristic = service.getCharacteristic(ALERTS_CHAR_UUID)
                    buttonCharacteristic = service.getCharacteristic(BUTTON_CONTROL_CHAR_UUID)

                    if (isTftCluster()) {
                        when (gatt.device.bondState) {
                            BluetoothDevice.BOND_NONE -> {
                                Log.i(TAG, "TFT cluster not bonded during discovery; initiating Just-Works bonding")
                                try {
                                    gatt.device.createBond()
                                } catch (e: Exception) {
                                    Log.w(TAG, "createBond failed: ${e.message}")
                                }
                                bleHandler.removeCallbacks(delayedMediaSyncRunnable)
                                bleHandler.postDelayed(delayedMediaSyncRunnable, 1800)
                            }
                            BluetoothDevice.BOND_BONDING -> {
                                Log.i(TAG, "TFT cluster bonding in progress; scheduling delayed media sync")
                                bleHandler.removeCallbacks(delayedMediaSyncRunnable)
                                bleHandler.postDelayed(delayedMediaSyncRunnable, 1800)
                            }
                        }
                    }

                    Log.i(TAG, "Discovered Bajaj GATT Services. Negotiating MTU 256...")

                    // Critical: Request MTU 256 for 48B TBT frames and 89B General status frames
                    try {
                        val requested = gatt.requestMtu(256)
                        Log.i(TAG, "requestMtu(256) returned: $requested")
                    } catch (e: Exception) {
                        Log.w(TAG, "requestMtu exception: ${e.message}")
                    }

                    // Fallback timer in case onMtuChanged callback is not emitted by cluster
                    bleHandler.removeCallbacks(mtuFallbackRunnable)
                    bleHandler.postDelayed(mtuFallbackRunnable, 2500)
                } else {
                    Log.e(TAG, "Bajaj Service UUID $OTC_SERVICE_UUID not found!")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            runOnBle {
                bleHandler.removeCallbacks(mtuFallbackRunnable)
                Log.i(TAG, "onMtuChanged: mtu=$mtu status=$status")
                onLinkReady("mtu=$mtu status=$status")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            runOnBle {
                ctlReadPending = false
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BUTTON_CONTROL_CHAR_UUID) {
                    handleControlBytes(value)
                }
            }
        }

        @Deprecated("Deprecated in Android BluetoothGattCallback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val data = characteristic.value
            runOnBle {
                ctlReadPending = false
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BUTTON_CONTROL_CHAR_UUID && data != null) {
                    handleControlBytes(data)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            runOnBle {
                if (characteristic.uuid == BUTTON_CONTROL_CHAR_UUID) {
                    handleControlBytes(value)
                }
            }
        }

        @Deprecated("Deprecated in Android BluetoothGattCallback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val data = characteristic.value ?: return
            runOnBle {
                if (characteristic.uuid == BUTTON_CONTROL_CHAR_UUID) {
                    handleControlBytes(data)
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            runOnBle {
                bleHandler.removeCallbacks(writeTimeoutWatchdog)
                isWriting = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Write error status=$status on ${characteristic.uuid}")
                }
                processNextWrite()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            runOnBle {
                bleHandler.removeCallbacks(writeTimeoutWatchdog)
                isWriting = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Descriptor write error status=$status on ${descriptor.uuid}")
                }
                processNextWrite()
            }
        }
    }

    private fun onLinkReady(reason: String) {
        if (isLinkReady) return
        isLinkReady = true
        Log.i(TAG, "Pulsar Cluster Link READY ($reason)")

        // Keep foreground service active to protect BLE link and background streaming
        try {
            com.pulsarnav.app.service.BikeBleForegroundService.start(context)
        } catch (_: Exception) {}

        // Subscribe to button control notifications if cluster exposes CCCD
        buttonCharacteristic?.let { char ->
            val props = char.properties
            if ((props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                try {
                    bluetoothGatt?.setCharacteristicNotification(char, true)
                    val cccd = char.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        val enableVal = if ((props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        } else {
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        }
                        enqueueOp(QueuedGattOp.WriteDescriptor(cccd, enableVal, priority = true))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "CCCD setup skipped: ${e.message}")
                }
            }
        }

        if (isTftCluster()) {
            currentVolume = MediaControlManager.getInstance(context).getMediaVolumeTenths()
        }

        // Send initial general status packet immediately
        dispatchGeneralPacketOnBle()

        // Send initial track metadata frame for TFT clusters
        if (isTftCluster()) {
            dispatchMusicInfoOnBle(force = true)
        }

        // Start 5-second general status heartbeat
        scheduleHeartbeat()

        // Start 700ms control characteristic reading (as reliable fallback alongside notifications)
        bleHandler.removeCallbacks(ctlPollRunnable)
        bleHandler.postDelayed(ctlPollRunnable, CTL_POLL_MS)
    }

    // =========================================================================
    // Control Handlebar Buttons Decoding
    // =========================================================================

    private fun handleControlBytes(b: ByteArray) {
        val frame = OtcPacketBuilder.parseControlFrame(b) ?: return
        val prev = lastControlBytes
        if (prev != null && prev.contentEquals(b)) return
        lastControlBytes = b.clone()

        if (!bootstrappedControls) {
            bootstrappedControls = true
            callAcceptCount = frame.callAccept
            callRejectCount = frame.callReject
            resumeSongCount = frame.resumeSong
            pauseSongCount = frame.pauseSong
            skipToNextCount = frame.skipToNext
            skipToPrevCount = frame.skipToPrev
            stopSongCount = frame.stopSong
            launchMediaPlayerCount = frame.launchMediaPlayer
            lastClusterVolumeNibble = frame.volumeToSet
            return
        }

        val tftMode = isTftCluster()
        var needsGeneralRefresh = false
        var needsMediaRefresh = false
        val mediaManager = MediaControlManager.getInstance(context)

        if (frame.callAccept != callAcceptCount) {
            callAcceptCount = frame.callAccept
            needsGeneralRefresh = true
            if (currentCallState == CallState.INCOMING_CALL) {
                Log.i(TAG, "Handlebar button: CALL ACCEPT pressed (${frame.callAccept})")
                CallManager.getInstance(context).acceptIncomingCall()
            } else if (!tftMode) {
                Log.i(TAG, "Handlebar button: MEDIA PLAY/PAUSE pressed")
                mediaManager.handleHandlebarButton(isLongPress = false)
            }
        }
        if (frame.callReject != callRejectCount) {
            callRejectCount = frame.callReject
            needsGeneralRefresh = true
            if (currentCallState == CallState.INCOMING_CALL || currentCallState == CallState.ACTIVE_CALL) {
                Log.i(TAG, "Handlebar button: CALL REJECT pressed (${frame.callReject})")
                CallManager.getInstance(context).rejectIncomingCall()
            } else if (!tftMode) {
                Log.i(TAG, "Handlebar button: MEDIA NEXT TRACK pressed")
                mediaManager.handleHandlebarButton(isLongPress = true)
            }
        }

        if (tftMode) {
            if (frame.resumeSong != resumeSongCount) {
                resumeSongCount = frame.resumeSong
                resumeSongAckCount = (resumeSongAckCount + 1) and 0xFF
                Log.i(TAG, "TFT switchgear: RESUME SONG (${frame.resumeSong}) -> ack=$resumeSongAckCount")
                mediaManager.dispatchPlay()
                needsGeneralRefresh = true
                needsMediaRefresh = true
            }
            if (frame.pauseSong != pauseSongCount) {
                pauseSongCount = frame.pauseSong
                pauseSongAckCount = (pauseSongAckCount + 1) and 0xFF
                Log.i(TAG, "TFT switchgear: PAUSE SONG (${frame.pauseSong}) -> ack=$pauseSongAckCount")
                mediaManager.dispatchPause()
                needsGeneralRefresh = true
                needsMediaRefresh = true
            }
            if (frame.skipToNext != skipToNextCount) {
                skipToNextCount = frame.skipToNext
                Log.i(TAG, "TFT switchgear: NEXT TRACK (${frame.skipToNext})")
                mediaManager.dispatchNext()
                bleHandler.removeCallbacks(delayedMediaSyncRunnable)
                bleHandler.postDelayed(delayedMediaSyncRunnable, 350)
                needsGeneralRefresh = true
                needsMediaRefresh = true
            }
            if (frame.skipToPrev != skipToPrevCount) {
                skipToPrevCount = frame.skipToPrev
                Log.i(TAG, "TFT switchgear: PREVIOUS TRACK (${frame.skipToPrev})")
                mediaManager.dispatchPrevious()
                bleHandler.removeCallbacks(delayedMediaSyncRunnable)
                bleHandler.postDelayed(delayedMediaSyncRunnable, 350)
                needsGeneralRefresh = true
                needsMediaRefresh = true
            }
            if (frame.stopSong != stopSongCount) {
                stopSongCount = frame.stopSong
                Log.i(TAG, "TFT switchgear: STOP SONG (${frame.stopSong})")
                mediaManager.dispatchStop()
                needsGeneralRefresh = true
                needsMediaRefresh = true
            }
            if (frame.launchMediaPlayer != launchMediaPlayerCount) {
                launchMediaPlayerCount = frame.launchMediaPlayer
                Log.i(TAG, "TFT switchgear: LAUNCH MEDIA PLAYER (${frame.launchMediaPlayer})")
                mediaManager.dispatchPlay()
                needsGeneralRefresh = true
                needsMediaRefresh = true
            }
            if (frame.volumeToSet != lastClusterVolumeNibble) {
                lastClusterVolumeNibble = frame.volumeToSet
                if (frame.volumeToSet in 0..10) {
                    val appliedTenths = mediaManager.setMediaVolumeFromCluster(frame.volumeToSet)
                    if (appliedTenths != null) {
                        currentVolume = appliedTenths
                    }
                    Log.i(TAG, "TFT switchgear: VOLUME SET (${frame.volumeToSet}/10 -> currentVolume=$currentVolume)")
                    needsGeneralRefresh = true
                }
            }
        }

        if (needsGeneralRefresh) {
            dispatchGeneralPacketOnBle()
        }
        if (needsMediaRefresh) {
            dispatchMusicInfoOnBle(force = true)
        }
    }

    // =========================================================================
    // Turn-by-Turn Navigation & Dry-Run Simulation Streaming
    // =========================================================================

    /**
     * Updates ongoing navigation with real Google Maps navigation data.
     * Starts continuous 800 ms streaming to the bike cluster.
     */
    fun updateNavigationStep(step: ParsedNavigationStep) = runOnBle {
        activeNavStep = step
        isNavigationActive = true
        _currentActiveManeuver.value = step.maneuver

        bleHandler.removeCallbacks(tbtStreamRunnable)
        bleHandler.post(tbtStreamRunnable)
    }

    /**
     * Stops turn-by-turn navigation and dispatches the verified stop frame (type = 0)
     * to clear the cluster display.
     */
    fun stopNavigation() = runOnBle {
        isNavigationActive = false
        activeNavStep = null
        _currentActiveManeuver.value = null
        bleHandler.removeCallbacks(tbtStreamRunnable)

        val stopFrame = OtcPacketBuilder.buildTbtStopFrame()
        if (isLinkReady) {
            _lastSentPacket.value = stopFrame
            _lastSentPacketHex.value = OtcPacketBuilder.toHex(stopFrame)
            tbtCharacteristic?.let { char ->
                enqueueWrite(char, stopFrame, priority = true)
            }
        }
    }

    /**
     * Starts continuous Dry Run Turn Simulation at verified ~500 ms cadence.
     * Keeps the arrow, distance, and road name glowing steadily on the motorcycle's LCD.
     */
    fun startSimulation(maneuver: Maneuver, distanceMeters: Int, streetText: String, roundaboutExit: Int = 0) = runOnBle {
        isNavigationActive = false
        _isSimulationRunning.value = false
        bleHandler.removeCallbacks(tbtStreamRunnable)

        _isSimulationRunning.value = true
        _currentActiveManeuver.value = maneuver

        val cal = Calendar.getInstance()
        var h = cal.get(Calendar.HOUR)
        if (h == 0) h = 12

        val simStep = ParsedNavigationStep(
            maneuver = maneuver,
            distanceMeters = distanceMeters,
            totalDistanceMeters = distanceMeters + 1200,
            streetName = streetText,
            rawDistanceText = "${distanceMeters}m",
            etaHour = h,
            etaMinute = (cal.get(Calendar.MINUTE) + 6) % 60,
            etaIsAm = (cal.get(Calendar.AM_PM) == Calendar.AM),
            roundaboutExit = roundaboutExit
        )
        activeNavStep = simStep

        Log.i(TAG, "▶Started Cluster Simulation for ${maneuver.description} ($distanceMeters m)")
        bleHandler.removeCallbacks(tbtStreamRunnable)
        bleHandler.post(tbtStreamRunnable)
    }

    /**
     * Stops the dry-run simulation and sends the stop frame to return cluster to default LCD view.
     */
    fun stopSimulation() = runOnBle {
        if (_isSimulationRunning.value) {
            _isSimulationRunning.value = false
            _currentActiveManeuver.value = null
            activeNavStep = null
            bleHandler.removeCallbacks(tbtStreamRunnable)

            Log.i(TAG, "⏹Stopped Cluster Simulation -> Sending Stop Frame")
            val stopFrame = OtcPacketBuilder.buildTbtStopFrame()
            _lastSentPacket.value = stopFrame
            _lastSentPacketHex.value = OtcPacketBuilder.toHex(stopFrame)
            tbtCharacteristic?.let { char ->
                if (isLinkReady) enqueueWrite(char, stopFrame, priority = true)
            }
        }
    }

    // =========================================================================
    // General Status Heartbeat, Calls & Alerts
    // =========================================================================

    fun updateTelemetry(
        batteryPercent: Int,
        @Suppress("UNUSED_PARAMETER") isCharging: Boolean = false,
        signalBars: Int = currentSignal,
        volume: Int = currentVolume
    ) = runOnBle {
        currentBattery = batteryPercent
        currentVolume = volume
        currentSignal = signalBars
        if (isLinkReady) {
            dispatchGeneralPacketOnBle()
        }
    }

    fun updateCallState(
        callState: CallState,
        callerName: String?,
        @Suppress("UNUSED_PARAMETER") callerNumber: String? = null,
        missedCallCount: Int = currentMissedCount
    ) = runOnBle {
        currentCallState = callState
        currentCallerName = callerName
        currentMissedCount = missedCallCount
        dispatchGeneralPacketOnBle(allowInspectorPreviewWhenDisconnected = true)
    }

    fun sendMissedCall(callerName: String, callerNumber: String, missedCount: Int = currentMissedCount) = runOnBle {
        currentMissedCount = missedCount
        val pkt = OtcPacketBuilder.buildMissedCallPacket(callerName, callerNumber)
        _lastSentPacket.value = pkt
        _lastSentPacketHex.value = OtcPacketBuilder.toHex(pkt)
        missedCallCharacteristic?.let { target ->
            if (isLinkReady) enqueueWrite(target, pkt, priority = true)
        }
        dispatchGeneralPacketOnBle(allowInspectorPreviewWhenDisconnected = true)
    }

    fun acknowledgeCallAction(
        accept: Boolean = false,
        reject: Boolean = false,
        @Suppress("UNUSED_PARAMETER") rejectSms: Boolean = false
    ) = runOnBle {
        if (accept) callAcceptCount = (callAcceptCount + 1) and 0xFF
        if (reject) callRejectCount = (callRejectCount + 1) and 0xFF
        if (isLinkReady) {
            dispatchGeneralPacketOnBle()
        }
    }

    private val tftNameRegex = Regex("^pulsar\\d{4,}$")

    private fun sanitizeBleName(raw: String?): String {
        return raw
            ?.substringBefore('\u0000')
            ?.trim()
            .orEmpty()
    }

    private fun isTftCluster(deviceName: String = connectedDeviceName): Boolean {
        if (com.pulsarnav.app.data.AppSettings.getInstance(context).selectedModelId == "ns400z") {
            return true
        }
        val normalized = sanitizeBleName(deviceName).lowercase()
        return normalized.contains("ns400") || tftNameRegex.matches(normalized)
    }

    private fun dispatchGeneralPacketOnBle(allowInspectorPreviewWhenDisconnected: Boolean = false) {
        if (!isLinkReady && !allowInspectorPreviewWhenDisconnected) {
            return
        }
        val pkt = OtcPacketBuilder.buildGeneralPacketV2(
            batteryPercent = currentBattery,
            volumeLevel = currentVolume,
            signalBars = currentSignal,
            callState = currentCallState,
            callerName = currentCallerName,
            callAcceptAck = callAcceptCount,
            callRejectAck = callRejectCount,
            missedCallCount = currentMissedCount,
            resumeSongAck = resumeSongAckCount,
            pauseSongAck = pauseSongAckCount,
            skipToNextAck = skipToNextCount,
            skipToPrevAck = skipToPrevCount,
            stopSongAck = stopSongCount,
            launchMediaPlayerAck = launchMediaPlayerCount
        )
        if (isLinkReady || allowInspectorPreviewWhenDisconnected) {
            _lastSentPacket.value = pkt
            _lastSentPacketHex.value = OtcPacketBuilder.toHex(pkt)
        }
        generalCharacteristic?.let { target ->
            if (isLinkReady) {
                val isUrgentCall = currentCallState != CallState.NO_CALL
                enqueueWrite(target, pkt, priority = isUrgentCall)
                // If there is an active/incoming call on LCD clusters, also send the 55-byte legacy frame
                // to support older cluster firmware that does not accept 89-byte V2 frames
                if (isUrgentCall && !isTftCluster()) {
                    val legacyPkt = OtcPacketBuilder.buildGeneralPacketLegacy(
                        batteryPercent = currentBattery,
                        volumeLevel = currentVolume,
                        callState = currentCallState,
                        callerName = currentCallerName,
                        callRejectAck = callRejectCount,
                        callRejectSmsAck = 0,
                        missedCallCount = currentMissedCount
                    )
                    enqueueWrite(target, legacyPkt, priority = true)
                }
            }
        }
    }

    private fun dispatchMusicInfoOnBle(force: Boolean = false) {
        if (!isLinkReady) return
        val target = mediaInfoCharacteristic ?: return
        val snapshot = MediaControlManager.getInstance(context).getActiveMediaSnapshot()
        if (!force && snapshot == lastMusicSnapshot) {
            return
        }
        lastMusicSnapshot = snapshot
        val pkt = OtcPacketBuilder.buildMusicInfoFrame(
            title = snapshot.title,
            artist = snapshot.artist,
            album = snapshot.album,
            elapsedSeconds = snapshot.elapsedSeconds,
            durationSeconds = snapshot.durationSeconds,
            playStatus = snapshot.playStatus
        )
        enqueueWrite(target, pkt, priority = force)
    }

    fun sendWhatsAppAlert(senderName: String) = runOnBle {
        val pkt = OtcPacketBuilder.buildWhatsAppAlertPacket(senderName)
        _lastSentPacket.value = pkt
        _lastSentPacketHex.value = OtcPacketBuilder.toHex(pkt)
        alertsCharacteristic?.let { target ->
            if (isLinkReady) {
                // Pause turn-by-turn stream for 6 seconds so the cluster doesn't immediately overwrite the alert
                alertDisplayHoldUntilMs = System.currentTimeMillis() + 6000L
                enqueueWrite(target, pkt, priority = true)
            }
        }
    }

    // =========================================================================
    // Serialized GATT Write Queue (runs exclusively on bleHandler)
    // =========================================================================

    private fun enqueueWrite(char: BluetoothGattCharacteristic, packet: ByteArray, priority: Boolean = false) {
        if (!isLinkReady && char.uuid == TBT_NAV_CHAR_UUID) {
            Log.w(TAG, "Write skipped: link not ready")
            return
        }

        // TFT clusters (NS400Z / PULSAR1854) and 0610 media info require WRITE_TYPE_DEFAULT (ATT Write Request),
        // while legacy LCD clusters (NS200) use WRITE_TYPE_NO_RESPONSE for real-time telemetry/alerts.
        val useWriteDefault = (char.uuid == MEDIA_INFO_CHAR_UUID || isTftCluster()) &&
            (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val writeType = if (useWriteDefault) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        // TBT frames are time-sensitive: drop stale TBT writes from the queue before inserting
        // so the cluster always gets the freshest direction, not one queued 2 seconds ago.
        if (priority && char.uuid == TBT_NAV_CHAR_UUID) {
            val iter = writeQueue.iterator()
            while (iter.hasNext()) {
                val op = iter.next()
                if (op is QueuedGattOp.WriteCharacteristic && op.characteristic.uuid == TBT_NAV_CHAR_UUID) {
                    iter.remove()
                }
            }
        }

        enqueueOp(QueuedGattOp.WriteCharacteristic(char, packet, writeType, priority = priority))
    }

    private fun enqueueOp(op: QueuedGattOp) {
        if (op.priority) {
            // Priority frames jump ahead of normal heartbeat frames while preserving relative order
            val snapshot = ArrayList(writeQueue)
            writeQueue.clear()
            var inserted = false
            for (existing in snapshot) {
                if (!inserted && !existing.priority) {
                    writeQueue.addLast(op)
                    inserted = true
                }
                writeQueue.addLast(existing)
            }
            if (!inserted) {
                writeQueue.addLast(op)
            }
        } else {
            // Cap non-priority queue depth; drop oldest non-priority item to avoid stale latency
            while (writeQueue.size >= 4) {
                val nonPrio = writeQueue.firstOrNull { !it.priority } ?: break
                writeQueue.remove(nonPrio)
            }
            writeQueue.addLast(op)
        }
        processNextWrite()
    }

    private fun processNextWrite() {
        if (isWriting) return
        val gatt = bluetoothGatt ?: return
        val item = writeQueue.pollFirst() ?: return

        isWriting = true
        if (item is QueuedGattOp.WriteCharacteristic) {
            _lastSentPacket.value = item.data
            _lastSentPacketHex.value = OtcPacketBuilder.toHex(item.data)
        }

        bleHandler.removeCallbacks(writeTimeoutWatchdog)
        val timeout = when (item) {
            is QueuedGattOp.WriteCharacteristic ->
                if (item.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) 100L else 1200L
            is QueuedGattOp.WriteDescriptor -> 1200L
        }
        bleHandler.postDelayed(writeTimeoutWatchdog, timeout)

        try {
            val ok = when (item) {
                is QueuedGattOp.WriteCharacteristic -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(item.characteristic, item.data, item.writeType) ==
                            BluetoothStatusCodes.SUCCESS
                    } else {
                        item.characteristic.writeType = item.writeType
                        @Suppress("DEPRECATION")
                        item.characteristic.value = item.data
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(item.characteristic)
                    }
                }
                is QueuedGattOp.WriteDescriptor -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(item.descriptor, item.value) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        item.descriptor.value = item.value
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(item.descriptor)
                    }
                }
            }

            if (!ok) {
                bleHandler.removeCallbacks(writeTimeoutWatchdog)
                isWriting = false
                if (item.attempts < 2) {
                    item.attempts++
                    bleHandler.postDelayed({
                        enqueueOp(item)
                    }, 100L)
                } else {
                    processNextWrite()
                }
            }
        } catch (e: Exception) {
            bleHandler.removeCallbacks(writeTimeoutWatchdog)
            Log.w(TAG, "Exception during GATT write: ${e.message}")
            isWriting = false
            processNextWrite()
        }
    }
}
