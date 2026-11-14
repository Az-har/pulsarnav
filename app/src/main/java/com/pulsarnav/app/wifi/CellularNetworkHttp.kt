package com.pulsarnav.app.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared HTTP helper that routes requests over Cellular mobile data even when the phone is
 * connected to the bike's internetless SoftAP Wi-Fi network (192.168.x.x).
 */
object CellularNetworkHttp {

    const val USER_AGENT = "PulsarNav-Android/1.2.4 (https://github.com/Az-har/pulsarnav)"

    @Volatile
    private var cachedCellularNetwork: Network? = null

    private val pinLock = Any()
    private var persistentCellCallback: ConnectivityManager.NetworkCallback? = null
    private var persistentCellCm: ConnectivityManager? = null

    fun hasInternetPath(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return findExistingCellularInternet(cm) != null || hasUsableDefaultInternet(cm)
    }

    fun getText(
        context: Context,
        url: String,
        connectTimeoutMs: Int = 4500,
        readTimeoutMs: Int = 5500,
        headers: Map<String, String> = emptyMap()
    ): String? {
        return withHttpConnection(context, url, "GET", connectTimeoutMs, readTimeoutMs, headers) { conn ->
            if (conn.responseCode !in 200..299) return@withHttpConnection null
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }
    }

    fun getBytes(
        context: Context,
        url: String,
        connectTimeoutMs: Int = 3500,
        readTimeoutMs: Int = 4500,
        maxBytes: Int = 1024 * 1024,
        headers: Map<String, String> = emptyMap()
    ): ByteArray? {
        return withHttpConnection(context, url, "GET", connectTimeoutMs, readTimeoutMs, headers) { conn ->
            if (conn.responseCode !in 200..299) return@withHttpConnection null
            readBounded(conn.inputStream, maxBytes)
        }
    }

