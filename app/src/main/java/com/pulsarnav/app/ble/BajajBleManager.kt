package com.pulsarnav.app.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.pulsarnav.app.debug.DebugLogRepository
import com.pulsarnav.app.media.MediaControlManager
import com.pulsarnav.app.model.Maneuver
import com.pulsarnav.app.navigation.NavigationIntentLauncher
import com.pulsarnav.app.parser.ParsedNavigationStep
import com.pulsarnav.app.protocol.AlertType
import com.pulsarnav.app.protocol.CallState
import com.pulsarnav.app.protocol.OtcPacketBuilder
import com.pulsarnav.app.telephony.CallManager
import com.pulsarnav.app.telephony.ClusterPhonebookManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime
import java.util.ArrayDeque
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

data class SimulatedAlertInfo(
    val sender: String,
    val alertType: AlertType,
    val timestampMs: Long
)

private sealed class QueuedGattOp(
    val sessionId: Long,
    var attempts: Int = 0,
    val priority: Boolean = false
) {
    class WriteCharacteristic(
        val characteristic: BluetoothGattCharacteristic,
        val data: ByteArray,
        val writeType: Int,
        sessionId: Long,
        priority: Boolean = false
    ) : QueuedGattOp(sessionId = sessionId, priority = priority)

    class WriteDescriptor(
        val descriptor: BluetoothGattDescriptor,
        val value: ByteArray,
        sessionId: Long,
        priority: Boolean = false
    ) : QueuedGattOp(sessionId = sessionId, priority = priority)
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
        val PLAYLIST_INFO_CHAR_UUID: UUID = UUID.fromString("0510676e-6972-6565-6e69-676e4543544f")
        val MEDIA_INFO_CHAR_UUID: UUID = UUID.fromString("0610676e-6972-6565-6e69-676e4543544f")
        val FAV_CONTACTS_CHAR_UUID: UUID = UUID.fromString("0910676e-6972-6565-6e69-676e4543544f")
        val BUTTON_CONTROL_CHAR_UUID: UUID = UUID.fromString("0a10676e-6972-6565-6e69-676e4543544f")
        val RECENT_CALLS_CHAR_UUID: UUID = UUID.fromString("0b10676e-6972-6565-6e69-676e4543544f")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TBT_STREAM_CADENCE_MS = 500L
        private const val TBT_STATIC_KEEPALIVE_MS = 1500L
        private const val GEN_HEARTBEAT_PERIOD_MS = 5000L
        private const val CTL_POLL_MS = 700L
        private const val CTL_READ_TIMEOUT_MS = 1500L
        private const val WRITE_NO_RESPONSE_TIMEOUT_MS = 350L
        private const val DISCONNECT_FLUSH_TIMEOUT_MS = 250L
        private const val GATT_RECONNECT_SETTLE_MS = 200L
        private const val MTU_RETRY_DELAY_MS = 350L
        private const val MTU_CALLBACK_TIMEOUT_MS = 2500L
        private const val MAX_MTU_RETRIES = 2
        private const val MAX_CONSECUTIVE_AUTH_ERRORS = 3
        private const val BOND_WAIT_TIMEOUT_MS = 12000L

        // Timeout-based auto-connect & Android 7+ scan throttle protection constants
        private const val AUTO_CONNECT_SCAN_DURATION_MS = 10000L  // 10s active scanning
        private const val AUTO_CONNECT_RETRY_INTERVAL_MS = 15000L // 15s pause between scans
        private const val DISCONNECT_RECONNECT_DELAY_MS = 4000L   // 4s backoff before scanning on bike disconnect
        private const val ACL_DISCONNECT_GRACE_MS = 15000L        // Grace window before finalizing trip/parking on ACL drop
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
    private var playlistInfoCharacteristic: BluetoothGattCharacteristic? = null
    private var mediaInfoCharacteristic: BluetoothGattCharacteristic? = null
    private var favContactsCharacteristic: BluetoothGattCharacteristic? = null
    private var buttonCharacteristic: BluetoothGattCharacteristic? = null
    private var recentCallsCharacteristic: BluetoothGattCharacteristic? = null

    private var isManualScan = false
    private var connectedDeviceName = ""
    private var connectedDeviceAddress = ""
    private var activeGattSessionId = 0L
    private var hasStartedRideSession = false
    private var isDisconnecting = false

    private val writeQueue = ArrayDeque<QueuedGattOp>()
    private val pendingRetryRunnables = ArrayList<Runnable>()
    private var pendingConnectRunnable: Runnable? = null
    private var ctlReadPending = false
    private var negotiatedMtu = BleProtocolSafety.DEFAULT_ATT_MTU
    private var mtuRetryCount = 0
    private var consecutiveAuthWriteErrors = 0
    private var isWaitingForBondCompletion = false
    private var bondFailedForSession = false
    private var isBondReceiverRegistered = false
    @Volatile private var activeProtocolStrategy: BleProtocolSafety.BleProtocolStrategy = BleProtocolSafety.BleProtocolStrategy.OLD_LCD_LEGACY

    private val initialTftSyncRunnable = Runnable {
        if (isLinkReady && !isDisconnecting) {
            syncPhonebookAndCalls()
            dispatchPlaylistInfoOnBle(page = pagePlaylistAck, newPlaylistAckVal = newPlaylistAck)
        }
    }

    private val pendingDisconnectRunnable = Runnable {
        if (isDisconnecting) {
            Log.w(TAG, "Disconnect flush timeout reached — closing GATT")
            finishDisconnectCleanup()
        }
    }

    private val pendingAclDisconnectRunnable = Runnable {
        if (!isLinkReady && _connectionState.value !is BleConnectionState.Connected && _connectionState.value !is BleConnectionState.Connecting) {
            Log.i(TAG, "ACL disconnect grace period elapsed without reconnect — saving parking spot and closing trip")
            if (hasStartedRideSession) {
                endRideSessionIfActive()
            } else {
                com.pulsarnav.app.location.ParkingManager.getInstance(context).onBikeDisconnected()
            }
        }
    }

    private val discoverServicesRunnable = Runnable {
        bluetoothGatt?.discoverServices()
    }

    private val mtuRetryRunnable = Runnable {
        val gatt = bluetoothGatt ?: return@Runnable
        if (!isLinkReady) {
            startMtuNegotiation(gatt, resetRetries = false)
        }
    }

    private val mtuFallbackRunnable = Runnable {
        val gatt = bluetoothGatt ?: return@Runnable
        if (!isLinkReady) {
            val tft = isTftCluster()
            val bondState = try { gatt.device.bondState } catch (_: SecurityException) { BluetoothDevice.BOND_NONE }
            if (BleProtocolSafety.canActivateLink(tft, negotiatedMtu, bondState, bondFailedForSession)) {
                if (tft) {
                    onLinkReady("mtu=$negotiatedMtu")
                } else {
                    Log.w(TAG, "MTU callback timed out on legacy cluster (mtu=$negotiatedMtu) — marking link ready via fallback")
                    if (negotiatedMtu <= BleProtocolSafety.DEFAULT_ATT_MTU) {
                        Log.w(TAG, "Legacy cluster settled at default ATT MTU ($negotiatedMtu); >20B Default writes will be guarded")
                    }
                    onLinkReady("mtu-fallback")
                }
            } else if (tft && isWaitingForBondCompletion && bondState == BluetoothDevice.BOND_BONDING) {
                Log.i(TAG, "MTU callback waiting on active TFT bonding")
            } else if (tft && bondFailedForSession) {
                abortHandshakeAndReconnect("TFT bonding failed before MTU completion", allowReconnect = false)
            } else if (mtuRetryCount < MAX_MTU_RETRIES) {
                mtuRetryCount++
                Log.w(TAG, "MTU callback timed out (mtu=$negotiatedMtu); retrying requestMtu ($mtuRetryCount/$MAX_MTU_RETRIES)")
                startMtuNegotiation(gatt, resetRetries = false)
            } else {
                abortHandshakeAndReconnect(
                    "TFT MTU negotiation timed out after $MAX_MTU_RETRIES retries (mtu=$negotiatedMtu)"
                )
            }
        }
    }

    private val bondTimeoutRunnable = Runnable {
        val gatt = bluetoothGatt ?: return@Runnable
        if (!isWaitingForBondCompletion) return@Runnable
        isWaitingForBondCompletion = false
        val bondState = try { gatt.device.bondState } catch (_: SecurityException) { BluetoothDevice.BOND_NONE }
        if (bondState == BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "Bond wait timeout resolved: device already BOND_BONDED")
            proceedAfterBondSettled(gatt)
        } else {
            fallbackToLegacyLcdProtocol("TFT bonding timed out (bondState=$bondState)")
        }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            runOnBle {
                val gatt = bluetoothGatt ?: return@runOnBle
                if (device != null && device.address != gatt.device.address) return@runOnBle
                when (bondState) {
                    BluetoothDevice.BOND_BONDED -> {
                        Log.i(TAG, "BLE bonding completed (BOND_BONDED) for ${device?.address ?: gatt.device.address}")
                        bleHandler.removeCallbacks(bondTimeoutRunnable)
                        bondFailedForSession = false
                        val wasWaiting = isWaitingForBondCompletion
                        isWaitingForBondCompletion = false
                        if (wasWaiting) {
                            proceedAfterBondSettled(gatt)
                        }
                    }
                    BluetoothDevice.BOND_NONE -> {
                        val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                        if (prevState == BluetoothDevice.BOND_BONDING || isWaitingForBondCompletion) {
                            Log.w(TAG, "Bonding ended without pairing (BOND_NONE from $prevState)")
                            bleHandler.removeCallbacks(bondTimeoutRunnable)
                            val wasWaiting = isWaitingForBondCompletion
                            isWaitingForBondCompletion = false
                            if (activeProtocolStrategy == BleProtocolSafety.BleProtocolStrategy.NEW_TFT_MODERN) {
                                fallbackToLegacyLcdProtocol("TFT bonding failed (BOND_NONE from $prevState)")
                            } else if (wasWaiting) {
                                proceedAfterBondSettled(gatt)
                            }
                        }
                    }
                }
            }
        }
    }

    // State flows
    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState

    private val _discoveredBikes = MutableStateFlow<List<DiscoveredBike>>(emptyList())
    val discoveredBikes: StateFlow<List<DiscoveredBike>> = _discoveredBikes

    private val _lastSentPacket = MutableStateFlow<ByteArray?>(null)
    val lastSentPacket: StateFlow<ByteArray?> = _lastSentPacket

    private val _isSimulationRunning = MutableStateFlow(false)
    val isSimulationRunning: StateFlow<Boolean> = _isSimulationRunning

    private val _currentActiveManeuver = MutableStateFlow<Maneuver?>(null)
    val currentActiveManeuver: StateFlow<Maneuver?> = _currentActiveManeuver

    private val _simulatedNavStep = MutableStateFlow<ParsedNavigationStep?>(null)
    val simulatedNavStep: StateFlow<ParsedNavigationStep?> = _simulatedNavStep

    private val _simulatedAlert = MutableStateFlow<SimulatedAlertInfo?>(null)
    val simulatedAlert: StateFlow<SimulatedAlertInfo?> = _simulatedAlert

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
    private var controlFrameCount = 0
    private var dialEdgesArmed = false
    private var lastDialSource = 0
    private var lastDialTxn = 0
    private var callAcceptCount = 0
    private var callRejectCount = 0
    private var callRejectWithSmsCount = 0
    private var callAcceptAckCount = 0
    private var callRejectAckCount = 0
    private var callRejectSmsAckCount = 0
    private var takeMeHomeCount = 0
    private var takeMeHomeAckCount = 0
    private var missedCallGetCount = 0
    private var missedCallGetAckCount = 0
    private var alertGetCount = 0
    private var alertGetAckCount = 0
    private var resumeSongCount = 0
    private var pauseSongCount = 0
    private var skipToNextCount = 0
    private var skipToPrevCount = 0
    private var stopSongCount = 0
    private var launchMediaPlayerCount = 0
    private var resumeSongAckCount = 0
    private var pauseSongAckCount = 0
    private var skipToNextAckCount = 0
    private var skipToPrevAckCount = 0
    private var stopSongAckCount = 0
    private var launchMediaPlayerAckCount = 0
    private var lastPagePlaylist = 0
    private var pagePlaylistAck = 1
    private var lastNewPlaylistReq = 0
    private var newPlaylistAck = 0
    private var lastSelectPlaylistSongReq = 0
    private var selectPlaylistSongAckCount = 0
    private var lastClusterVolumeNibble = -1
    private var lastAlertSenderName: String? = null
    private var lastAlertType: AlertType = AlertType.WHATSAPP
    private var lastMusicSnapshot: MediaControlManager.MediaMetadataSnapshot? = null
    private val phonebookExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val _lastClusterAction = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val lastClusterAction: kotlinx.coroutines.flow.StateFlow<String?> = _lastClusterAction

    private val delayedMediaSyncRunnable = Runnable {
        if (isLinkReady && isTftCluster()) {
            dispatchMusicInfoOnBle(force = true)
            dispatchPlaylistInfoOnBle(page = pagePlaylistAck, newPlaylistAckVal = newPlaylistAck)
        }
    }

    // Active Navigation / Simulation stream
    private var activeNavStep: ParsedNavigationStep? = null
    private var isNavigationActive = false
    private var alertDisplayHoldUntilMs = 0L
    private var lastSentTbtFrame: ByteArray? = null
    private var lastTbtSendTimeMs = 0L

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
                            streetText = step.streetName,
                            takeMeHomeAck = takeMeHomeAckCount
                        )
                        val now = SystemClock.elapsedRealtime()
                        val isSameFrame = lastSentTbtFrame?.contentEquals(frame) == true
                        val keepaliveExpired = (now - lastTbtSendTimeMs) >= TBT_STATIC_KEEPALIVE_MS
                        if (!isSameFrame || keepaliveExpired) {
                            lastSentTbtFrame = frame
                            lastTbtSendTimeMs = now
                            _lastSentPacket.value = frame
                            if (isLinkReady) {
                                tbtCharacteristic?.let { enqueueWrite(it, frame, priority = true) }
                            }
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

    private val ctlReadTimeoutRunnable = Runnable {
        if (ctlReadPending) {
            Log.w(TAG, "Control characteristic read timeout watchdog triggered. Clearing ctlReadPending.")
            ctlReadPending = false
            processNextWrite()
        }
    }

    private val ctlPollRunnable = object : Runnable {
        override fun run() {
            if (isLinkReady && buttonCharacteristic != null && bluetoothGatt != null) {
                // Poll 0a10 only when write queue is idle and negotiated ATT MTU avoids Read Blob (0x0c)
                val mtuAllowsPoll = BleProtocolSafety.canPollControlCharacteristic(isTftCluster(), negotiatedMtu)
                if (mtuAllowsPoll && !isWriting && writeQueue.isEmpty() && !ctlReadPending) {
                    ctlReadPending = true
                    bleHandler.removeCallbacks(ctlReadTimeoutRunnable)
                    try {
                        val ok = bluetoothGatt?.readCharacteristic(buttonCharacteristic) ?: false
                        if (ok) {
                            bleHandler.postDelayed(ctlReadTimeoutRunnable, CTL_READ_TIMEOUT_MS)
                        } else {
                            ctlReadPending = false
                        }
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
            advanceWriteQueueOrFinishDisconnect()
        }
    }

    // =========================================================================
    // Scanning & Connection
    // =========================================================================

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val scanResult = result ?: return
            runOnBle { handleScanResult(scanResult) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            val batch = results?.toList() ?: return
            runOnBle { batch.forEach { handleScanResult(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnBle {
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
        bleHandler.removeCallbacks(pendingDisconnectRunnable)
        stopScanInternal()
        isManualScan = false
        userRequestedDisconnect = false
        isDisconnecting = false
        val deviceName = sanitizeBleName(device.name).ifEmpty { "Bajaj Cluster" }

        prefs.edit()
            .putString(PREF_LAST_BIKE_ADDR, device.address)
            .putString(PREF_LAST_BIKE_NAME, deviceName)
            .apply()
        com.pulsarnav.app.data.AppSettings.getInstance(context).lastConnectedBikeMac = device.address

        _connectionState.value = BleConnectionState.Connecting(deviceName)

        val hadActiveGatt = bluetoothGatt != null
        endRideSessionIfActive()
        closeGatt(callDisconnect = true)
        connectedDeviceName = deviceName
        connectedDeviceAddress = device.address
        if (!hadActiveGatt) {
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            val connectSession = activeGattSessionId
            val runnable = Runnable {
                pendingConnectRunnable = null
                if (activeGattSessionId == connectSession && bluetoothGatt == null && !userRequestedDisconnect) {
                    bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                }
            }
            pendingConnectRunnable = runnable
            bleHandler.postDelayed(runnable, GATT_RECONNECT_SETTLE_MS)
        }
    }

    fun disconnect() = runOnBle {
        userRequestedDisconnect = true
        isManualScan = false
        isAutoConnectLoopRunning = false
        bleHandler.removeCallbacks(nextAutoConnectRunnable)
        bleHandler.removeCallbacks(scanTimeoutRunnable)
        bleHandler.removeCallbacks(pendingDisconnectRunnable)
        stopScanInternal()

        val hadReadyLink = isLinkReady && bluetoothGatt != null
        if (hadReadyLink) {
            // Queue TBT stop frames while isLinkReady is still true before tearing down GATT
            stopNavigation()
            stopSimulation()
        } else {
            isNavigationActive = false
            activeNavStep = null
            _currentActiveManeuver.value = null
            _isSimulationRunning.value = false
            _simulatedNavStep.value = null
            bleHandler.removeCallbacks(tbtStreamRunnable)
        }

        bleHandler.removeCallbacks(heartbeatRunnable)
        bleHandler.removeCallbacks(ctlPollRunnable)
        bleHandler.removeCallbacks(delayedMediaSyncRunnable)
        bleHandler.removeCallbacks(initialTftSyncRunnable)
        pendingConnectRunnable?.let { bleHandler.removeCallbacks(it) }
        pendingConnectRunnable = null

        if (hadReadyLink && (isWriting || writeQueue.isNotEmpty())) {
            isDisconnecting = true
            bleHandler.postDelayed(pendingDisconnectRunnable, DISCONNECT_FLUSH_TIMEOUT_MS)
        } else {
            finishDisconnectCleanup()
        }
    }

    private fun endRideSessionIfActive() {
        bleHandler.removeCallbacks(pendingAclDisconnectRunnable)
        try {
            com.pulsarnav.app.service.BikeBleForegroundService.stopTftMirror(context)
        } catch (_: Exception) {}
        if (!hasStartedRideSession) return
        hasStartedRideSession = false
        val trip = com.pulsarnav.app.tracking.RideTracker.getInstance(context).onBikeDisconnected()
        if (trip != null && trip.distanceKm > 0.0) {
            com.pulsarnav.app.maintenance.MaintenanceManager.getInstance(context).addDistance(trip.distanceKm)
        }
        com.pulsarnav.app.location.ParkingManager.getInstance(context).onBikeDisconnected()
    }

    fun onSystemAclConnected() = runOnBle {
        bleHandler.removeCallbacks(pendingAclDisconnectRunnable)
    }

    fun onSystemAclDisconnected() = runOnBle {
        if (_connectionState.value is BleConnectionState.Connected && isLinkReady) {
            Log.d(TAG, "Ignoring ACL_DISCONNECTED while GATT link is still ready")
            return@runOnBle
        }
        bleHandler.removeCallbacks(pendingAclDisconnectRunnable)
        bleHandler.postDelayed(pendingAclDisconnectRunnable, ACL_DISCONNECT_GRACE_MS)
    }

    private fun finishDisconnectCleanup() {
        bleHandler.removeCallbacks(pendingDisconnectRunnable)
        isDisconnecting = false
        endRideSessionIfActive()
        closeGatt(callDisconnect = true)
        _connectionState.value = BleConnectionState.Disconnected
    }

    private fun abortHandshakeAndReconnect(reason: String, allowReconnect: Boolean = true) {
        Log.e(TAG, "Aborting cluster handshake ($reason)")
        DebugLogRepository.error("BLE_HANDSHAKE", "Handshake aborted: $reason", "allowReconnect=$allowReconnect")
        bleHandler.removeCallbacks(pendingDisconnectRunnable)
        isDisconnecting = false
        endRideSessionIfActive()
        closeGatt(callDisconnect = true)
        _connectionState.value = BleConnectionState.Disconnected
        if (allowReconnect && !userRequestedDisconnect && isAutoConnectEnabled) {
            isAutoConnectLoopRunning = true
            scheduleNextAutoConnect(DISCONNECT_RECONNECT_DELAY_MS)
        }
    }

    private fun ensureBondReceiverRegistered() {
        if (isBondReceiverRegistered) return
        try {
            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            ContextCompat.registerReceiver(context, bondStateReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            isBondReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register ACTION_BOND_STATE_CHANGED receiver: ${e.message}")
        }
    }

    private fun unregisterBondReceiver() {
        if (!isBondReceiverRegistered) return
        try {
            context.unregisterReceiver(bondStateReceiver)
        } catch (_: Exception) {}
        isBondReceiverRegistered = false
    }

    private fun proceedAfterBondSettled(gatt: BluetoothGatt) {
        if (gatt.services.isNullOrEmpty()) {
            bleHandler.removeCallbacks(discoverServicesRunnable)
            bleHandler.postDelayed(discoverServicesRunnable, 250)
        } else if (!isLinkReady) {
            startMtuNegotiation(gatt, resetRetries = true)
        } else if (isTftCluster()) {
            bleHandler.removeCallbacks(delayedMediaSyncRunnable)
            bleHandler.postDelayed(delayedMediaSyncRunnable, 350)
        }
    }

    private fun switchToModernTftProtocol(gatt: BluetoothGatt, reason: String) {
        if (activeProtocolStrategy == BleProtocolSafety.BleProtocolStrategy.NEW_TFT_MODERN && isWaitingForBondCompletion) return
        Log.i(TAG, "Upgrading BLE protocol: OLD_LCD_LEGACY -> NEW_TFT_MODERN ($reason)")
        DebugLogRepository.bleEvent("BLE protocol switch -> NEW_TFT_MODERN ($reason)")
        activeProtocolStrategy = BleProtocolSafety.BleProtocolStrategy.NEW_TFT_MODERN

        ensureBondReceiverRegistered()
        val device = gatt.device
        val bondState = try { device.bondState } catch (_: SecurityException) { BluetoothDevice.BOND_NONE }
        when (bondState) {
            BluetoothDevice.BOND_NONE -> {
                try {
                    val bondStarted = device.createBond()
                    Log.i(TAG, "Initiated Just-Works bonding for TFT cluster: $bondStarted")
                    if (bondStarted || device.bondState == BluetoothDevice.BOND_BONDING) {
                        isWaitingForBondCompletion = true
                        bleHandler.removeCallbacks(bondTimeoutRunnable)
                        bleHandler.postDelayed(bondTimeoutRunnable, BOND_WAIT_TIMEOUT_MS)
                        return
                    }
                } catch (se: SecurityException) {
                    Log.w(TAG, "createBond failed: ${se.message}")
                }
            }
            BluetoothDevice.BOND_BONDING -> {
                isWaitingForBondCompletion = true
                bleHandler.removeCallbacks(bondTimeoutRunnable)
                bleHandler.postDelayed(bondTimeoutRunnable, BOND_WAIT_TIMEOUT_MS)
                return
            }
            BluetoothDevice.BOND_BONDED -> {
                // Already bonded, proceed directly with MTU negotiation and TFT sync
            }
        }
        startMtuNegotiation(gatt, resetRetries = true)
    }

    private fun fallbackToLegacyLcdProtocol(reason: String) {
        Log.w(TAG, "Falling back BLE protocol: NEW_TFT_MODERN -> OLD_LCD_LEGACY ($reason)")
        DebugLogRepository.bleEvent("BLE protocol fallback -> OLD_LCD_LEGACY ($reason)")
        activeProtocolStrategy = BleProtocolSafety.BleProtocolStrategy.OLD_LCD_LEGACY
        isWaitingForBondCompletion = false
        bondFailedForSession = false
        bleHandler.removeCallbacks(bondTimeoutRunnable)
        onLinkReady("fallback-to-lcd-legacy: $reason")
    }

    private fun startMtuNegotiation(gatt: BluetoothGatt, resetRetries: Boolean) {
        if (resetRetries) {
            mtuRetryCount = 0
        }
        bleHandler.removeCallbacks(mtuRetryRunnable)
        bleHandler.removeCallbacks(mtuFallbackRunnable)
        try {
            val requested = gatt.requestMtu(256)
            Log.i(TAG, "requestMtu(256) returned: $requested (attempt=${mtuRetryCount + 1})")
        } catch (e: Exception) {
            Log.w(TAG, "requestMtu exception: ${e.message}")
        }
        bleHandler.postDelayed(mtuFallbackRunnable, MTU_CALLBACK_TIMEOUT_MS)
    }

    private fun closeGatt(callDisconnect: Boolean = true) {
        activeGattSessionId++
        bleHandler.removeCallbacks(discoverServicesRunnable)
        bleHandler.removeCallbacks(mtuRetryRunnable)
        bleHandler.removeCallbacks(mtuFallbackRunnable)
        bleHandler.removeCallbacks(bondTimeoutRunnable)
        bleHandler.removeCallbacks(ctlReadTimeoutRunnable)
        bleHandler.removeCallbacks(pendingDisconnectRunnable)
        bleHandler.removeCallbacks(heartbeatRunnable)
        bleHandler.removeCallbacks(ctlPollRunnable)
        bleHandler.removeCallbacks(writeTimeoutWatchdog)
        bleHandler.removeCallbacks(delayedMediaSyncRunnable)
        bleHandler.removeCallbacks(initialTftSyncRunnable)
        pendingConnectRunnable?.let { bleHandler.removeCallbacks(it) }
        pendingConnectRunnable = null
        pendingRetryRunnables.forEach { bleHandler.removeCallbacks(it) }
        pendingRetryRunnables.clear()

        isLinkReady = false
        isDisconnecting = false
        hasStartedRideSession = false
        isWriting = false
        ctlReadPending = false
        negotiatedMtu = BleProtocolSafety.DEFAULT_ATT_MTU
        mtuRetryCount = 0
        consecutiveAuthWriteErrors = 0
        isWaitingForBondCompletion = false
        bondFailedForSession = false
        unregisterBondReceiver()
        writeQueue.clear()
        bootstrappedControls = false
        controlFrameCount = 0
        dialEdgesArmed = false
        lastDialSource = 0
        lastDialTxn = 0
        lastControlBytes = null
        connectedDeviceName = ""
        connectedDeviceAddress = ""
        callAcceptCount = 0
        callRejectCount = 0
        callRejectWithSmsCount = 0
        callAcceptAckCount = 0
        callRejectAckCount = 0
        callRejectSmsAckCount = 0
        takeMeHomeCount = 0
        takeMeHomeAckCount = 0
        missedCallGetCount = 0
        missedCallGetAckCount = 0
        alertGetCount = 0
        alertGetAckCount = 0
        resumeSongCount = 0
        pauseSongCount = 0
        skipToNextCount = 0
        skipToPrevCount = 0
        stopSongCount = 0
        launchMediaPlayerCount = 0
        resumeSongAckCount = 0
        pauseSongAckCount = 0
        skipToNextAckCount = 0
        skipToPrevAckCount = 0
        stopSongAckCount = 0
        launchMediaPlayerAckCount = 0
        lastPagePlaylist = 0
        pagePlaylistAck = 1
        lastNewPlaylistReq = 0
        newPlaylistAck = 0
        lastSelectPlaylistSongReq = 0
        selectPlaylistSongAckCount = 0
        lastClusterVolumeNibble = -1
        lastAlertSenderName = null
        lastAlertType = AlertType.WHATSAPP
        lastMusicSnapshot = null

        tbtCharacteristic = null
        generalCharacteristic = null
        missedCallCharacteristic = null
        alertsCharacteristic = null
        playlistInfoCharacteristic = null
        mediaInfoCharacteristic = null
        favContactsCharacteristic = null
        buttonCharacteristic = null
        recentCallsCharacteristic = null

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
                if (gatt !== bluetoothGatt) {
                    Log.d(TAG, "Ignoring stale onConnectionStateChange callback for old GATT instance")
                    return@runOnBle
                }
                val device = gatt.device
                val name = sanitizeBleName(device.name).ifEmpty {
                    sanitizeBleName(prefs.getString(PREF_LAST_BIKE_NAME, null)).ifEmpty { "Bajaj Cluster" }
                }

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Connected to $name (${device.address}), starting BLE handshake...")
                    DebugLogRepository.bleEvent("GATT connected to $name (${device.address})")
                    connectedDeviceName = name
                    connectedDeviceAddress = device.address
                    bleHandler.removeCallbacks(nextAutoConnectRunnable)
                    bleHandler.removeCallbacks(scanTimeoutRunnable)
                    // Remain in Connecting state until discovery, bonding, and ATT MTU settle
                    _connectionState.value = BleConnectionState.Connecting(name)
                    com.pulsarnav.app.data.AppSettings.getInstance(context).lastConnectedBikeMac = device.address
                    prefs.edit()
                        .putString(PREF_LAST_BIKE_ADDR, device.address)
                        .putString(PREF_LAST_BIKE_NAME, name)
                        .apply()

                    try {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                    } catch (_: Exception) {}

                    negotiatedMtu = BleProtocolSafety.DEFAULT_ATT_MTU
                    mtuRetryCount = 0
                    isWaitingForBondCompletion = false
                    bondFailedForSession = false
                    ensureBondReceiverRegistered()

                    // Universal dual-protocol connection strategy:
                    // Condition 1: Check Bike's Name & Model Profile.
                    // Condition 2: First test the OLD code (legacy LCD connection) without pre-bonding blocks.
                    // If an error is detected during write (e.g. Auth/Encryption) or 0610 Media Info
                    // characteristic is present, dynamically upgrade to NEW_TFT_MODERN.
                    activeProtocolStrategy = BleProtocolSafety.BleProtocolStrategy.OLD_LCD_LEGACY
                    Log.i(TAG, "Initiating universal handshake for $name (testing OLD_LCD_LEGACY protocol first)")

                    bleHandler.removeCallbacks(discoverServicesRunnable)
                    bleHandler.removeCallbacks(bondTimeoutRunnable)
                    bleHandler.postDelayed(discoverServicesRunnable, 350)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Disconnected / connect failure on $name (status=$status, newState=$newState)")
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        DebugLogRepository.error("BLE_GATT", "Disconnect/error on $name (status=$status, newState=$newState)")
                    } else {
                        DebugLogRepository.bleEvent("GATT disconnected from $name")
                    }
                    endRideSessionIfActive()
                    closeGatt(callDisconnect = false)
                    _connectionState.value = BleConnectionState.Disconnected

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
                if (gatt !== bluetoothGatt) return@runOnBle
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    abortHandshakeAndReconnect("onServicesDiscovered error status=$status")
                    return@runOnBle
                }

                val service = gatt.getService(OTC_SERVICE_UUID)
                if (service != null) {
                    tbtCharacteristic = service.getCharacteristic(TBT_NAV_CHAR_UUID)
                    generalCharacteristic = service.getCharacteristic(GENERAL_CHAR_UUID)
                    missedCallCharacteristic = service.getCharacteristic(MISSED_CALL_CHAR_UUID)
                    alertsCharacteristic = service.getCharacteristic(ALERTS_CHAR_UUID)
                    playlistInfoCharacteristic = service.getCharacteristic(PLAYLIST_INFO_CHAR_UUID)
                    mediaInfoCharacteristic = service.getCharacteristic(MEDIA_INFO_CHAR_UUID)
                    favContactsCharacteristic = service.getCharacteristic(FAV_CONTACTS_CHAR_UUID)
                    buttonCharacteristic = service.getCharacteristic(BUTTON_CONTROL_CHAR_UUID)
                    recentCallsCharacteristic = service.getCharacteristic(RECENT_CALLS_CHAR_UUID)

                    // Condition 1 & 2 Check:
                    // Does the cluster expose 0610 (Media Info), 0510 (Playlist), or 0910/0b10 (Phonebook)?
                    val appSettings = com.pulsarnav.app.data.AppSettings.getInstance(context)
                    val clusterCategory = BleProtocolSafety.classifyCluster(
                        deviceName = connectedDeviceName,
                        selectedModelId = appSettings.selectedModelId,
                        hasMediaInfoCharacteristic = mediaInfoCharacteristic != null,
                        hasPlaylistCharacteristic = playlistInfoCharacteristic != null,
                        hasFavRecentCharacteristic = favContactsCharacteristic != null || recentCallsCharacteristic != null,
                        userOverride = appSettings.clusterConsoleType
                    )

                    if (clusterCategory == BleProtocolSafety.ClusterCategory.TFT_COLOR) {
                        Log.i(TAG, "Hardware TFT characteristic(s) or setting detected for $connectedDeviceName -> upgrading to NEW_TFT_MODERN")
                        switchToModernTftProtocol(gatt, "TFT hardware/profile detected")
                        return@runOnBle
                    }

                    // Otherwise test the OLD code (legacy LCD handshake):
                    Log.i(TAG, "Discovered Bajaj GATT Services on $connectedDeviceName. Testing OLD_LCD_LEGACY protocol...")
                    activeProtocolStrategy = BleProtocolSafety.BleProtocolStrategy.OLD_LCD_LEGACY
                    startMtuNegotiation(gatt, resetRetries = true)
                } else {
                    abortHandshakeAndReconnect("Bajaj Service UUID $OTC_SERVICE_UUID not found")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            runOnBle {
                if (gatt !== bluetoothGatt) return@runOnBle
                bleHandler.removeCallbacks(mtuFallbackRunnable)
                bleHandler.removeCallbacks(mtuRetryRunnable)
                Log.i(TAG, "onMtuChanged: mtu=$mtu status=$status")
                DebugLogRepository.bleEvent("MTU changed -> mtu=$mtu, status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS && mtu > BleProtocolSafety.DEFAULT_ATT_MTU) {
                    negotiatedMtu = mtu
                }

                val tft = isTftCluster()
                val bondState = try { gatt.device.bondState } catch (_: SecurityException) { BluetoothDevice.BOND_NONE }
                val recommendedTftMtu = if (tft) {
                    BleProtocolSafety.recommendedTftMtu(
                        hasMediaInfo = mediaInfoCharacteristic != null,
                        hasFavRecent = favContactsCharacteristic != null || recentCallsCharacteristic != null,
                        hasPlaylist = playlistInfoCharacteristic != null
                    )
                } else {
                    BleProtocolSafety.DEFAULT_ATT_MTU
                }
                when {
                    tft && bondState == BluetoothDevice.BOND_BONDING -> {
                        Log.i(TAG, "MTU=$negotiatedMtu negotiated, waiting for TFT bonding to complete before activating link")
                        isWaitingForBondCompletion = true
                        bleHandler.removeCallbacks(bondTimeoutRunnable)
                        bleHandler.postDelayed(bondTimeoutRunnable, BOND_WAIT_TIMEOUT_MS)
                    }
                    tft && (bondFailedForSession || bondState != BluetoothDevice.BOND_BONDED) -> {
                        bondFailedForSession = true
                        abortHandshakeAndReconnect("TFT not bonded during MTU settlement (bondState=$bondState)", allowReconnect = false)
                    }
                    tft && negotiatedMtu < recommendedTftMtu && mtuRetryCount < MAX_MTU_RETRIES -> {
                        mtuRetryCount++
                        Log.w(
                            TAG,
                            "TFT MTU $negotiatedMtu < target $recommendedTftMtu; retrying requestMtu ($mtuRetryCount/$MAX_MTU_RETRIES)"
                        )
                        bleHandler.postDelayed(mtuRetryRunnable, MTU_RETRY_DELAY_MS)
                    }
                    BleProtocolSafety.canActivateLink(tft, negotiatedMtu, bondState, bondFailedForSession) -> {
                        if (tft && negotiatedMtu < recommendedTftMtu) {
                            Log.w(TAG, "Activating TFT link at partial MTU=$negotiatedMtu (target=$recommendedTftMtu); oversized characteristics will stay disabled")
                        }
                        onLinkReady("mtu=$negotiatedMtu status=$status")
                    }
                    mtuRetryCount < MAX_MTU_RETRIES -> {
                        mtuRetryCount++
                        Log.w(TAG, "MTU insufficient (mtu=$mtu status=$status, negotiated=$negotiatedMtu); retrying requestMtu ($mtuRetryCount/$MAX_MTU_RETRIES)")
                        bleHandler.postDelayed(mtuRetryRunnable, MTU_RETRY_DELAY_MS)
                    }
                    !tft -> {
                        onLinkReady("mtu=$mtu status=$status")
                    }
                    else -> {
                        abortHandshakeAndReconnect(
                            "TFT MTU negotiation failed after $MAX_MTU_RETRIES retries (mtu=$mtu status=$status)"
                        )
                    }
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            runOnBle {
                if (gatt !== bluetoothGatt) return@runOnBle
                bleHandler.removeCallbacks(ctlReadTimeoutRunnable)
                ctlReadPending = false
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BUTTON_CONTROL_CHAR_UUID) {
                    handleControlBytes(value)
                }
                processNextWrite()
            }
        }

        @Deprecated("Deprecated in Android BluetoothGattCallback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val data = characteristic.value
            runOnBle {
                if (gatt !== bluetoothGatt) return@runOnBle
                bleHandler.removeCallbacks(ctlReadTimeoutRunnable)
                ctlReadPending = false
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BUTTON_CONTROL_CHAR_UUID && data != null) {
                    handleControlBytes(data)
                }
                processNextWrite()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            runOnBle {
                if (gatt !== bluetoothGatt) return@runOnBle
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
                if (gatt !== bluetoothGatt) return@runOnBle
                if (characteristic.uuid == BUTTON_CONTROL_CHAR_UUID) {
                    handleControlBytes(data)
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            runOnBle {
                if (gatt !== bluetoothGatt) return@runOnBle
                bleHandler.removeCallbacks(writeTimeoutWatchdog)
                isWriting = false
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    consecutiveAuthWriteErrors = 0
                } else {
                    Log.w(TAG, "Write error status=$status on ${characteristic.uuid}")
                    DebugLogRepository.error("BLE_GATT", "Write failed (${charShortUuid(characteristic.uuid)}) status=$status")
                    if (BleProtocolSafety.isAuthOrEncryptionError(status)) {
                        if (activeProtocolStrategy == BleProtocolSafety.BleProtocolStrategy.OLD_LCD_LEGACY) {
                            Log.i(TAG, "OLD protocol write rejected (status=$status). Switching to NEW_TFT_MODERN protocol!")
                            switchToModernTftProtocol(gatt, "Auth error status=$status during OLD protocol test")
                            return@runOnBle
                        }
                        consecutiveAuthWriteErrors++
                        if (consecutiveAuthWriteErrors >= MAX_CONSECUTIVE_AUTH_ERRORS) {
                            handleStaleBondAuthFailure(status)
                            return@runOnBle
                        }
                    }
                }
                advanceWriteQueueOrFinishDisconnect()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            runOnBle {
                if (gatt !== bluetoothGatt) return@runOnBle
                bleHandler.removeCallbacks(writeTimeoutWatchdog)
                isWriting = false
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    consecutiveAuthWriteErrors = 0
                } else {
                    Log.w(TAG, "Descriptor write error status=$status on ${descriptor.uuid}")
                    if (BleProtocolSafety.isAuthOrEncryptionError(status)) {
                        if (activeProtocolStrategy == BleProtocolSafety.BleProtocolStrategy.OLD_LCD_LEGACY) {
                            Log.i(TAG, "OLD protocol CCCD write rejected (status=$status). Switching to NEW_TFT_MODERN protocol!")
                            switchToModernTftProtocol(gatt, "Auth error status=$status on CCCD during OLD protocol test")
                            return@runOnBle
                        }
                        consecutiveAuthWriteErrors++
                        if (consecutiveAuthWriteErrors >= MAX_CONSECUTIVE_AUTH_ERRORS) {
                            handleStaleBondAuthFailure(status)
                            return@runOnBle
                        }
                    }
                }
                advanceWriteQueueOrFinishDisconnect()
            }
        }
    }

    private fun onLinkReady(reason: String) {
        if (isLinkReady) return
        val gatt = bluetoothGatt ?: return
        val bondState = try {
            gatt.device.bondState
        } catch (_: SecurityException) {
            BluetoothDevice.BOND_NONE
        }
        if (!BleProtocolSafety.canActivateLink(isTftCluster(), negotiatedMtu, bondState, bondFailedForSession)) {
            Log.w(
                TAG,
                "Blocked onLinkReady($reason): TFT link requires MTU >= ${BleProtocolSafety.MIN_TFT_READY_MTU} and BOND_BONDED (mtu=$negotiatedMtu, bondState=$bondState, bondFailed=$bondFailedForSession)"
            )
            DebugLogRepository.error(
                "BLE_SAFETY",
                "Link activation gated ($reason): mtu=$negotiatedMtu, bond=$bondState, bondFailed=$bondFailedForSession"
            )
            return
        }
        isLinkReady = true
        bleHandler.removeCallbacks(pendingAclDisconnectRunnable)
        val address = connectedDeviceAddress.ifEmpty { gatt.device.address }
        _connectionState.value = BleConnectionState.Connected(connectedDeviceName, address)
        if (!hasStartedRideSession) {
            hasStartedRideSession = true
            com.pulsarnav.app.tracking.RideTracker.getInstance(context).onBikeConnected()
        }
        Log.i(TAG, "Pulsar Cluster Link READY ($reason)")
        DebugLogRepository.bleEvent(
            "Cluster link READY ($connectedDeviceName)",
            "reason=$reason, tft=${isTftCluster()}, mtu=$negotiatedMtu, bond=$bondState"
        )

        // Keep foreground service active to protect BLE link and background streaming
        try {
            com.pulsarnav.app.service.BikeBleForegroundService.start(context)
        } catch (_: Exception) {}

        if (isTftCluster() && com.pulsarnav.app.data.AppSettings.getInstance(context).isAutoWifiMirrorEnabled) {
            val wifiMgr = com.pulsarnav.app.wifi.TftWifiMirrorManager.getInstance(context)
            val currentStatus = wifiMgr.mirrorState.value.status
            if (currentStatus == com.pulsarnav.app.wifi.TftMirrorStatus.IDLE ||
                currentStatus == com.pulsarnav.app.wifi.TftMirrorStatus.ERROR) {
                Log.i(TAG, "TFT cluster link ready -> auto-launching preferred Headless Mirror (Live Map / Cockpit HUD)")
                try {
                    com.pulsarnav.app.service.BikeBleForegroundService.startForTftCockpitMirror(context)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed auto-starting headless TFT mirror: ${e.message}")
                }
            }
        }

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
                        enqueueOp(QueuedGattOp.WriteDescriptor(cccd, enableVal, sessionId = activeGattSessionId, priority = true))
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

        // Send initial track metadata, phonebook/call logs & playlist for TFT clusters
        if (isTftCluster()) {
            dispatchMusicInfoOnBle(force = true)
            bleHandler.removeCallbacks(initialTftSyncRunnable)
            bleHandler.postDelayed(initialTftSyncRunnable, 250L)
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

        controlFrameCount++
        DebugLogRepository.bleRx("0a10", b)
        if (!bootstrappedControls || controlFrameCount == 1) {
            bootstrappedControls = true
            callAcceptCount = frame.callAccept
            callRejectCount = frame.callReject
            callRejectWithSmsCount = frame.callRejectWithSms
            takeMeHomeCount = frame.takeMeHome
            missedCallGetCount = frame.missedCallGet
            alertGetCount = frame.alertGet
            resumeSongCount = frame.resumeSong
            pauseSongCount = frame.pauseSong
            skipToNextCount = frame.skipToNext
            skipToPrevCount = frame.skipToPrev
            stopSongCount = frame.stopSong
            launchMediaPlayerCount = frame.launchMediaPlayer
            lastClusterVolumeNibble = frame.volumeToSet
            lastDialSource = frame.dialSource
            lastDialTxn = frame.dialTxn
            dialEdgesArmed = (frame.dialSource == 0)
            lastPagePlaylist = frame.pagePlaylist
            lastNewPlaylistReq = frame.newPlaylistReq
            lastSelectPlaylistSongReq = frame.selectPlaylistSongReq
            return
        }

        val tftMode = isTftCluster()
        var needsGeneralRefresh = false
        var needsMediaRefresh = false
        var needsTbtRefresh = false
        val mediaManager = MediaControlManager.getInstance(context)
        val phonebookManager = ClusterPhonebookManager.getInstance(context)

        if (frame.callAccept != callAcceptCount) {
            callAcceptCount = frame.callAccept
            needsGeneralRefresh = true
            if (currentCallState == CallState.INCOMING_CALL) {
                Log.i(TAG, "Handlebar button: CALL ACCEPT pressed (${frame.callAccept})")
                // acceptIncomingCall() calls acknowledgeCallAction(accept = true) to bump callAcceptAckCount once
                CallManager.getInstance(context).acceptIncomingCall()
            } else if (!tftMode) {
                callAcceptAckCount = (callAcceptAckCount + 1) and 0xFF
                Log.i(TAG, "Handlebar button: MEDIA PLAY/PAUSE pressed")
                mediaManager.handleHandlebarButton(isLongPress = false)
            } else {
                callAcceptAckCount = (callAcceptAckCount + 1) and 0xFF
            }
        }
        if (frame.callReject != callRejectCount) {
            callRejectCount = frame.callReject
            needsGeneralRefresh = true
            if (currentCallState == CallState.INCOMING_CALL ||
                currentCallState == CallState.ACTIVE_CALL ||
                currentCallState == CallState.OUTGOING_CALL
            ) {
                Log.i(TAG, "Handlebar button: CALL REJECT pressed (${frame.callReject})")
                // rejectIncomingCall() calls acknowledgeCallAction(reject = true) to bump callRejectAckCount once
                CallManager.getInstance(context).rejectIncomingCall()
            } else if (!tftMode) {
                callRejectAckCount = (callRejectAckCount + 1) and 0xFF
                Log.i(TAG, "Handlebar button: MEDIA NEXT TRACK pressed")
                mediaManager.handleHandlebarButton(isLongPress = true)
            } else {
                callRejectAckCount = (callRejectAckCount + 1) and 0xFF
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
                skipToNextAckCount = (skipToNextAckCount + 1) and 0xFF
                Log.i(TAG, "TFT switchgear: NEXT TRACK (${frame.skipToNext}) -> ack=$skipToNextAckCount")
                mediaManager.dispatchNext()
                bleHandler.removeCallbacks(delayedMediaSyncRunnable)
                bleHandler.postDelayed(delayedMediaSyncRunnable, 350)
                needsGeneralRefresh = true
            }
            if (frame.skipToPrev != skipToPrevCount) {
                skipToPrevCount = frame.skipToPrev
                skipToPrevAckCount = (skipToPrevAckCount + 1) and 0xFF
                Log.i(TAG, "TFT switchgear: PREVIOUS TRACK (${frame.skipToPrev}) -> ack=$skipToPrevAckCount")
                mediaManager.dispatchPrevious()
                bleHandler.removeCallbacks(delayedMediaSyncRunnable)
                bleHandler.postDelayed(delayedMediaSyncRunnable, 350)
                needsGeneralRefresh = true
            }
            if (frame.stopSong != stopSongCount) {
                stopSongCount = frame.stopSong
                stopSongAckCount = (stopSongAckCount + 1) and 0xFF
                Log.i(TAG, "TFT switchgear: STOP SONG (${frame.stopSong}) -> ack=$stopSongAckCount")
                mediaManager.dispatchStop()
                needsGeneralRefresh = true
                needsMediaRefresh = true
            }
            if (frame.launchMediaPlayer != launchMediaPlayerCount) {
                launchMediaPlayerCount = frame.launchMediaPlayer
                launchMediaPlayerAckCount = (launchMediaPlayerAckCount + 1) and 0xFF
                Log.i(TAG, "TFT switchgear: LAUNCH MEDIA PLAYER (${frame.launchMediaPlayer}) -> ack=$launchMediaPlayerAckCount")
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

            if (frame.callRejectWithSms != callRejectWithSmsCount) {
                callRejectWithSmsCount = frame.callRejectWithSms
                Log.i(TAG, "TFT switchgear: CALL REJECT WITH SMS (${frame.callRejectWithSms})")
                _lastClusterAction.value = "Call Reject + SMS"
                if (currentCallState == CallState.INCOMING_CALL) {
                    CallManager.getInstance(context).rejectIncomingCallWithSms()
                } else {
                    callRejectSmsAckCount = (callRejectSmsAckCount + 1) and 0xFF
                    needsGeneralRefresh = true
                }
            }

            if (frame.takeMeHome != takeMeHomeCount) {
                takeMeHomeCount = frame.takeMeHome
                takeMeHomeAckCount = (takeMeHomeAckCount + 1) and 0xFF
                Log.i(TAG, "TFT switchgear: TAKE ME HOME (${frame.takeMeHome}) -> ack=$takeMeHomeAckCount")
                val launched = NavigationIntentLauncher.launchHomeNavigation(context)
                _lastClusterAction.value = if (launched) {
                    "Take Me Home launched"
                } else {
                    "Take Me Home: set Home in Settings"
                }
                needsTbtRefresh = true
            }

            if (frame.missedCallGet != missedCallGetCount) {
                missedCallGetCount = frame.missedCallGet
                missedCallGetAckCount = (missedCallGetAckCount + 1) and 0xFF
                Log.i(TAG, "TFT switchgear: MISSED CALL GET (${frame.missedCallGet}) -> ack=$missedCallGetAckCount")
                _lastClusterAction.value = "Missed Call sync"
                CallManager.getInstance(context).pushLatestMissedCallToCluster()
                needsGeneralRefresh = true
            }

            if (frame.alertGet != alertGetCount) {
                alertGetCount = frame.alertGet
                alertGetAckCount = (alertGetAckCount + 1) and 0xFF
                Log.i(TAG, "TFT switchgear: ALERT GET (${frame.alertGet}) -> ack=$alertGetAckCount")
                _lastClusterAction.value = "Alert sync"
                val cachedSender = lastAlertSenderName
                if (!cachedSender.isNullOrBlank()) {
                    sendWhatsAppAlert(cachedSender, lastAlertType)
                }
                needsGeneralRefresh = true
            }

            val dialTxnChanged = frame.dialTxn != lastDialTxn
            if (!dialEdgesArmed && !dialTxnChanged) {
                if (frame.dialSource == 0 || controlFrameCount >= 3) {
                    dialEdgesArmed = true
                }
                lastDialSource = frame.dialSource
                lastDialTxn = frame.dialTxn
            } else {
                dialEdgesArmed = true
                val shouldDial = frame.dialSource != 0 && ((lastDialSource == 0) || dialTxnChanged)
                lastDialSource = frame.dialSource
                lastDialTxn = frame.dialTxn
                if (shouldDial) {
                    val sourceSnapshot = frame.dialSource
                    val indexSnapshot = frame.dialIndex
                    if (indexSnapshot !in 0 until ClusterPhonebookManager.MAX_CLUSTER_ENTRIES) {
                        Log.w(TAG, "Ignoring out-of-range cluster dialIndex=$indexSnapshot (source=$sourceSnapshot)")
                    } else if (currentCallState == CallState.NO_CALL) {
                        phonebookExecutor.execute {
                            val target = phonebookManager.resolveDialTarget(sourceSnapshot, indexSnapshot)
                            if (target != null) {
                                val (name, number) = target
                                val placed = phonebookManager.placeClusterCall(name, number)
                                runOnBle {
                                    if (placed) {
                                        CallManager.getInstance(context).notifyOutgoingDialFromCluster(name, number)
                                        _lastClusterAction.value = "Dialing $name"
                                    } else {
                                        _lastClusterAction.value = "Dial failed (allow CALL_PHONE)"
                                    }
                                }
                            } else {
                                runOnBle {
                                    _lastClusterAction.value = "No contact in slot ${indexSnapshot + 1}"
                                }
                            }
                        }
                    } else {
                        Log.i(TAG, "Ignoring cluster dialback while callState=$currentCallState")
                    }
                }
            }

            val playlistPageChanged = frame.pagePlaylist != lastPagePlaylist
            val newPlaylistRequested = frame.newPlaylistReq != lastNewPlaylistReq
            if (playlistPageChanged || newPlaylistRequested) {
                lastPagePlaylist = frame.pagePlaylist
                lastNewPlaylistReq = frame.newPlaylistReq
                val requestedPage = frame.pagePlaylist.takeIf { it > 0 } ?: 1
                Log.i(TAG, "TFT switchgear: PLAYLIST page=$requestedPage, newReq=${frame.newPlaylistReq}")
                dispatchPlaylistInfoOnBle(
                    page = requestedPage,
                    newPlaylistAckVal = frame.newPlaylistReq and 0x03
                )
            }

            if (frame.selectPlaylistSongReq != lastSelectPlaylistSongReq) {
                lastSelectPlaylistSongReq = frame.selectPlaylistSongReq
                selectPlaylistSongAckCount = (selectPlaylistSongAckCount + 1) and 0xFF
                val activePage = frame.pagePlaylist.takeIf { it > 0 } ?: pagePlaylistAck
                mediaManager.selectPlaylistTrack(activePage, frame.selectedPlaylistSong)
                Log.i(
                    TAG,
                    "TFT switchgear: SELECT PLAYLIST SONG slot=${frame.selectedPlaylistSong} page=$activePage -> ack=$selectPlaylistSongAckCount"
                )
                _lastClusterAction.value = "Playlist track #${frame.selectedPlaylistSong + 1}"
                needsGeneralRefresh = true
                needsMediaRefresh = true
            }
        }

        if (needsGeneralRefresh) {
            dispatchGeneralPacketOnBle()
        }
        if (needsMediaRefresh) {
            dispatchMusicInfoOnBle(force = true)
        }
        if (needsTbtRefresh && isLinkReady) {
            if (isNavigationActive || _isSimulationRunning.value) {
                bleHandler.removeCallbacks(tbtStreamRunnable)
                bleHandler.post(tbtStreamRunnable)
            } else {
                tbtCharacteristic?.let { target ->
                    enqueueWrite(
                        target,
                        OtcPacketBuilder.buildTbtStopFrame(takeMeHomeAck = takeMeHomeAckCount),
                        priority = true
                    )
                }
            }
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
        val wasDifferent = activeNavStep == null ||
            activeNavStep?.distanceMeters != step.distanceMeters ||
            activeNavStep?.maneuver != step.maneuver ||
            activeNavStep?.streetName != step.streetName ||
            activeNavStep?.etaHour != step.etaHour ||
            activeNavStep?.etaMinute != step.etaMinute
        activeNavStep = step
        isNavigationActive = true
        _simulatedNavStep.value = null
        _currentActiveManeuver.value = step.maneuver

        if (wasDifferent) {
            lastTbtSendTimeMs = 0L
            lastSentTbtFrame = null
            bleHandler.removeCallbacks(tbtStreamRunnable)
            bleHandler.post(tbtStreamRunnable)
        }
    }

    /**
     * Stops turn-by-turn navigation and dispatches the verified stop frame (type = 0)
     * to clear the cluster display.
     */
    fun stopNavigation() = runOnBle {
        isNavigationActive = false
        activeNavStep = null
        _simulatedNavStep.value = null
        _currentActiveManeuver.value = null
        lastSentTbtFrame = null
        lastTbtSendTimeMs = 0L
        bleHandler.removeCallbacks(tbtStreamRunnable)

        val stopFrame = OtcPacketBuilder.buildTbtStopFrame()
        if (isLinkReady) {
            _lastSentPacket.value = stopFrame
            tbtCharacteristic?.let { char ->
                enqueueWrite(char, stopFrame, priority = true)
            }
        }
        DebugLogRepository.bleEvent("Navigation stopped (0110 TBT_STOP)")
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

        val eta = LocalTime.now().plusMinutes(6)
        val rawHour = eta.hour % 12
        val h = if (rawHour == 0) 12 else rawHour

        val simStep = ParsedNavigationStep(
            maneuver = maneuver,
            distanceMeters = distanceMeters,
            totalDistanceMeters = distanceMeters + 1200,
            streetName = streetText,
            rawDistanceText = "${distanceMeters}m",
            etaHour = h,
            etaMinute = eta.minute,
            etaIsAm = eta.hour < 12,
            roundaboutExit = roundaboutExit
        )
        activeNavStep = simStep
        _simulatedNavStep.value = simStep

        Log.i(TAG, "▶Started Cluster Simulation for ${maneuver.description} ($distanceMeters m)")
        DebugLogRepository.bleEvent(
            "Simulation started: ${maneuver.description} (${distanceMeters}m)",
            streetText
        )
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
            _simulatedNavStep.value = null
            bleHandler.removeCallbacks(tbtStreamRunnable)

            Log.i(TAG, "⏹Stopped Cluster Simulation -> Sending Stop Frame")
            DebugLogRepository.bleEvent("Simulation stopped -> 0110 TBT_STOP")
            val stopFrame = OtcPacketBuilder.buildTbtStopFrame()
            _lastSentPacket.value = stopFrame
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
        if (batteryPercent == currentBattery && volume == currentVolume && signalBars == currentSignal) {
            return@runOnBle
        }
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
        missedCallCharacteristic?.let { target ->
            if (isLinkReady) enqueueWrite(target, pkt, priority = true)
        }
        dispatchGeneralPacketOnBle(allowInspectorPreviewWhenDisconnected = true)
    }

    fun acknowledgeCallAction(
        accept: Boolean = false,
        reject: Boolean = false,
        rejectSms: Boolean = false
    ) = runOnBle {
        if (accept) callAcceptAckCount = (callAcceptAckCount + 1) and 0xFF
        if (reject) callRejectAckCount = (callRejectAckCount + 1) and 0xFF
        if (rejectSms) callRejectSmsAckCount = (callRejectSmsAckCount + 1) and 0xFF
        if (isLinkReady) {
            dispatchGeneralPacketOnBle()
        }
    }

    private fun sanitizeBleName(raw: String?): String =
        BleProtocolSafety.normalizeBleName(raw)

    private fun isTftCluster(deviceName: String = connectedDeviceName): Boolean =
        activeProtocolStrategy == BleProtocolSafety.BleProtocolStrategy.NEW_TFT_MODERN ||
        (activeProtocolStrategy != BleProtocolSafety.BleProtocolStrategy.OLD_LCD_LEGACY &&
            BleProtocolSafety.isTftCluster(
                deviceName = deviceName,
                selectedModelId = com.pulsarnav.app.data.AppSettings.getInstance(context).selectedModelId,
                hasMediaInfoCharacteristic = mediaInfoCharacteristic != null,
                hasPlaylistCharacteristic = playlistInfoCharacteristic != null,
                hasFavRecentCharacteristic = favContactsCharacteristic != null || recentCallsCharacteristic != null,
                userOverride = com.pulsarnav.app.data.AppSettings.getInstance(context).clusterConsoleType
            ))

    fun isConnectedToTftCluster(): Boolean = isTftCluster()

    fun getConnectedClusterName(): String = connectedDeviceName

    private fun dispatchGeneralPacketOnBle(allowInspectorPreviewWhenDisconnected: Boolean = false) {
        if (!isLinkReady && !allowInspectorPreviewWhenDisconnected) {
            return
        }
        val target = generalCharacteristic ?: return

        val pkt = if (activeProtocolStrategy == BleProtocolSafety.BleProtocolStrategy.OLD_LCD_LEGACY) {
            OtcPacketBuilder.buildGeneralPacketLegacy(
                batteryPercent = currentBattery,
                volumeLevel = currentVolume,
                callState = currentCallState,
                callerName = currentCallerName,
                callRejectAck = callRejectAckCount,
                callRejectSmsAck = callRejectSmsAckCount,
                missedCallCount = currentMissedCount,
                missedCallGetAck = missedCallGetAckCount
            )
        } else {
            OtcPacketBuilder.buildGeneralPacketV2(
                batteryPercent = currentBattery,
                volumeLevel = currentVolume,
                signalBars = currentSignal,
                callState = currentCallState,
                callerName = currentCallerName,
                callAcceptAck = callAcceptAckCount,
                callRejectAck = callRejectAckCount,
                callRejectSmsAck = callRejectSmsAckCount,
                missedCallCount = currentMissedCount,
                missedCallGetAck = missedCallGetAckCount,
                resumeSongAck = resumeSongAckCount,
                pauseSongAck = pauseSongAckCount,
                skipToNextAck = skipToNextAckCount,
                skipToPrevAck = skipToPrevAckCount,
                stopSongAck = stopSongAckCount,
                launchMediaPlayerAck = launchMediaPlayerAckCount,
                alertsGetAck = alertGetAckCount,
                selectPlaylistSongAck = selectPlaylistSongAckCount
            )
        }

        if (isLinkReady || allowInspectorPreviewWhenDisconnected) {
            _lastSentPacket.value = pkt
        }
        if (isLinkReady) {
            val isUrgentCall = currentCallState != CallState.NO_CALL
            enqueueWrite(target, pkt, priority = isUrgentCall)
        }
    }

    private fun dispatchMusicInfoOnBle(force: Boolean = false) {
        if (!isLinkReady || isDisconnecting) return
        val target = mediaInfoCharacteristic ?: return
        if (!BleProtocolSafety.supportsMediaInfoWrites(negotiatedMtu)) {
            Log.w(TAG, "Skipping 0610 Media Info sync: MTU=$negotiatedMtu < 114 required for 111B frame")
            return
        }
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

    fun sendWhatsAppAlert(
        senderName: String,
        alertType: AlertType = AlertType.WHATSAPP
    ) = runOnBle {
        lastAlertSenderName = senderName
        lastAlertType = alertType
        val ts = System.currentTimeMillis()
        val alertSnapshot = SimulatedAlertInfo(senderName, alertType, ts)
        _simulatedAlert.value = alertSnapshot
        bleHandler.postDelayed({
            if (_simulatedAlert.value?.timestampMs == ts) {
                _simulatedAlert.value = null
            }
        }, 6000L)
        val pkt = OtcPacketBuilder.buildWhatsAppAlertPacket(senderName, alertType)
        _lastSentPacket.value = pkt
        DebugLogRepository.bleTx("0410", pkt)
        alertsCharacteristic?.let { target ->
            if (isLinkReady) {
                // Pause turn-by-turn stream for 6 seconds so the cluster doesn't immediately overwrite the alert
                alertDisplayHoldUntilMs = System.currentTimeMillis() + 6000L
                enqueueWrite(target, pkt, priority = true)
            }
        }
    }

    /**
     * Queries starred contacts (0910) and recent/missed calls (0b10) off the BLE thread
     * and dispatches them as single-PDU writes on TFT clusters.
     */
    fun syncPhonebookAndCalls() {
        val gatt = bluetoothGatt
        if (!isLinkReady || gatt == null || !isTftCluster()) return
        if (!BleProtocolSafety.supportsFavRecentWrites(negotiatedMtu)) {
            Log.d(
                TAG,
                "Skipping 0910/0b10 phonebook sync: MTU $negotiatedMtu < ${BleProtocolSafety.FAV_RECENT_PACKET_BYTES + BleProtocolSafety.ATT_HEADER_BYTES}"
            )
            return
        }
        val favChar = favContactsCharacteristic
        val recentChar = recentCallsCharacteristic
        if (favChar == null && recentChar == null) return

        val phonebook = ClusterPhonebookManager.getInstance(context)
        val callManager = CallManager.getInstance(context)
        phonebookExecutor.execute {
            val favs = phonebook.queryFavoriteContacts()
            val recents = phonebook.queryRecentCalls()
            val latestMissed = phonebook.getLatestMissedCall()
            runOnBle {
                if (!isLinkReady || !isTftCluster()) return@runOnBle
                if (!BleProtocolSafety.supportsFavRecentWrites(negotiatedMtu)) return@runOnBle

                favContactsCharacteristic?.let { target ->
                    val favPkt = OtcPacketBuilder.buildFavContactsFrame(favs)
                    enqueueWrite(target, favPkt, priority = false)
                }
                recentCallsCharacteristic?.let { target ->
                    val recentPkt = OtcPacketBuilder.buildRecentCallsFrame(recents)
                    enqueueWrite(target, recentPkt, priority = false)
                }
                _lastClusterAction.value = "Synced ${favs.size} favs, ${recents.size} recents"
                Log.i(TAG, "Queued TFT phonebook sync: favs=${favs.size}, recents=${recents.size}")
                DebugLogRepository.telephony("Phonebook", "Synced ${favs.size} favs & ${recents.size} recents to cluster")
                if (latestMissed != null && missedCallGetCount > 0) {
                    callManager.pushLatestMissedCallToCluster()
                }
            }
        }
    }

    /**
     * Encodes the current media queue / recent track page onto TFT characteristic 0510 (177B).
     */
    fun dispatchPlaylistInfoOnBle(
        page: Int = pagePlaylistAck,
        newPlaylistAckVal: Int = newPlaylistAck
    ) = runOnBle {
        if (!isLinkReady || !isTftCluster()) return@runOnBle
        if (!BleProtocolSafety.supportsPlaylistWrites(negotiatedMtu)) {
            Log.d(
                TAG,
                "Skipping 0510 playlist write: MTU $negotiatedMtu < ${BleProtocolSafety.PLAYLIST_PACKET_BYTES + BleProtocolSafety.ATT_HEADER_BYTES}"
            )
            return@runOnBle
        }
        val target = playlistInfoCharacteristic ?: return@runOnBle
        val mediaManager = MediaControlManager.getInstance(context)
        val playlistPage = mediaManager.buildClusterPlaylistPage(
            requestedPage = page,
            newPlaylistAck = newPlaylistAckVal
        )
        pagePlaylistAck = playlistPage.pagePlaylistAck
        newPlaylistAck = playlistPage.newPlaylistAck
        val pkt = OtcPacketBuilder.buildPlaylistFrame(
            trackTitles = playlistPage.trackTitles,
            totalSongs = playlistPage.totalSongs,
            totalPages = playlistPage.totalPages,
            currentTrackNumber = playlistPage.currentTrackNumber,
            pagePlaylistAck = playlistPage.pagePlaylistAck,
            newPlaylistAck = playlistPage.newPlaylistAck,
            userChangePlaylistCounter = playlistPage.userChangePlaylistCounter
        )
        enqueueWrite(target, pkt, priority = false)
    }

    // =========================================================================
    // Serialized GATT Write Queue (runs exclusively on bleHandler)
    // =========================================================================

    private fun enqueueWrite(char: BluetoothGattCharacteristic, packet: ByteArray, priority: Boolean = false) {
        if (!isLinkReady || isDisconnecting) {
            Log.w(TAG, "Write skipped (${char.uuid}): link not ready or disconnecting")
            return
        }

        val tft = isTftCluster()
        // TFT clusters (NS400Z / PULSAR1854) and 0610 media info require WRITE_TYPE_DEFAULT (ATT Write Request),
        // while legacy LCD clusters (NS200) use WRITE_TYPE_NO_RESPONSE for real-time telemetry/alerts.
        val useWriteDefault = (char.uuid == MEDIA_INFO_CHAR_UUID || tft) &&
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

        val isDefaultWriteType = writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (!BleProtocolSafety.fitsSingleAttWrite(tft, isDefaultWriteType, packet.size, negotiatedMtu)) {
            Log.w(
                TAG,
                "Blocked ATT Long Write to ${char.uuid}: packet ${packet.size}B > max single-PDU payload ${BleProtocolSafety.maxSingleWritePayload(negotiatedMtu)}B (mtu=$negotiatedMtu)"
            )
            DebugLogRepository.error(
                "BLE_SAFETY",
                "Blocked Long Write (${charShortUuid(char.uuid)}): ${packet.size}B > ${BleProtocolSafety.maxSingleWritePayload(negotiatedMtu)}B (mtu=$negotiatedMtu)"
            )
            return
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

        enqueueOp(QueuedGattOp.WriteCharacteristic(char, packet, writeType, sessionId = activeGattSessionId, priority = priority))
    }

    private fun enqueueOp(op: QueuedGattOp) {
        if (op.sessionId != activeGattSessionId) {
            return
        }
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
        if (isWriting || ctlReadPending) return
        val gatt = bluetoothGatt ?: return
        val item = writeQueue.pollFirst() ?: return

        isWriting = true
        if (item is QueuedGattOp.WriteCharacteristic) {
            _lastSentPacket.value = item.data
            val shortId = charShortUuid(item.characteristic.uuid)
            when (shortId) {
                "0110" -> {
                    val typeBits = if (item.data.isNotEmpty()) item.data[0].toInt() and 0xFF else 0
                    val iconByte = if (item.data.size > 1) item.data[1].toInt() and 0xFF else 0
                    val decodedKey = "0110:$typeBits:$iconByte:${item.data.size}"
                    DebugLogRepository.bleTx("0110", item.data, rateLimitKey = decodedKey, minIntervalMs = 4_000L)
                }
                "0210" -> {
                    val callByte = if (item.data.size > 18) item.data[18].toInt() and 0xFF else 0
                    val batByte = if (item.data.size > 14) item.data[14].toInt() and 0xFF else 0
                    val rateKey = "0210:${item.data.size}:$callByte:$batByte"
                    DebugLogRepository.bleTx("0210", item.data, rateLimitKey = rateKey, minIntervalMs = 15_000L)
                }
                else -> DebugLogRepository.bleTx(shortId, item.data)
            }
        }

        bleHandler.removeCallbacks(writeTimeoutWatchdog)
        val timeout = when (item) {
            is QueuedGattOp.WriteCharacteristic ->
                if (item.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) WRITE_NO_RESPONSE_TIMEOUT_MS else 1200L
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
                if (item.attempts < 2 && item.sessionId == activeGattSessionId) {
                    item.attempts++
                    val retryRunnable = object : Runnable {
                        override fun run() {
                            pendingRetryRunnables.remove(this)
                            if (item.sessionId == activeGattSessionId && !isDisconnecting) {
                                enqueueOp(item)
                            } else {
                                advanceWriteQueueOrFinishDisconnect()
                            }
                        }
                    }
                    pendingRetryRunnables.add(retryRunnable)
                    bleHandler.postDelayed(retryRunnable, 100L)
                    if (writeQueue.isNotEmpty()) {
                        advanceWriteQueueOrFinishDisconnect()
                    }
                } else {
                    advanceWriteQueueOrFinishDisconnect()
                }
            }
        } catch (e: Exception) {
            bleHandler.removeCallbacks(writeTimeoutWatchdog)
            Log.w(TAG, "Exception during GATT write: ${e.message}")
            isWriting = false
            advanceWriteQueueOrFinishDisconnect()
        }
    }

    private fun charShortUuid(uuid: UUID): String {
        return uuid.toString().substring(0, 4).lowercase(java.util.Locale.US)
    }

    fun getDebugDiagnosticsSummary(): String {
        val gatt = bluetoothGatt
        val bondState = try {
            gatt?.device?.bondState ?: -1
        } catch (_: SecurityException) {
            -1
        }
        val bondLabel = when (bondState) {
            BluetoothDevice.BOND_BONDED -> "BONDED"
            BluetoothDevice.BOND_BONDING -> "BONDING"
            BluetoothDevice.BOND_NONE -> "NONE"
            else -> "N/A"
        }
        return "BLE ready=$isLinkReady • tft=${isTftCluster()} • mtu=$negotiatedMtu • bond=$bondLabel • call=$currentCallState"
    }

    private fun handleStaleBondAuthFailure(status: Int) {
        val msg = "Cluster pairing key mismatch (GATT $status) — forget bike in Bluetooth Settings and re-pair"
        Log.e(TAG, msg)
        DebugLogRepository.error("BLE_AUTH", msg)
        bondFailedForSession = true
        endRideSessionIfActive()
        closeGatt(callDisconnect = true)
        _connectionState.value = BleConnectionState.Error(msg)
    }

    private fun advanceWriteQueueOrFinishDisconnect() {
        if (isDisconnecting && writeQueue.isEmpty()) {
            finishDisconnectCleanup()
        } else {
            processNextWrite()
        }
    }
}
