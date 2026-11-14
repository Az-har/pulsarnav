package com.pulsarnav.app.wifi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.projection.MediaProjection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Surface
import com.pulsarnav.app.ble.BajajBleManager
import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.media.MediaControlManager
import com.pulsarnav.app.service.BikeBleForegroundService
import com.pulsarnav.app.service.GoogleMapsNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class TftMirrorStatus {
    IDLE,
    CONNECTING_WIFI,
    WIFI_CONNECTED_WAITING_TCP,
    TCP_CONNECTED_IDLE,
    STREAMING,
    ERROR
}

enum class TftMirrorSource {
    LIVE_MAP,
    COCKPIT_HUD,
    LIVE_SCREEN_CAST
}

data class TftMirrorState(
    val status: TftMirrorStatus = TftMirrorStatus.IDLE,
    val source: TftMirrorSource = TftMirrorSource.LIVE_MAP,
    val ssid: String = "",
    val clusterHost: String = TftWifiProtocol.DEFAULT_CLUSTER_HOST,
    val isBikeStationary: Boolean = true,
    val fps: Float = 0f,
    val jpegQuality: Int = TftWifiProtocol.UDP_DEFAULT_JPEG_QUALITY,
    val lastFrameBytes: Int = 0,
    val framesSent: Long = 0L,
    val lastTouchInfo: String = "",
    val errorMessage: String? = null
)

/**
 * Manages the Bajaj TFT cluster Wi-Fi SoftAP connection, TCP 5558 bidirectional control link,
 * and UDP 5556 encrypted frame streaming pipeline.
 *
 * All sockets bind directly to the SoftAP [Network] via `network.bindSocket(...)` without calling
 * `bindProcessToNetwork`, keeping cellular mobile data functional for Google Maps routing/traffic.
 */
class TftWifiMirrorManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TftWifiMirrorManager"
        private const val WIFI_REQUEST_TIMEOUT_MS = 35_000
        private const val SOFTAP_RECONNECT_DELAY_MS = 1500L
        private const val DOC_PREVIEW_WIDTH = 480
        private const val DOC_PREVIEW_HEIGHT = 320
        // Stay comfortably below the 65,530-byte OEM CMD_DOC_DATA 16-bit header threshold.
        private const val MAX_DOC_PREVIEW_BYTES = 60_000

        @Volatile
        private var instance: TftWifiMirrorManager? = null

        fun getInstance(context: Context): TftWifiMirrorManager {
            return instance ?: synchronized(this) {
                instance ?: TftWifiMirrorManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private data class CachedDocPreview(val cacheKey: String, val bytes: ByteArray)

    private val docPreviewCache = ConcurrentHashMap<Int, CachedDocPreview>()
    private val sessionLock = Any()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val appSettings = AppSettings.getInstance(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val udpDispatcherExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TftUdpSenderThread").apply { isDaemon = true }
    }

    private val _mirrorState = MutableStateFlow(TftMirrorState())
    val mirrorState: StateFlow<TftMirrorState> = _mirrorState.asStateFlow()

    private val isActiveSession = AtomicBoolean(false)
    private val clusterStreamingEnabled = AtomicBoolean(false)
    private val tcpWriteLock = Any()
    private val pendingJpegRef = AtomicReference<ByteArray?>(null)
    private val udpWakeLock = Object()

    @Volatile
    private var activeNetwork: Network? = null
    @Volatile
    private var activeSsid: String = ""
    @Volatile
    private var activePassword: String = ""
    @Volatile
    private var activeSourceMode: TftMirrorSource = TftMirrorSource.LIVE_MAP
    @Volatile
    private var tcpOutputStream: OutputStream? = null
    @Volatile
    private var tcpSocket: Socket? = null
    @Volatile
    private var udpSocket: DatagramSocket? = null

    private var registeredNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectRunnable: Runnable? = null
    private var tcpSessionJob: Job? = null
    private var udpLoopFuture: java.util.concurrent.Future<*>? = null

    private var fpsWindowStartMs = 0L
    private var fpsWindowFrames = 0
    private val framesSentCounter = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile
    private var latestEncodedQuality = TftWifiProtocol.UDP_DEFAULT_JPEG_QUALITY
    @Volatile
    private var latestEncodedBytes = 0

    private val navV2Controller = TftNavV2Controller(context)

    private val displayController = TftCockpitDisplayController(
        context = context,
        onEncodedFrameReady = { jpeg, quality, bytes ->
            onDisplayFrameEncoded(jpeg, quality, bytes)
        },
        onProjectionStoppedUnexpectedly = {
            if (isActiveSession.get() && _mirrorState.value.source == TftMirrorSource.LIVE_SCREEN_CAST) {
                handleScreenLockedDuringCast()
            }
        }
    )

    private var savedAccelRotation: Int? = null
    private var savedUserRotation: Int? = null
    private var rotationModifiedByCast = false
    private val isTransitioningToHeadless = java.util.concurrent.atomic.AtomicBoolean(false)

    fun handleScreenLockedDuringCast() {
        if (!isActiveSession.get() || _mirrorState.value.source != TftMirrorSource.LIVE_SCREEN_CAST) return
        if (!isTransitioningToHeadless.compareAndSet(false, true)) return
        try {
            Log.i(TAG, "Screen locked during LIVE_SCREEN_CAST -> transitioning to lock-proof headless mirror")
            com.pulsarnav.app.debug.DebugLogRepository.wifi(
                "TFT_CAST",
                "Screen locked -> switching to lock-proof ${if (appSettings.tftMirrorMode == AppSettings.TFT_MODE_LIVE_MAP) "Live Map" else "Cockpit HUD"}"
            )
            maybeApplyCastLandscapeRotation(false)
            startPreferredHeadlessMirroring()
            BikeBleForegroundService.dropMediaProjection(context, keepWifiMirror = true)
        } finally {
            isTransitioningToHeadless.set(false)
        }
    }

    private fun stopLiveMapIfUnused() {
        if (!mapEngine.uiState.value.routeActive && !mapEngine.hasPendingRoute()) {
            mapEngine.stopLocationTracking()
        }
    }

    private fun maybeApplyCastLandscapeRotation(enable: Boolean) {
        try {
            if (enable) {
                if (!appSettings.isTftCastForceLandscapeEnabled) return
                if (!Settings.System.canWrite(context)) {
                    Log.w(TAG, "Auto-landscape skipped: android.permission.WRITE_SETTINGS not granted")
                    return
                }
                val resolver = context.contentResolver
                if (!rotationModifiedByCast) {
                    savedAccelRotation = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1)
                    savedUserRotation = Settings.System.getInt(resolver, Settings.System.USER_ROTATION, Surface.ROTATION_0)
                }
                Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
                Settings.System.putInt(resolver, Settings.System.USER_ROTATION, Surface.ROTATION_90)
                rotationModifiedByCast = true
                Log.i(TAG, "Auto-landscape: system orientation locked to Surface.ROTATION_90")
            } else if (rotationModifiedByCast) {
                if (Settings.System.canWrite(context)) {
                    val resolver = context.contentResolver
                    savedAccelRotation?.let {
                        Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, it)
                    }
                    savedUserRotation?.let {
                        Settings.System.putInt(resolver, Settings.System.USER_ROTATION, it)
                    }
                    Log.i(TAG, "Auto-landscape: system orientation restored to previous settings")
                }
                rotationModifiedByCast = false
                savedAccelRotation = null
                savedUserRotation = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "System rotation update skipped: ${e.message}")
        }
    }

    private val mapEngine: TftNavigationMapEngine by lazy {
        TftNavigationMapEngine.getInstance(context).also { engine ->
            engine.setInvalidationListener {
                if (isActiveSession.get() || isLocalPreviewActive.get()) {
                    displayController.refreshFrameNow()
                }
            }
        }
    }

    fun startPreferredHeadlessMirroring() {
        val preferred = if (appSettings.tftMirrorMode == AppSettings.TFT_MODE_HUD) {
            TftMirrorSource.COCKPIT_HUD
        } else {
            TftMirrorSource.LIVE_MAP
        }
        startInternal(preferred, null)
    }

    fun startLiveMapMirroring() {
        appSettings.tftMirrorMode = AppSettings.TFT_MODE_LIVE_MAP
        startInternal(TftMirrorSource.LIVE_MAP, null)
    }

    fun startCockpitHudMirroring() {
        startInternal(TftMirrorSource.COCKPIT_HUD, null)
    }

    fun switchHeadlessModeIfActive(mode: String) {
        appSettings.tftMirrorMode = mode
        if (!isActiveSession.get() && !isLocalPreviewActive.get()) return
        val nextSource = if (mode == AppSettings.TFT_MODE_HUD) {
            TftMirrorSource.COCKPIT_HUD
        } else {
            TftMirrorSource.LIVE_MAP
        }
        if (nextSource == TftMirrorSource.LIVE_MAP) {
            mapEngine.startLocationTracking()
        } else {
            stopLiveMapIfUnused()
        }
        if (isActiveSession.get() && _mirrorState.value.source != TftMirrorSource.LIVE_SCREEN_CAST) {
            startInternal(nextSource, null)
        } else if (isLocalPreviewActive.get()) {
            displayController.startHeadless(nextSource)
        }
    }

    fun startLiveScreenMirroring(mediaProjection: MediaProjection): Boolean {
        val ok = startInternal(TftMirrorSource.LIVE_SCREEN_CAST, mediaProjection)
        if (ok) {
            maybeApplyCastLandscapeRotation(true)
        }
        return ok
    }

    fun stopMirroring() {
        synchronized(sessionLock) {
            stopMirroringLocked()
        }
    }

    private fun stopMirroringLocked(preserveErrorState: Boolean = false) {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        isActiveSession.set(false)
        clusterStreamingEnabled.set(false)
        isLocalPreviewActive.set(false)
        _isLocalPreviewActiveFlow.value = false
        maybeApplyCastLandscapeRotation(false)
        displayController.stop()
        stopLiveMapIfUnused()
        unregisterWifiCallback()
        closeSockets()
        tcpSessionJob?.cancel()
        tcpSessionJob = null
        udpLoopFuture?.cancel(true)
        udpLoopFuture = null
        synchronized(udpWakeLock) {
            udpWakeLock.notifyAll()
        }
        pendingJpegRef.set(null)
        activeNetwork = null
        activePassword = ""
        if (!preserveErrorState) {
            _mirrorState.value = TftMirrorState(status = TftMirrorStatus.IDLE)
        }
        Log.i(TAG, "TFT Wi-Fi mirroring stopped")
    }

    private fun startInternal(source: TftMirrorSource, projection: MediaProjection?): Boolean {
        synchronized(sessionLock) {
            val bleName = BajajBleManager.getInstance(context).getConnectedClusterName()
            val ssid = resolveTargetSsid(bleName)
            val password = resolveTargetPassword()

            // Reuse active SoftAP connection & TCP/UDP workers across HUD <-> Live Cast transitions
            // so Android never tears down WifiNetworkSpecifier or re-prompts the OS Wi-Fi consent sheet.
            val canPreserveSoftAp = isActiveSession.get() && activeNetwork != null
            if (canPreserveSoftAp) {
                activeSourceMode = source
                isLocalPreviewActive.set(false)
                _isLocalPreviewActiveFlow.value = false
                if (source == TftMirrorSource.LIVE_SCREEN_CAST && projection != null) {
                    val castStarted = displayController.startLiveScreenCast(projection)
                    if (!castStarted) {
                        Log.w(TAG, "Live screen cast switch failed; keeping failure clean instead of stale projection")
                        stopMirroringLocked(preserveErrorState = true)
                        _mirrorState.value = TftMirrorState(
                            status = TftMirrorStatus.ERROR,
                            source = source,
                            ssid = ssid,
                            errorMessage = "Could not start live screen cast VirtualDisplay."
                        )
                        return false
                    }
                    } else {
                        maybeApplyCastLandscapeRotation(false)
                        if (source == TftMirrorSource.LIVE_MAP) {
                            mapEngine.startLocationTracking()
                        } else {
                            stopLiveMapIfUnused()
                        }
                        displayController.startHeadless(source)
                    }
                    _mirrorState.value = _mirrorState.value.copy(
                        source = source,
                        errorMessage = null
                    )
                    return true
                }

            stopMirroringLocked()
            activeSsid = ssid
            activePassword = password
            activeSourceMode = source

            val displayOk = if (source == TftMirrorSource.LIVE_SCREEN_CAST && projection != null) {
                stopLiveMapIfUnused()
                displayController.startLiveScreenCast(projection)
            } else {
                if (source == TftMirrorSource.LIVE_MAP) {
                    mapEngine.startLocationTracking()
                } else {
                    stopLiveMapIfUnused()
                }
                displayController.startHeadless(source)
                true
            }
            if (!displayOk) {
                stopMirroringLocked(preserveErrorState = true)
                _mirrorState.value = TftMirrorState(
                    status = TftMirrorStatus.ERROR,
                    source = source,
                    ssid = ssid,
                    errorMessage = "Could not start live screen cast VirtualDisplay."
                )
                return false
            }

            isActiveSession.set(true)
            clusterStreamingEnabled.set(true)

            val existing = findAlreadyConnectedClusterWifi()
            if (existing != null) {
                val (net, host) = existing
                Log.i(TAG, "Adopting already-connected SoftAP Wi-Fi network toward $host")
                onClusterWifiReady(net, host, ssid, source)
                return true
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                stopMirroringLocked(preserveErrorState = true)
                _mirrorState.value = TftMirrorState(
                    status = TftMirrorStatus.ERROR,
                    source = source,
                    ssid = ssid,
                    errorMessage = "Join bike Wi-Fi hotspot \"$ssid\" in Android Settings first, then start mirror."
                )
                return false
            }

            if (password.isBlank()) {
                stopMirroringLocked(preserveErrorState = true)
                _mirrorState.value = TftMirrorState(
                    status = TftMirrorStatus.ERROR,
                    source = source,
                    ssid = ssid,
                    errorMessage = "Enter Bike VIN or SoftAP WPA2 Password in TFT Wi-Fi Settings (or join \"$ssid\" via Android Settings)."
                )
                return false
            }

            _mirrorState.value = TftMirrorState(
                status = TftMirrorStatus.CONNECTING_WIFI,
                source = source,
                ssid = ssid,
                errorMessage = null
            )

            requestSoftApNetwork(ssid, password)
            return true
        }
    }

    private fun requestSoftApNetwork(ssid: String, passphrase: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        unregisterWifiCallback()
        try {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(passphrase)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!isActiveSession.get()) return
                    reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                    reconnectRunnable = null
                    val gateway = resolveGatewayHost(network)
                    Log.i(TAG, "SoftAP network available: SSID=$ssid gateway=$gateway")
                    onClusterWifiReady(network, gateway, ssid, activeSourceMode)
                }

                override fun onUnavailable() {
                    if (registeredNetworkCallback === this) {
                        registeredNetworkCallback = null
                    }
                    if (!isActiveSession.get()) return
                    Log.w(TAG, "SoftAP connection unavailable or timed out for SSID=$ssid")
                    isActiveSession.set(false)
                    clusterStreamingEnabled.set(false)
                    displayController.stop()
                    _mirrorState.value = _mirrorState.value.copy(
                        status = TftMirrorStatus.ERROR,
                        ssid = ssid,
                        errorMessage = "Could not join bike Wi-Fi \"$ssid\". Check Wi-Fi ON on TFT, VIN/password, or join manually in Android Settings."
                    )
                    BikeBleForegroundService.dropMediaProjection(context, keepWifiMirror = false)
                }

                override fun onLost(network: Network) {
                    if (network == activeNetwork && isActiveSession.get()) {
                        Log.w(TAG, "SoftAP network lost — cancelling sockets/workers and scheduling recovery")
                        activeNetwork = null
                        tcpSessionJob?.cancel()
                        tcpSessionJob = null
                        udpLoopFuture?.cancel(true)
                        udpLoopFuture = null
                        synchronized(udpWakeLock) {
                            udpWakeLock.notifyAll()
                        }
                        closeSockets()
                        _mirrorState.value = _mirrorState.value.copy(
                            status = TftMirrorStatus.CONNECTING_WIFI,
                            errorMessage = "Cluster Wi-Fi signal lost — reconnecting..."
                        )
                        scheduleSoftApRecovery()
                    }
                }
            }

            registeredNetworkCallback = callback
            connectivityManager.requestNetwork(request, callback, mainHandler, WIFI_REQUEST_TIMEOUT_MS)
        } catch (e: SecurityException) {
            registeredNetworkCallback = null
            Log.e(TAG, "Missing Wi-Fi permission for SoftAP request: ${e.message}")
            stopMirroringLocked(preserveErrorState = true)
            _mirrorState.value = _mirrorState.value.copy(
                status = TftMirrorStatus.ERROR,
                errorMessage = "Wi-Fi permission denied. Grant Nearby Wi-Fi / Location permission."
            )
        } catch (e: Exception) {
            registeredNetworkCallback = null
            Log.e(TAG, "Failed requesting SoftAP network: ${e.message}", e)
            stopMirroringLocked(preserveErrorState = true)
            _mirrorState.value = _mirrorState.value.copy(
                status = TftMirrorStatus.ERROR,
                errorMessage = "Wi-Fi SoftAP setup failed: ${e.message}"
            )
        }
    }

    private fun scheduleSoftApRecovery() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            synchronized(sessionLock) {
                if (!isActiveSession.get() || activeNetwork != null) return@synchronized
                val existing = findAlreadyConnectedClusterWifi()
                if (existing != null) {
                    val (net, host) = existing
                    onClusterWifiReady(net, host, activeSsid, activeSourceMode)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && activePassword.isNotBlank()) {
                    requestSoftApNetwork(activeSsid, activePassword)
                }
            }
        }
        reconnectRunnable = r
        mainHandler.postDelayed(r, SOFTAP_RECONNECT_DELAY_MS)
    }

    private fun onClusterWifiReady(
        network: Network,
        gatewayHost: String,
        ssid: String,
        source: TftMirrorSource
    ) {
        activeNetwork = network
        framesSentCounter.set(0L)
        fpsWindowStartMs = SystemClock.elapsedRealtime()
        fpsWindowFrames = 0

        _mirrorState.value = _mirrorState.value.copy(
            status = TftMirrorStatus.WIFI_CONNECTED_WAITING_TCP,
            source = source,
            ssid = ssid,
            clusterHost = gatewayHost,
            errorMessage = null
        )

        startTcpWorker(network, gatewayHost)
        startUdpWorker(network, gatewayHost)
    }

    private fun startTcpWorker(network: Network, host: String) {
        tcpSessionJob?.cancel()
        tcpSessionJob = scope.launch {
            while (isActive && isActiveSession.get()) {
                var sock: Socket? = null
                try {
                    sock = Socket()
                    network.bindSocket(sock)
                    sock.soTimeout = 3000
                    sock.tcpNoDelay = true
                    sock.connect(InetSocketAddress(host, TftWifiProtocol.TCP_PORT), 5000)
                    tcpSocket = sock
                    tcpOutputStream = sock.getOutputStream()

                    Log.i(TAG, "Connected TCP control channel to $host:${TftWifiProtocol.TCP_PORT}")
                    clusterStreamingEnabled.set(true)
                    displayController.refreshFrameNow()
                    synchronized(udpWakeLock) {
                        udpWakeLock.notifyAll()
                    }
                    _mirrorState.value = _mirrorState.value.copy(
                        status = TftMirrorStatus.STREAMING,
                        errorMessage = null
                    )

                    val input = sock.getInputStream()
                    val accum = ByteArray(8192)
                    var accumLen = 0
                    val readTmp = ByteArray(2048)

                    while (isActive && isActiveSession.get() && !sock.isClosed) {
                        val readCount = try {
                            input.read(readTmp)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        if (readCount < 0) break
                        if (accumLen + readCount > accum.size) {
                            accumLen = 0
                        }
                        System.arraycopy(readTmp, 0, accum, accumLen, readCount)
                        accumLen += readCount

                        var keepDecoding = true
                        while (keepDecoding && accumLen > 0) {
                            val res = TftTcpCodec.decodeNextFrame(accum, accumLen)
                            if (res.consumedBytes > 0) {
                                val remaining = accumLen - res.consumedBytes
                                if (remaining > 0) {
                                    System.arraycopy(accum, res.consumedBytes, accum, 0, remaining)
                                }
                                accumLen = remaining
                            }
                            if (res.frame != null) {
                                handleClusterFrame(res.frame)
                            }
                            if (res.needMoreData || res.consumedBytes == 0) {
                                keepDecoding = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActiveSession.get()) {
                        Log.w(TAG, "TCP session retry ($host): ${e.message}")
                        _mirrorState.value = _mirrorState.value.copy(
                            status = TftMirrorStatus.WIFI_CONNECTED_WAITING_TCP,
                            errorMessage = "Waiting for cluster mirror service ($host:5558)..."
                        )
                    }
                } finally {
                    synchronized(tcpWriteLock) {
                        tcpOutputStream = null
                    }
                    try {
                        sock?.close()
                    } catch (_: Exception) {}
                    tcpSocket = null
                }

                if (isActive && isActiveSession.get()) {
                    delay(1200L)
                }
            }
        }
    }

    private fun startUdpWorker(network: Network, host: String) {
        udpLoopFuture?.cancel(true)
        udpLoopFuture = udpDispatcherExecutor.submit {
            var dSock: DatagramSocket? = null
            try {
                val sock = try {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(TftWifiProtocol.UDP_PORT))
                    }
                } catch (_: Exception) {
                    DatagramSocket()
                }
                dSock = sock
                network.bindSocket(sock)
                udpSocket = sock

                val targetAddr = InetAddress.getByName(host)
                val cipher = TftUdpFrameEncoder.createEncryptCipher()
                val reusablePacket = DatagramPacket(ByteArray(0), 0, targetAddr, TftWifiProtocol.UDP_PORT)

                while (isActiveSession.get() && !Thread.currentThread().isInterrupted) {
                    val jpeg = pendingJpegRef.getAndSet(null)
                    if (jpeg == null || !clusterStreamingEnabled.get()) {
                        synchronized(udpWakeLock) {
                            if (pendingJpegRef.get() == null && isActiveSession.get()) {
                                udpWakeLock.wait(200L)
                            }
                        }
                        continue
                    }

                    val chunks = TftUdpFrameEncoder.encodeJpegFrameChunks(jpeg, cipher)
                    if (chunks.isEmpty()) continue

                    for (i in chunks.indices) {
                        if (!isActiveSession.get()) break
                        val packetBytes = chunks[i]
                        reusablePacket.setData(packetBytes, 0, packetBytes.size)
                        sock.send(reusablePacket)
                        if ((i + 1) % TftWifiProtocol.UDP_TX_BURST_PACKETS == 0 && i < chunks.lastIndex) {
                            Thread.sleep(TftWifiProtocol.UDP_TX_PACING_MS)
                        }
                    }

                    recordSentFrameStats()
                }
            } catch (_: InterruptedException) {
                // Expected on shutdown
            } catch (e: Exception) {
                if (isActiveSession.get()) {
                    Log.w(TAG, "UDP stream error: ${e.message}")
                    com.pulsarnav.app.debug.DebugLogRepository.error("UDP_5556", "UDP stream error: ${e.message}")
                }
            } finally {
                try {
                    dSock?.close()
                } catch (_: Exception) {}
                udpSocket = null
            }
        }
    }

    private val isLocalPreviewActive = AtomicBoolean(false)
    private val _isLocalPreviewActiveFlow = MutableStateFlow(false)
    val isLocalPreviewActiveFlow: StateFlow<Boolean> = _isLocalPreviewActiveFlow

    private val debugFrameSeq = java.util.concurrent.atomic.AtomicLong(0L)
    private val _latestDebugFrame = MutableStateFlow<TftDebugFrame?>(null)
    val latestDebugFrame: StateFlow<TftDebugFrame?> = _latestDebugFrame

    private fun onDisplayFrameEncoded(jpeg: ByteArray, quality: Int, bytes: Int) {
        val liveSession = isActiveSession.get()
        if (liveSession && isLocalPreviewActive.compareAndSet(true, false)) {
            _isLocalPreviewActiveFlow.value = false
        }
        val localPreview = isLocalPreviewActive.get()
        if (!liveSession && !localPreview) return

        latestEncodedQuality = quality
        latestEncodedBytes = bytes

        if (localPreview || _latestDebugFrame.subscriptionCount.value > 0) {
            _latestDebugFrame.value = TftDebugFrame(
                jpegBytes = jpeg,
                width = TftWifiProtocol.TFT_WIDTH,
                height = TftWifiProtocol.TFT_HEIGHT,
                quality = quality,
                byteSize = bytes,
                frameIndex = debugFrameSeq.incrementAndGet(),
                timestampMs = System.currentTimeMillis(),
                isLiveWifi = liveSession
            )
        }

        if (liveSession) {
            pendingJpegRef.set(jpeg)
            synchronized(udpWakeLock) {
                udpWakeLock.notifyAll()
            }
        }
    }

    private fun recordSentFrameStats() {
        val now = SystemClock.elapsedRealtime()
        fpsWindowFrames++
        val elapsed = now - fpsWindowStartMs
        val current = _mirrorState.value
        val newTotal = framesSentCounter.incrementAndGet()
        if (elapsed >= 1000L || newTotal == 1L || current.status != TftMirrorStatus.STREAMING) {
            val fps = if (elapsed > 0L) (fpsWindowFrames * 1000f) / elapsed.toFloat() else 0f
            val q = latestEncodedQuality
            val b = latestEncodedBytes
            fpsWindowStartMs = now
            fpsWindowFrames = 0
            _mirrorState.value = current.copy(
                status = TftMirrorStatus.STREAMING,
                fps = fps,
                framesSent = newTotal,
                jpegQuality = q,
                lastFrameBytes = b
            )
            if (elapsed >= 1000L) {
                com.pulsarnav.app.debug.DebugLogRepository.logRateLimited(
                    key = "udp_stream_stats",
                    minIntervalMs = 5_000L,
                    category = com.pulsarnav.app.debug.DebugCategory.WIFI,
                    tag = "UDP_5556",
                    summary = String.format(
                        java.util.Locale.US,
                        "Streaming %.1f fps • %dB / %dB • Q=%d",
                        fps,
                        b,
                        TftWifiProtocol.UDP_MAX_JPEG_BYTES,
                        q
                    ),
                    detail = "totalFrames=$newTotal"
                )
            }
        }
    }

    private fun handleClusterFrame(frame: TftTcpFrame) {
        val frameDesc = com.pulsarnav.app.debug.DebugLogRepository.summarizeTcpFrame("RX", frame)
        if (frame.group == TftWifiProtocol.GROUP_SCREEN && frame.cmd == TftWifiProtocol.CMD_BIKE_INFO) {
            com.pulsarnav.app.debug.DebugLogRepository.logRateLimited(
                key = "tcp_bike_info_${frame.payload.firstOrNull() ?: -1}",
                minIntervalMs = 5_000L,
                category = com.pulsarnav.app.debug.DebugCategory.WIFI,
                tag = "TCP_5558",
                summary = frameDesc
            )
        } else if (!(frame.group == TftWifiProtocol.GROUP_DATA && frame.cmd >= TftWifiProtocol.CMD_NAV_V2_GET_POI_LIST)) {
            com.pulsarnav.app.debug.DebugLogRepository.wifi("TCP_5558", frameDesc)
        }

        when (val req = TftTcpCodec.parseClusterRequest(frame)) {
            is ClusterTcpRequest.ScreenInfoRequest -> {
                val navActive = GoogleMapsNotificationListener.currentNavStep.value != null ||
                    mapEngine.uiState.value.routeActive
                val mode = if (navActive || _mirrorState.value.source == TftMirrorSource.LIVE_SCREEN_CAST) {
                    TftWifiProtocol.SCREEN_MODE_TBT_NAV
                } else {
                    TftWifiProtocol.SCREEN_MODE_ROUTE_MAP
                }
                sendTcpFrame(TftTcpCodec.buildScreenInfoResponse(mode))
                displayController.refreshFrameNow()
                synchronized(udpWakeLock) {
                    udpWakeLock.notifyAll()
                }
            }
            is ClusterTcpRequest.ScreenControl -> {
                sendTcpFrame(TftTcpCodec.buildScreenControlResponse())
                displayController.refreshFrameNow()
            }
            is ClusterTcpRequest.DateTimeRequest -> {
                sendTcpFrame(TftTcpCodec.buildDateTimeResponse(Calendar.getInstance()))
            }
            is ClusterTcpRequest.PhoneNameRequest -> {
                val label = Build.MODEL?.takeIf { it.isNotBlank() } ?: "PulsarNav"
                sendTcpFrame(TftTcpCodec.buildPhoneNameResponse(label))
            }
            is ClusterTcpRequest.BikeInfo -> {
                displayController.updateBikeStationary(req.isStationary)
                _mirrorState.value = _mirrorState.value.copy(isBikeStationary = req.isStationary)
            }
            is ClusterTcpRequest.MirroringStateChanged -> {
                clusterStreamingEnabled.set(req.active)
                _mirrorState.value = _mirrorState.value.copy(
                    status = if (req.active) TftMirrorStatus.STREAMING else TftMirrorStatus.TCP_CONNECTED_IDLE
                )
                if (req.active) {
                    displayController.refreshFrameNow()
                    synchronized(udpWakeLock) {
                        udpWakeLock.notifyAll()
                    }
                }
            }
            is ClusterTcpRequest.Touch -> {
                displayController.onClusterTouch(req.touchType, req.x, req.y)
                _mirrorState.value = _mirrorState.value.copy(
                    lastTouchInfo = "Touch(${req.touchType}) @ ${req.x},${req.y}"
                )
            }
            is ClusterTcpRequest.HandlebarInput -> {
                if (req.recenterRequested) {
                    displayController.onHandlebarRecenter()
                    _mirrorState.value = _mirrorState.value.copy(lastTouchInfo = "Handlebar Recenter")
                }
            }
            is ClusterTcpRequest.DocSizeRequest -> {
                val doc = resolveDocumentBytes(req.docNum)
                sendTcpFrame(TftTcpCodec.buildDocSizeResponse(req.docNum, doc))
            }
            is ClusterTcpRequest.DocMd5Request -> {
                val doc = resolveDocumentBytes(req.docNum)
                sendTcpFrame(TftTcpCodec.buildDocMd5Response(req.docNum, doc))
            }
            is ClusterTcpRequest.DocDataRequest -> {
                val doc = resolveDocumentBytes(req.docNum)
                sendTcpFrame(TftTcpCodec.buildDocDataResponse(req.docNum, doc))
            }
            is ClusterTcpRequest.StopNavigation -> {
                Log.i(TAG, "Cluster requested navigation stop / pause")
                mapEngine.clearActiveRoute()
                BajajBleManager.getInstance(context).stopNavigation()
                _mirrorState.value = _mirrorState.value.copy(lastTouchInfo = "Cluster Stop Nav")
            }
            is ClusterTcpRequest.NavV2Command -> {
                navV2Controller.handleCommand(req.cmd, req.payload) { replyFrame ->
                    sendTcpFrame(replyFrame)
                }
            }
            is ClusterTcpRequest.Unknown -> {
                Log.d(TAG, "Unhandled cluster TCP frame group=${req.group} cmd=${req.cmd} len=${req.payload.size}")
            }
        }
    }

    private fun resolveDocumentBytes(docNum: Int): ByteArray {
        return when (docNum) {
            TftWifiProtocol.DOC_MUSIC_COVER -> {
                val raw = MediaControlManager.getInstance(context).getActiveAlbumArtJpeg() ?: return ByteArray(0)
                if (raw.size <= MAX_DOC_PREVIEW_BYTES) raw else raw.copyOf(MAX_DOC_PREVIEW_BYTES)
            }
            TftWifiProtocol.DOC_RC -> readDocumentPreviewBytes(TftWifiProtocol.DOC_RC, appSettings.docRcPath)
            TftWifiProtocol.DOC_INSURANCE -> readDocumentPreviewBytes(TftWifiProtocol.DOC_INSURANCE, appSettings.docInsurancePath)
            else -> ByteArray(0)
        }
    }

    private fun readDocumentPreviewBytes(docNum: Int, path: String): ByteArray {
        if (path.isBlank()) {
            docPreviewCache.remove(docNum)
            return ByteArray(0)
        }
        return try {
            val file = File(path)
            if (!file.exists() || !file.isFile) {
                docPreviewCache.remove(docNum)
                return ByteArray(0)
            }
            val cacheKey = "$path:${file.length()}:${file.lastModified()}"
            docPreviewCache[docNum]?.let { cached ->
                if (cached.cacheKey == cacheKey) return cached.bytes
            }

            val ext = file.extension.lowercase()
            val rawBitmap: Bitmap? = when (ext) {
                "jpg", "jpeg", "png", "webp" -> decodeDownsampledBitmap(file)
                "pdf" -> renderPdfFirstPage(file)
                else -> null
            }
            if (rawBitmap == null) return ByteArray(0)

            val scaled = Bitmap.createScaledBitmap(rawBitmap, DOC_PREVIEW_WIDTH, DOC_PREVIEW_HEIGHT, true)
            if (scaled !== rawBitmap) rawBitmap.recycle()

            val encoded = compressUnderLimit(scaled, MAX_DOC_PREVIEW_BYTES)
            scaled.recycle()
            docPreviewCache[docNum] = CachedDocPreview(cacheKey, encoded)
            encoded
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    private fun decodeDownsampledBitmap(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        var sampleSize = 1
        while (w / (sampleSize * 2) >= DOC_PREVIEW_WIDTH && h / (sampleSize * 2) >= DOC_PREVIEW_HEIGHT) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
    }

    private fun renderPdfFirstPage(file: File): Bitmap? {
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount <= 0) return null
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(DOC_PREVIEW_WIDTH, DOC_PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }

    private fun compressUnderLimit(bitmap: Bitmap, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (q in intArrayOf(70, 55, 40, 28, 18)) {
            out.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() <= maxBytes) break
        }
        val bytes = out.toByteArray()
        return if (bytes.size <= maxBytes) bytes else bytes.copyOf(maxBytes)
    }

    private fun sendTcpFrame(bytes: ByteArray) {
        synchronized(tcpWriteLock) {
            try {
                val out = tcpOutputStream ?: return
                out.write(bytes)
                out.flush()
                if (bytes.size >= TftWifiProtocol.TCP_HEADER_SIZE) {
                    val g = bytes[1].toInt() and 0xFF
                    val c = bytes[2].toInt() and 0xFF
                    val label = com.pulsarnav.app.debug.DebugLogRepository.tcpCmdLabel(g, c)
                    com.pulsarnav.app.debug.DebugLogRepository.wifi("TCP_5558", "TX $label (${bytes.size}B)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed TCP write (${bytes.size}B): ${e.message}")
                com.pulsarnav.app.debug.DebugLogRepository.error("TCP_5558", "TCP write failed (${bytes.size}B): ${e.message}")
            }
        }
    }

    /**
     * Starts an offline 800x480 TFT virtual display + adaptive JPEG encoder session
     * for on-phone visual debugging without needing motorcycle Wi-Fi SoftAP.
     */
    fun startLocalDebugPreview() {
        if (isActiveSession.get()) {
            com.pulsarnav.app.debug.DebugLogRepository.wifi(
                "TFT_DBG",
                "Live TFT Wi-Fi session already streaming; reusing live frame feed"
            )
            return
        }
        if (isLocalPreviewActive.compareAndSet(false, true)) {
            _isLocalPreviewActiveFlow.value = true
            val src = if (appSettings.tftMirrorMode == AppSettings.TFT_MODE_HUD) {
                TftMirrorSource.COCKPIT_HUD
            } else {
                mapEngine.startLocationTracking()
                TftMirrorSource.LIVE_MAP
            }
            displayController.startHeadless(src)
            com.pulsarnav.app.debug.DebugLogRepository.wifi(
                "TFT_DBG",
                "Started offline 800x480 TFT bench preview (${src.name})"
            )
        } else {
            displayController.refreshFrameNow()
        }
    }

    fun stopLocalDebugPreview() {
        val wasActive = isLocalPreviewActive.getAndSet(false)
        _isLocalPreviewActiveFlow.value = false
        if (wasActive && !isActiveSession.get()) {
            displayController.stop()
            if (!mapEngine.uiState.value.routeActive && !mapEngine.hasPendingRoute()) {
                mapEngine.stopLocationTracking()
            }
            com.pulsarnav.app.debug.DebugLogRepository.wifi(
                "TFT_DBG",
                "Stopped offline 800x480 TFT Cockpit bench preview"
            )
        }
    }

    fun simulateClusterTouch(touchType: Int = 0, x: Int, y: Int) {
        val cx = x.coerceIn(0, TftWifiProtocol.TFT_WIDTH - 1)
        val cy = y.coerceIn(0, TftWifiProtocol.TFT_HEIGHT - 1)
        displayController.onClusterTouch(touchType, cx, cy)
        _mirrorState.value = _mirrorState.value.copy(
            lastTouchInfo = "DbgTouch($touchType) @ $cx,$cy"
        )
        com.pulsarnav.app.debug.DebugLogRepository.wifi(
            "TFT_INPUT",
            "Simulated TFT touch($touchType) @ ($cx, $cy)"
        )
    }

    fun simulateHandlebarRecenter() {
        displayController.onHandlebarRecenter()
        _mirrorState.value = _mirrorState.value.copy(lastTouchInfo = "Handlebar Recenter (Dbg)")
        com.pulsarnav.app.debug.DebugLogRepository.wifi(
            "TFT_INPUT",
            "Triggered Handlebar Recenter banner on TFT"
        )
    }

    fun toggleDebugDaylightContrast(): Boolean {
        val next = displayController.toggleDaylightHighContrast()
        com.pulsarnav.app.debug.DebugLogRepository.wifi(
            "TFT_DBG",
            "TFT theme toggled -> ${if (next) "DAYLIGHT HIGH-CONTRAST" else "NIGHT COCKPIT"}"
        )
        return next
    }

    fun toggleDebugHitboxOverlay(): Boolean {
        val next = !displayController.isDebugOverlayEnabled()
        displayController.setDebugOverlayEnabled(next)
        com.pulsarnav.app.debug.DebugLogRepository.wifi(
            "TFT_DBG",
            "TFT debug hitbox & crosshair overlay -> ${if (next) "ON" else "OFF"}"
        )
        return next
    }

    fun toggleDebugStationaryMode(): Boolean {
        val next = !displayController.isBikeStationaryMode()
        displayController.updateBikeStationary(next)
        _mirrorState.value = _mirrorState.value.copy(isBikeStationary = next)
        com.pulsarnav.app.debug.DebugLogRepository.wifi(
            "TFT_DBG",
            "TFT bike motion state -> ${if (next) "PARK (Stationary)" else "RIDE (Moving)"}"
        )
        return next
    }

    fun refreshDebugCockpitFrame() {
        if (isLocalPreviewActive.get() || isActiveSession.get()) {
            displayController.refreshFrameNow()
        }
    }

    /**
     * Runs an offline loopback diagnostic across TCP 5558 NAV_V2 handlers (0x0E Route Pref,
     * 0x09 Favourites, 0x0A Recent Trips, 0x07 Nearby POIs) without opening any socket.
     */
    fun runNavV2LoopbackDiagnostics() {
        val cmds = listOf(
            TftWifiProtocol.CMD_NAV_V2_GET_ROUTE_PREF to ByteArray(0),
            TftWifiProtocol.CMD_NAV_V2_GET_FAVOURITES to ByteArray(0),
            TftWifiProtocol.CMD_NAV_V2_GET_RECENT_TRIPS to ByteArray(0),
            TftWifiProtocol.CMD_NAV_V2_GET_POI_LIST to byteArrayOf(0x00)
        )
        com.pulsarnav.app.debug.DebugLogRepository.navV2(
            "NAV_V2_TEST",
            "Running offline NAV_V2 loopback suite (${cmds.size} TCP 5558 cmds)"
        )
        for ((cmd, payload) in cmds) {
            navV2Controller.handleCommand(cmd, payload) { frameBytes ->
                val decoded = TftTcpCodec.decodeNextFrame(
                    buffer = frameBytes,
                    length = frameBytes.size,
                    expectedFrameType = TftWifiProtocol.TYPE_PHONE_RESPONSE,
                    maxPayloadBytes = 16_384
                )
                val cmdName = com.pulsarnav.app.debug.DebugLogRepository.tcpCmdLabel(
                    TftWifiProtocol.GROUP_DATA,
                    cmd
                )
                if (decoded.frame != null) {
                    com.pulsarnav.app.debug.DebugLogRepository.navV2(
                        "NAV_V2_LOOPBACK",
                        "$cmdName valid frame (${frameBytes.size}B, payload=${decoded.frame.payload.size}B)"
                    )
                } else {
                    com.pulsarnav.app.debug.DebugLogRepository.error(
                        "NAV_V2_LOOPBACK",
                        "$cmdName produced malformed frame (${frameBytes.size}B)"
                    )
                }
            }
        }
    }

    private fun resolveTargetSsid(bleDeviceName: String): String {
        val custom = appSettings.tftWifiSsid.trim()
        if (custom.isNotEmpty()) return custom
        return TftPasswordGenerator.deriveDefaultSsid(bleDeviceName, appSettings.tftWifiVin)
    }

    private fun resolveTargetPassword(): String {
        val manual = appSettings.tftWifiPassphrase.trim()
        if (manual.isNotEmpty()) return manual
        val vin = appSettings.tftWifiVin.trim()
        if (vin.isNotEmpty()) return TftPasswordGenerator.deriveSoftApPassword(vin)
        return ""
    }

    @Suppress("DEPRECATION")
    private fun findAlreadyConnectedClusterWifi(): Pair<Network, String>? {
        val allNets = try {
            connectivityManager.allNetworks
        } catch (_: Exception) {
            emptyArray()
        }
        for (net in allNets) {
            val caps = connectivityManager.getNetworkCapabilities(net) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val host = resolveGatewayHostOrNull(net) ?: continue
            // Match typical cluster SoftAP subnet (192.168.4.x) or Wi-Fi without Internet
            if (host == TftWifiProtocol.DEFAULT_CLUSTER_HOST ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return net to host
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun resolveGatewayHost(network: Network): String {
        return resolveGatewayHostOrNull(network) ?: run {
            try {
                val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val gwInt = wifiMgr?.dhcpInfo?.gateway ?: 0
                if (gwInt != 0) {
                    val ip = String.format(
                        java.util.Locale.US,
                        "%d.%d.%d.%d",
                        gwInt and 0xFF,
                        (gwInt shr 8) and 0xFF,
                        (gwInt shr 16) and 0xFF,
                        (gwInt shr 24) and 0xFF
                    )
                    if (ip != "0.0.0.0") return ip
                }
            } catch (_: Exception) {}
            TftWifiProtocol.DEFAULT_CLUSTER_HOST
        }
    }

    private fun resolveGatewayHostOrNull(network: Network): String? {
        val lp = connectivityManager.getLinkProperties(network) ?: return null
        for (route in lp.routes) {
            val gw = route.gateway ?: continue
            if (gw is Inet4Address && !gw.isAnyLocalAddress && !gw.isLoopbackAddress) {
                val hostAddr = gw.hostAddress ?: continue
                if (!hostAddr.startsWith("fe80:") && hostAddr != "0.0.0.0") {
                    return hostAddr
                }
            }
        }
        return null
    }

    private fun unregisterWifiCallback() {
        val cb = registeredNetworkCallback ?: return
        registeredNetworkCallback = null
        try {
            connectivityManager.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
    }

    private fun closeSockets() {
        synchronized(tcpWriteLock) {
            tcpOutputStream = null
        }
        try {
            tcpSocket?.close()
        } catch (_: Exception) {}
        tcpSocket = null
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null
    }
}