    fun postFormText(
        context: Context,
        url: String,
        encodedBody: String,
        connectTimeoutMs: Int = 3500,
        readTimeoutMs: Int = 4500
    ): String? {
        return withHttpConnection(
            context = context,
            urlString = url,
            method = "POST",
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
            prepare = { conn ->
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(encodedBody) }
            }
        ) { conn ->
            if (conn.responseCode !in 200..299) return@withHttpConnection null
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }
    }

    /**
     * Resolves shortened URLs (e.g., `maps.app.goo.gl/...`) while keeping every redirect hop
     * bound to the active cellular [Network] when available. Also extracts canonical / `og:url`
     * links from HTML meta tags if the redirect lands on a Firebase Dynamic Link or Maps preview page.
     */
    fun resolveFinalUrl(context: Context, initialUrl: String, maxHops: Int = 6): String {
        var current = initialUrl
        val appCtx = context.applicationContext
        return withPreferredNetwork(appCtx) { network ->
            if (network == null && !hasUsableDefaultInternet(
                    appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                )
            ) {
                return@withPreferredNetwork current
            }
            for (hop in 0 until maxHops) {
                var conn: HttpURLConnection? = null
                try {
                    val u = URL(current)
                    conn = (network?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4500
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val location = conn.getHeaderField("Location")?.trim() ?: break
                        val (nextTarget, continueHttp) = resolveRedirectTarget(current, location)
                        current = nextTarget
                        if (!continueHttp) break
                        continue
                    }
                    if (code in 200..299) {
                        val ct = conn.contentType.orEmpty()
                        if (ct.contains("text/html", ignoreCase = true)) {
                            val headBytes = readBounded(conn.inputStream, 32 * 1024)
                            val html = String(headBytes, StandardCharsets.UTF_8)
                            val metaUrl = extractMetaUrlFromHtml(html)
                            if (!metaUrl.isNullOrBlank() && metaUrl != current) {
                                current = metaUrl
                            }
                        }
                    }
                    break
                } catch (_: Exception) {
                    if (network != null && cachedCellularNetwork == network) {
                        cachedCellularNetwork = null
                    }
                    break
                } finally {
                    try {
                        conn?.disconnect()
                    } catch (_: Exception) {
                    }
                }
            }
            current
        }
    }

    internal fun extractMetaUrlFromHtml(html: String): String? {
        val ogRegex = Regex("""<meta[^>]+property=["']og:url["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        ogRegex.find(html)?.groupValues?.getOrNull(1)?.let { return it }
        val canonicalRegex = Regex("""<link[^>]+rel=["']canonical["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        canonicalRegex.find(html)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    internal fun extractIntentFallbackOrGeoUri(intentUri: String): String? {
        if (!intentUri.startsWith("intent://", ignoreCase = true)) return null
        val fragmentIndex = intentUri.indexOf("#Intent", ignoreCase = true)
        val authorityAndPath = if (fragmentIndex >= 0) {
            intentUri.substring("intent://".length, fragmentIndex)
        } else {
            intentUri.removePrefix("intent://")
        }
        val intentMeta = if (fragmentIndex >= 0) intentUri.substring(fragmentIndex + "#Intent".length) else ""

        val fallbackMatch = Regex("""(?:^|;)S\.browser_fallback_url=([^;]+)""").find(intentMeta)
        val rawFallback = fallbackMatch?.groupValues?.getOrNull(1)
        if (!rawFallback.isNullOrBlank()) {
            val decoded = try {
                URLDecoder.decode(rawFallback, StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
                rawFallback
            }.trim()
            if (decoded.isNotEmpty()) return decoded
        }

        val schemeMatch = Regex("""(?:^|;)scheme=([a-zA-Z0-9+.-]+)""").find(intentMeta)
        val scheme = schemeMatch?.groupValues?.getOrNull(1)?.lowercase()
        if (!scheme.isNullOrBlank() && authorityAndPath.isNotBlank()) {
            return "$scheme://$authorityAndPath"
        }
        if (authorityAndPath.isNotBlank() && authorityAndPath.contains('.')) {
            return "https://$authorityAndPath"
        }
        return null
    }

    internal fun resolveRedirectTarget(baseUrl: String, location: String): Pair<String, Boolean> {
        val trimmed = location.trim()
        if (trimmed.startsWith("geo:", ignoreCase = true) ||
            trimmed.startsWith("google.navigation:", ignoreCase = true)
        ) {
            return trimmed to false
        }
        if (trimmed.startsWith("intent://", ignoreCase = true)) {
            val extracted = extractIntentFallbackOrGeoUri(trimmed)
            if (!extracted.isNullOrBlank()) {
                val isHttp = extracted.startsWith("http://", ignoreCase = true) ||
                    extracted.startsWith("https://", ignoreCase = true)
                return extracted to isHttp
            }
            return trimmed to false
        }
        val resolved = URL(URL(baseUrl), trimmed).toString()
        val isHttp = resolved.startsWith("http://", ignoreCase = true) ||
            resolved.startsWith("https://", ignoreCase = true)
        return resolved to isHttp
    }

    private fun <T> withHttpConnection(
        context: Context,
        urlString: String,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        headers: Map<String, String>,
        prepare: ((HttpURLConnection) -> Unit)? = null,
        block: (HttpURLConnection) -> T?
    ): T? {
        val appCtx = context.applicationContext
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return withPreferredNetwork(appCtx) { network ->
            if (network == null && !hasUsableDefaultInternet(cm)) {
                return@withPreferredNetwork null
            }
            var conn: HttpURLConnection? = null
            try {
                val url = URL(urlString)
                conn = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.setRequestProperty("User-Agent", USER_AGENT)
                for ((k, v) in headers) {
                    conn.setRequestProperty(k, v)
                }
                prepare?.invoke(conn)
                block(conn)
            } catch (_: Exception) {
                if (network != null && cachedCellularNetwork == network) {
                    cachedCellularNetwork = null
                }
                null
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun releaseCellularPin() {
        synchronized(pinLock) {
            val cb = persistentCellCallback
            val cm = persistentCellCm
            persistentCellCallback = null
            persistentCellCm = null
            cachedCellularNetwork = null
            if (cb != null && cm != null) {
                try {
                    cm.unregisterNetworkCallback(cb)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun <T> withPreferredNetwork(context: Context, block: (Network?) -> T): T {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return block(null)

        // Fast path: if default network already has validated internet (normal home Wi-Fi or cellular),
        // use it directly unless we are currently on an unvalidated/internetless Wi-Fi SoftAP.
        if (hasUsableDefaultInternet(cm) && !isDefaultInternetlessWifi(cm)) {
            releaseCellularPin()
            return block(null)
        }
        val existingCell = findExistingCellularInternet(cm)
        if (existingCell != null) {
            cachedCellularNetwork = existingCell
            return block(existingCell)
        }

        // Slow path: SoftAP is active while cellular hasn't been pinned yet; keep a single shared
        // cellular request alive instead of churning the PDN up and down on every tile/route call.
        var temporaryCallback: ConnectivityManager.NetworkCallback? = null
        val requestedNet = AtomicReference<Network?>(null)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CHANGE_NETWORK_STATE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val latch = CountDownLatch(1)
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .build()
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        requestedNet.compareAndSet(null, network)
                        cachedCellularNetwork = network
                        latch.countDown()
                    }

                    override fun onLost(network: Network) {
                        synchronized(pinLock) {
                            if (cachedCellularNetwork == network) {
                                cachedCellularNetwork = null
                            }
                            if (persistentCellCallback === this) {
                                persistentCellCallback = null
                                persistentCellCm = null
                            }
                        }
                    }

                    override fun onUnavailable() {
                        synchronized(pinLock) {
                            if (persistentCellCallback === this) {
                                persistentCellCallback = null
                                persistentCellCm = null
                            }
                        }
                        latch.countDown()
                    }
                }
                synchronized(pinLock) {
                    if (persistentCellCallback == null) {
                        cm.requestNetwork(req, cb, 2500)
                        persistentCellCallback = cb
                        persistentCellCm = cm
                    } else {
                        temporaryCallback = cb
                        cm.requestNetwork(req, cb, 2000)
                    }
                }
                latch.await(2200L, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
            }
        }
        return try {
            block(requestedNet.get() ?: cachedCellularNetwork ?: findExistingCellularInternet(cm))
        } finally {
            temporaryCallback?.let { cb ->
                try {
                    cm.unregisterNetworkCallback(cb)
                } catch (_: Exception) {
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun findExistingCellularInternet(cm: ConnectivityManager): Network? {
        val cached = cachedCellularNetwork
        if (cached != null) {
            val caps = cm.getNetworkCapabilities(cached)
            if (caps != null &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            ) {
                return cached
            }
            cachedCellularNetwork = null
        }
        val all = try {
            cm.allNetworks
        } catch (_: Exception) {
            emptyArray()
        }
        var fallbackCandidate: Network? = null
        for (net in all) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            ) {
                continue
            }
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return net
            }
            if (fallbackCandidate == null) {
                fallbackCandidate = net
            }
        }
        return fallbackCandidate
    }

    private fun isDefaultInternetlessWifi(cm: ConnectivityManager): Boolean {
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun hasUsableDefaultInternet(cm: ConnectivityManager?): Boolean {
        if (cm == null) return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun readBounded(stream: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        stream.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                val allow = minOf(n, maxBytes - total)
                if (allow > 0) {
                    out.write(buf, 0, allow)
                    total += allow
                }
                if (total >= maxBytes) break
            }
        }
        return out.toByteArray()
    }
}
