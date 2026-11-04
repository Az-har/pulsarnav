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
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.pulsarnav.app.model.Maneuver
import com.pulsarnav.app.parser.ParsedNavigationStep
import com.pulsarnav.app.protocol.CallState
import com.pulsarnav.app.protocol.OtcPacketBuilder
import com.pulsarnav.app.telephony.CallManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

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

private data class QueuedWrite(
    val characteristic: BluetoothGattCharacteristic,
    val data: ByteArray,
    val writeType: Int,
    var attempts: Int = 0,
    val priority: Boolean = false   // TBT nav frames jump ahead of heartbeats in the queue
)

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
        val BUTTON_CONTROL_CHAR_UUID: UUID = UUID.fromString("0a10676e-6972-6565-6e69-676e4543544f")

        private const val TBT_STREAM_CADENCE_MS = 800L
        private const val GEN_HEARTBEAT_PERIOD_MS = 5000L
        private const val CTL_POLL_MS = 700L

        private val BIKE_NAME_PATTERNS = listOf("pulsar", "rouser", "dominar", "ns200", "ns160", "n250", "f250", "otc", "bajaj")

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
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bluetoothGatt: BluetoothGatt? = null
    private var isWriting = false
    private var userRequestedDisconnect = false

    @Volatile
    var isLinkReady: Boolean = false
        private set

    // Characteristics
    private var tbtCharacteristic: BluetoothGattCharacteristic? = null
    private var generalCharacteristic: BluetoothGattCharacteristic? = null
    private var missedCallCharacteristic: BluetoothGattCharacteristic? = null
    private var alertsCharacteristic: BluetoothGattCharacteristic? = null
    private var buttonCharacteristic: BluetoothGattCharacteristic? = null

    private val writeQueue = ConcurrentLinkedQueue<QueuedWrite>()
    private var ctlReadPending = false

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

    // Control button edge tracking (Pulsar handlebar buttons)
    private var lastControlBytes: ByteArray? = null
    private var bootstrappedControls = false
    private var callAcceptCount = 0
    private var callRejectCount = 0

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
                            roundaboutExit = 0,
                            streetText = step.streetName
                        )
                        _lastSentPacket.value = frame
                        _lastSentPacketHex.value = OtcPacketBuilder.toHex(frame)
                        if (isLinkReady) {
                            tbtCharacteristic?.let { enqueueWrite(it, frame, priority = true) }
                        }
                    }
                }
                mainHandler.postDelayed(this, TBT_STREAM_CADENCE_MS)
            }
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isLinkReady && bluetoothGatt != null) {
                dispatchGeneralPacket()
                mainHandler.postDelayed(this, GEN_HEARTBEAT_PERIOD_MS)
            }
        }
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
                mainHandler.postDelayed(this, CTL_POLL_MS)
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
            _connectionState.value = BleConnectionState.Error("Scan failed: error code $errorCode")
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown Bike"
        val lowerName = name.lowercase()

        val isBajajBike = BIKE_NAME_PATTERNS.any { lowerName.contains(it) } ||
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

            val savedAddress = prefs.getString(PREF_LAST_BIKE_ADDR, null)
            if (savedAddress != null && savedAddress.equals(device.address, ignoreCase = true) &&
                _connectionState.value is BleConnectionState.Scanning) {
                Log.i(TAG, "Recognized saved motorcycle: $name ($savedAddress) -> Auto-connecting")
                connect(device)
            }
        }
    }

    fun startScan() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            _connectionState.value = BleConnectionState.Error("Bluetooth is turned off")
            return
        }

        _discoveredBikes.value = emptyList()
        _connectionState.value = BleConnectionState.Scanning

        val scanner = adapter.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(OTC_SERVICE_UUID)).build()
        )

        try {
            scanner.startScan(filters, settings, scanCallback)
            mainHandler.postDelayed({ stopScan() }, 15000)
        } catch (_: Exception) {
            try {
                scanner.startScan(scanCallback)
                mainHandler.postDelayed({ stopScan() }, 15000)
            } catch (ex: Exception) {
                _connectionState.value = BleConnectionState.Error("Failed to start scan")
            }
        }
    }

    fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            if (_connectionState.value is BleConnectionState.Scanning) {
                _connectionState.value = BleConnectionState.Disconnected
            }
        } catch (_: Exception) {}
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        userRequestedDisconnect = false
        val deviceName = device.name ?: "Bajaj Pulsar NS200"

        prefs.edit()
            .putString(PREF_LAST_BIKE_ADDR, device.address)
            .putString(PREF_LAST_BIKE_NAME, deviceName)
            .apply()

        _connectionState.value = BleConnectionState.Connecting(deviceName)

        mainHandler.post {
            closeGatt()
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    fun disconnect() {
        userRequestedDisconnect = true
        stopNavigation()
        stopSimulation()
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.removeCallbacks(ctlPollRunnable)
        mainHandler.removeCallbacks(writeTimeoutWatchdog)
        closeGatt()
        _connectionState.value = BleConnectionState.Disconnected
    }

    private fun closeGatt() {
        isLinkReady = false
        isWriting = false
        ctlReadPending = false
        writeQueue.clear()
        bootstrappedControls = false
        lastControlBytes = null

        tbtCharacteristic = null
        generalCharacteristic = null
        missedCallCharacteristic = null
        alertsCharacteristic = null
        buttonCharacteristic = null

        bluetoothGatt?.let { g ->
            try { g.disconnect() } catch (_: Exception) {}
            try { g.close() } catch (_: Exception) {}
        }
        bluetoothGatt = null
    }

    // =========================================================================
    // GATT Callbacks
    // =========================================================================

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val device = gatt.device
            val name = device.name ?: "Bajaj Pulsar NS200"

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Connected to $name ($device.address), requesting priority & discovering services...")
                _connectionState.value = BleConnectionState.Connected(name, device.address)
                com.pulsarnav.app.data.AppSettings.getInstance(context).lastConnectedBikeMac = device.address

                try {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                } catch (_: Exception) {}

                mainHandler.postDelayed({ gatt.discoverServices() }, 350)
                com.pulsarnav.app.tracking.RideTracker.getInstance(context).onBikeConnected()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected from $name (status=$status)")
                val wasConnected = isLinkReady || _connectionState.value is BleConnectionState.Connected
                closeGatt()
                _connectionState.value = BleConnectionState.Disconnected

                val trip = com.pulsarnav.app.tracking.RideTracker.getInstance(context).onBikeDisconnected()
                if (trip != null) {
                    com.pulsarnav.app.maintenance.MaintenanceManager.getInstance(context).addDistance(trip.distanceKm)
                }
                com.pulsarnav.app.location.ParkingManager.getInstance(context).onBikeDisconnected()

                // Auto-reconnect if not manual disconnect
                val savedAddress = prefs.getString(PREF_LAST_BIKE_ADDR, null)
                if (!userRequestedDisconnect && savedAddress != null && wasConnected) {
                    mainHandler.postDelayed({
                        if (!userRequestedDisconnect && _connectionState.value is BleConnectionState.Disconnected) {
                            try {
                                bluetoothAdapter?.getRemoteDevice(savedAddress)?.let { connect(it) }
                            } catch (_: Exception) {}
                        }
                    }, 4000)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onServicesDiscovered error status=$status")
                return
            }

            val service = gatt.getService(OTC_SERVICE_UUID)
            if (service != null) {
                tbtCharacteristic = service.getCharacteristic(TBT_NAV_CHAR_UUID)
                generalCharacteristic = service.getCharacteristic(GENERAL_CHAR_UUID)
                missedCallCharacteristic = service.getCharacteristic(MISSED_CALL_CHAR_UUID)
                alertsCharacteristic = service.getCharacteristic(ALERTS_CHAR_UUID)
                buttonCharacteristic = service.getCharacteristic(BUTTON_CONTROL_CHAR_UUID)

                Log.i(TAG, "Discovered Bajaj GATT Services. Negotiating MTU 256...")

                // Critical: Request MTU 256 for 48B TBT frames and 89B General status frames
                try {
                    val requested = gatt.requestMtu(256)
                    Log.i(TAG, "requestMtu(256) returned: $requested")
                } catch (e: Exception) {
                    Log.w(TAG, "requestMtu exception: ${e.message}")
                }

                // Fallback timer in case onMtuChanged callback is not emitted by cluster
                mainHandler.postDelayed({
                    if (!isLinkReady && bluetoothGatt != null) {
                        Log.w(TAG, "MTU callback timed out — marking link ready via fallback")
                        onLinkReady("mtu-fallback")
                    }
                }, 2500)
            } else {
                Log.e(TAG, "Bajaj Service UUID $OTC_SERVICE_UUID not found!")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "onMtuChanged: mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onLinkReady("mtu=$mtu")
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            ctlReadPending = false
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BUTTON_CONTROL_CHAR_UUID) {
                val data = characteristic.value ?: return
                handleControlBytes(data)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            mainHandler.removeCallbacks(writeTimeoutWatchdog)
            isWriting = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Write error status=$status on ${characteristic.uuid}")
            }
            processNextWrite()
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

        // Send initial general status packet immediately
        dispatchGeneralPacket()

        // Start 5-second general status heartbeat
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.postDelayed(heartbeatRunnable, GEN_HEARTBEAT_PERIOD_MS)

        // Start 700ms control characteristic reading
        mainHandler.removeCallbacks(ctlPollRunnable)
        mainHandler.postDelayed(ctlPollRunnable, CTL_POLL_MS)
    }

    // =========================================================================
    // Control Handlebar Buttons Decoding
    // =========================================================================

    private fun handleControlBytes(b: ByteArray) {
        if (b.size < 18) return
        val prev = lastControlBytes
        if (prev != null && prev.contentEquals(b)) return
        lastControlBytes = b.clone()

        val accept = b[1].toInt() and 0xFF
        val reject = b[2].toInt() and 0xFF

        if (!bootstrappedControls) {
            bootstrappedControls = true
            callAcceptCount = accept
            callRejectCount = reject
            return
        }

        if (accept != callAcceptCount) {
            callAcceptCount = accept
            Log.i(TAG, "Handlebar button: CALL ACCEPT pressed ($accept)")
            CallManager.getInstance(context).acceptIncomingCall()
        }
        if (reject != callRejectCount) {
            callRejectCount = reject
            Log.i(TAG, "Handlebar button: CALL REJECT pressed ($reject)")
            CallManager.getInstance(context).rejectIncomingCall()
        }
    }

    // =========================================================================
    // Turn-by-Turn Navigation & Dry-Run Simulation Streaming
    // =========================================================================

    /**
     * Updates ongoing navigation with real Google Maps navigation data.
     * Starts continuous 800 ms streaming to the bike cluster.
     */
    fun updateNavigationStep(step: ParsedNavigationStep) {
        activeNavStep = step
        isNavigationActive = true
        _currentActiveManeuver.value = step.maneuver

        mainHandler.removeCallbacks(tbtStreamRunnable)
        mainHandler.post(tbtStreamRunnable)
    }

    /**
     * Stops turn-by-turn navigation and dispatches the verified stop frame (type = 0)
     * to clear the cluster display.
     */
    fun stopNavigation() {
        isNavigationActive = false
        activeNavStep = null
        _currentActiveManeuver.value = null
        mainHandler.removeCallbacks(tbtStreamRunnable)

        val stopFrame = OtcPacketBuilder.buildTbtStopFrame()
        _lastSentPacket.value = stopFrame
        _lastSentPacketHex.value = OtcPacketBuilder.toHex(stopFrame)
        tbtCharacteristic?.let { char ->
            if (isLinkReady) enqueueWrite(char, stopFrame)
        }
    }

    /**
     * Starts continuous Dry Run Turn Simulation at verified ~800 ms cadence.
     * Keeps the arrow, distance, and road name glowing steadily on the motorcycle's LCD.
     */
    fun startSimulation(maneuver: Maneuver, distanceMeters: Int, streetText: String) {
        stopNavigation()
        stopSimulation()

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
            etaIsAm = (cal.get(Calendar.AM_PM) == Calendar.AM)
        )
        activeNavStep = simStep

        Log.i(TAG, "▶Started Cluster Simulation for ${maneuver.description} ($distanceMeters m)")
        mainHandler.removeCallbacks(tbtStreamRunnable)
        mainHandler.post(tbtStreamRunnable)
    }

    /**
     * Stops the dry-run simulation and sends the stop frame to return cluster to default LCD view.
     */
    fun stopSimulation() {
        if (_isSimulationRunning.value) {
            _isSimulationRunning.value = false
            _currentActiveManeuver.value = null
            activeNavStep = null
            mainHandler.removeCallbacks(tbtStreamRunnable)

            Log.i(TAG, "⏹Stopped Cluster Simulation -> Sending Stop Frame")
            val stopFrame = OtcPacketBuilder.buildTbtStopFrame()
            _lastSentPacket.value = stopFrame
            _lastSentPacketHex.value = OtcPacketBuilder.toHex(stopFrame)
            tbtCharacteristic?.let { char ->
                if (isLinkReady) enqueueWrite(char, stopFrame)
            }
        }
    }

    // =========================================================================
    // General Status Heartbeat, Calls & Alerts
    // =========================================================================

    fun updateTelemetry(
        batteryPercent: Int,
        isCharging: Boolean = false,
        signalBars: Int = currentSignal,
        volume: Int = currentVolume
    ) {
        currentBattery = batteryPercent
        currentVolume = volume
        currentSignal = signalBars
        dispatchGeneralPacket()
    }

    fun updateCallState(
        callState: CallState,
        callerName: String?,
        callerNumber: String? = null,
        missedCallCount: Int = currentMissedCount
    ) {
        currentCallState = callState
        currentCallerName = callerName
        currentMissedCount = missedCallCount
        dispatchGeneralPacket()
    }

    fun sendMissedCall(callerName: String, callerNumber: String, missedCount: Int = currentMissedCount) {
        currentMissedCount = missedCount
        val pkt = OtcPacketBuilder.buildMissedCallPacket(callerName, callerNumber)
        _lastSentPacket.value = pkt
        _lastSentPacketHex.value = OtcPacketBuilder.toHex(pkt)
        missedCallCharacteristic?.let { target ->
            if (isLinkReady) enqueueWrite(target, pkt)
        }
        dispatchGeneralPacket()
    }

    fun acknowledgeCallAction(accept: Boolean = false, reject: Boolean = false, rejectSms: Boolean = false) {
        if (accept) callAcceptCount = (callAcceptCount + 1) and 0xFF
        if (reject) callRejectCount = (callRejectCount + 1) and 0xFF
        dispatchGeneralPacket()
    }

    private fun dispatchGeneralPacket() {
        val pkt = OtcPacketBuilder.buildGeneralPacketV2(
            batteryPercent = currentBattery,
            volumeLevel = currentVolume,
            signalBars = currentSignal,
            callState = currentCallState,
            callerName = currentCallerName,
            callAcceptAck = callAcceptCount,
            callRejectAck = callRejectCount,
            missedCallCount = currentMissedCount
        )
        _lastSentPacket.value = pkt
        _lastSentPacketHex.value = OtcPacketBuilder.toHex(pkt)
        generalCharacteristic?.let { target ->
            if (isLinkReady) {
                enqueueWrite(target, pkt)
                // If there is an active/incoming call, also send the 55-byte legacy frame
                // to support older cluster firmware that does not accept 89-byte V2 frames
                if (currentCallState != CallState.NO_CALL) {
                    val legacyPkt = OtcPacketBuilder.buildGeneralPacketLegacy(
                        batteryPercent = currentBattery,
                        volumeLevel = currentVolume,
                        callState = currentCallState,
                        callerName = currentCallerName,
                        callRejectAck = callAcceptCount,
                        callRejectSmsAck = callRejectCount,
                        missedCallCount = currentMissedCount
                    )
                    enqueueWrite(target, legacyPkt)
                }
            }
        }
    }

    fun sendWhatsAppAlert(senderName: String) {
        val pkt = OtcPacketBuilder.buildWhatsAppAlertPacket(senderName)
        _lastSentPacket.value = pkt
        _lastSentPacketHex.value = OtcPacketBuilder.toHex(pkt)
        alertsCharacteristic?.let { target ->
            if (isLinkReady) {
                // Pause turn-by-turn stream for 6 seconds so the cluster doesn't immediately overwrite the alert
                alertDisplayHoldUntilMs = System.currentTimeMillis() + 6000L
                enqueueWrite(target, pkt)
            }
        }
    }

    // =========================================================================
    // Serialized GATT Write Queue
    // =========================================================================

    private fun enqueueWrite(char: BluetoothGattCharacteristic, packet: ByteArray, priority: Boolean = false) {
        if (!isLinkReady && char.uuid == TBT_NAV_CHAR_UUID) {
            Log.w(TAG, "Write skipped: link not ready")
            return
        }

        // OTC clusters specifically require WRITE_TYPE_NO_RESPONSE for real-time telemetry/alerts
        val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
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
                if (iter.next().characteristic.uuid == TBT_NAV_CHAR_UUID) iter.remove()
            }
        }

        // Cap queue at 3 items for non-priority; drop oldest to prevent stale latency
        if (!priority && writeQueue.size > 3) {
            writeQueue.poll()
        }
        writeQueue.add(QueuedWrite(char, packet, writeType, priority = priority))
        processNextWrite()
    }

    @Synchronized
    private fun processNextWrite() {
        if (isWriting) return
        val gatt = bluetoothGatt ?: return
        val item = writeQueue.poll() ?: return

        isWriting = true
        _lastSentPacket.value = item.data
        _lastSentPacketHex.value = OtcPacketBuilder.toHex(item.data)

        mainHandler.removeCallbacks(writeTimeoutWatchdog)
        val timeout = if (item.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) 100L else 1200L
        mainHandler.postDelayed(writeTimeoutWatchdog, timeout)

        try {
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val res = gatt.writeCharacteristic(item.characteristic, item.data, item.writeType)
                res == BluetoothStatusCodes.SUCCESS
            } else {
                item.characteristic.writeType = item.writeType
                @Suppress("DEPRECATION")
                item.characteristic.value = item.data
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(item.characteristic)
            }

            if (!ok) {
                isWriting = false
                if (item.attempts < 2) {
                    item.attempts++
                    mainHandler.postDelayed({
                        writeQueue.add(item)
                        processNextWrite()
                    }, 100L)
                } else {
                    processNextWrite()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during GATT write: ${e.message}")
            isWriting = false
            processNextWrite()
        }
    }
}
