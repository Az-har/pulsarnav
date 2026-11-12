package com.pulsarnav.app.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.pulsarnav.app.data.AppSettings
import com.pulsarnav.app.service.GoogleMapsNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MediaControlManager private constructor(private val context: Context) {

    data class MediaMetadataSnapshot(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val elapsedSeconds: Int = 0,
        val durationSeconds: Int = 0,
        val playStatus: Int = 0
    )

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _lastMediaEvent = MutableStateFlow<String?>(null)
    val lastMediaEvent: StateFlow<String?> = _lastMediaEvent.asStateFlow()

    companion object {
        private const val TAG = "PulsarMedia"

        @Volatile
        private var instance: MediaControlManager? = null

        fun getInstance(context: Context): MediaControlManager {
            return instance ?: synchronized(this) {
                instance ?: MediaControlManager(context.applicationContext).also { instance = it }
            }
        }
    }

    data class ClusterPlaylistPage(
        val trackTitles: List<String>,
        val totalSongs: Int,
        val totalPages: Int,
        val currentTrackNumber: Int,
        val pagePlaylistAck: Int,
        val newPlaylistAck: Int,
        val userChangePlaylistCounter: Int
    )

    private var lastShortPressTimeMs = 0L
    private var pendingPlayPauseJob: kotlinx.coroutines.Job? = null
    private var clearBannerJob: kotlinx.coroutines.Job? = null
    private val recentTrackTitles = ArrayDeque<String>()
    private var playlistChangeCounter = 0
    private var lastSelectTrackTimeMs = 0L

    @Synchronized
    fun handleHandlebarButton(isLongPress: Boolean): Boolean {
        if (!AppSettings.getInstance(context).isMediaControlEnabled) {
            return false
        }

        val now = SystemClock.uptimeMillis()

        if (isLongPress) {
            // Check if this long press was preceded by a short press within 1000ms ("Press then Long Press")
            val elapsedSinceShort = now - lastShortPressTimeMs
            if (lastShortPressTimeMs > 0L && elapsedSinceShort in 0..1000) {
                // Cancel pending Play/Pause
                pendingPlayPauseJob?.cancel()
                pendingPlayPauseJob = null
                lastShortPressTimeMs = 0L

                // Trigger Previous Song
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                _lastMediaEvent.value = "⏮ Previous Track"
                clearBannerAfterDelay()
                return true
            }

            // Normal Long Press -> Next Track
            pendingPlayPauseJob?.cancel()
            pendingPlayPauseJob = null
            lastShortPressTimeMs = 0L
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            _lastMediaEvent.value = "⏭ Next Track"
            clearBannerAfterDelay()
            return true
        } else {
            // Short Press: queue Play/Pause with a 400ms window to detect "press then long press"
            lastShortPressTimeMs = now
            pendingPlayPauseJob?.cancel()
            pendingPlayPauseJob = scope.launch {
                delay(400)
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                _lastMediaEvent.value = "⏯ Play / Pause"
                clearBannerAfterDelay()
            }
            return true
        }
    }

    fun dispatchPlay(): Boolean =
        dispatchExplicitMediaAction(KeyEvent.KEYCODE_MEDIA_PLAY, "▶ Play")

    fun dispatchPause(): Boolean =
        dispatchExplicitMediaAction(KeyEvent.KEYCODE_MEDIA_PAUSE, "⏸ Pause")

    fun dispatchNext(): Boolean =
        dispatchExplicitMediaAction(KeyEvent.KEYCODE_MEDIA_NEXT, "⏭ Next Track")

    fun dispatchPrevious(): Boolean =
        dispatchExplicitMediaAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "⏮ Previous Track")

    fun dispatchStop(): Boolean =
        dispatchExplicitMediaAction(KeyEvent.KEYCODE_MEDIA_STOP, "⏹ Stop")

    private fun dispatchExplicitMediaAction(keyCode: Int, banner: String): Boolean {
        if (!AppSettings.getInstance(context).isMediaControlEnabled) {
            return false
        }
        sendMediaKey(keyCode)
        _lastMediaEvent.value = banner
        clearBannerAfterDelay()
        return true
    }

    private fun activeVolumeStream(am: AudioManager): Int {
        val inCall = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
        return if (inCall) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
    }

    /**
     * Applies cluster-requested volume (0..10 tenths per OEM Controls.setVolume) to AudioManager.
     * Returns the clamped tenths value applied, or null if skipped.
     */
    fun setMediaVolumeFromCluster(tenths: Int): Int? {
        if (!AppSettings.getInstance(context).isMediaControlEnabled) {
            return null
        }
        val am = audioManager ?: return null
        val clampedTenths = tenths.coerceIn(0, 10)
        val stream = activeVolumeStream(am)
        val maxVol = am.getStreamMaxVolume(stream)
        if (maxVol <= 0) return clampedTenths

        val targetLevel = ((maxVol / 10.0) * clampedTenths).roundToInt().coerceIn(0, maxVol)
        try {
            am.setStreamVolume(stream, targetLevel, 0)
            Log.i(TAG, "Cluster volume applied: tenths=$clampedTenths -> stream=$stream level=$targetLevel/$maxVol")
        } catch (e: SecurityException) {
            Log.w(TAG, "Cluster volume rejected by OS policy: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Cluster volume update failed: ${e.message}")
        }
        return clampedTenths
    }

    fun getMediaVolumeTenths(): Int {
        val am = audioManager ?: return 8
        val stream = activeVolumeStream(am)
        val maxVol = am.getStreamMaxVolume(stream)
        if (maxVol <= 0) return 8
        val current = am.getStreamVolume(stream)
        return ((current.toDouble() / maxVol.toDouble()) * 10.0).roundToInt().coerceIn(0, 10)
    }

    fun getActiveMediaSnapshot(): MediaMetadataSnapshot {
        val controller = findPreferredController() ?: return MediaMetadataSnapshot()
        val metadata = controller.metadata
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val durationSec = (durationMs / 1000L).toInt().coerceIn(0, 0xFFFF)

        val pb = controller.playbackState
        val playStatus = when (pb?.state) {
            PlaybackState.STATE_PLAYING -> 2
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING -> 1
            else -> 0
        }
        val elapsedSec = if (pb != null && pb.position >= 0L) {
            val deltaMs = if (playStatus == 2 && pb.lastPositionUpdateTime > 0L) {
                ((SystemClock.elapsedRealtime() - pb.lastPositionUpdateTime) * pb.playbackSpeed).toLong().coerceAtLeast(0L)
            } else {
                0L
            }
            val rawSec = ((pb.position + deltaMs) / 1000L).toInt().coerceAtLeast(0)
            if (durationSec > 0) rawSec.coerceAtMost(durationSec) else rawSec.coerceAtMost(0xFFFF)
        } else {
            0
        }

        val activeTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        recordTrackInHistory(activeTitle)

        return MediaMetadataSnapshot(
            title = activeTitle,
            artist = artist,
            album = album,
            elapsedSeconds = elapsedSec,
            durationSeconds = durationSec,
            playStatus = playStatus
        )
    }

    @Synchronized
    private fun recordTrackInHistory(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        if (recentTrackTitles.firstOrNull() != trimmed) {
            recentTrackTitles.remove(trimmed)
            recentTrackTitles.addFirst(trimmed)
            while (recentTrackTitles.size > 25) {
                recentTrackTitles.removeLast()
            }
            playlistChangeCounter = (playlistChangeCounter + 1) and 0xFF
        }
    }

    @Synchronized
    fun buildClusterPlaylistPage(requestedPage: Int, newPlaylistAck: Int = 0): ClusterPlaylistPage {
        val controller = findPreferredController()
        val activeTitle = controller?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        if (activeTitle.isNotEmpty()) {
            recordTrackInHistory(activeTitle)
        }

        val queueTitles = controller?.queue
            ?.mapNotNull { it.description?.title?.toString()?.trim()?.takeIf { t -> t.isNotEmpty() } }
            .orEmpty()

        val allTitles = if (queueTitles.isNotEmpty()) {
            queueTitles
        } else {
            recentTrackTitles.toList()
        }

        val total = allTitles.size.coerceAtMost(255)
        if (total == 0) {
            return ClusterPlaylistPage(
                trackTitles = emptyList(),
                totalSongs = 0,
                totalPages = 0,
                currentTrackNumber = 0,
                pagePlaylistAck = requestedPage.coerceAtLeast(1),
                newPlaylistAck = newPlaylistAck and 0x03,
                userChangePlaylistCounter = playlistChangeCounter
            )
        }

        val totalPages = (total + 4) / 5
        val page = requestedPage.coerceIn(1, totalPages)
        val startIdx = (page - 1) * 5
        val endIdx = (startIdx + 5).coerceAtMost(total)
        val slice = allTitles.subList(startIdx, endIdx)

        val activeId = controller?.playbackState?.activeQueueItemId ?: -1L
        val currentIdx = if (queueTitles.isNotEmpty() && activeId != -1L) {
            controller?.queue?.indexOfFirst { it.queueId == activeId }?.takeIf { it >= 0 } ?: 0
        } else {
            allTitles.indexOf(activeTitle).coerceAtLeast(0)
        }

        return ClusterPlaylistPage(
            trackTitles = slice,
            totalSongs = total,
            totalPages = totalPages,
            currentTrackNumber = currentIdx.coerceIn(0, 255),
            pagePlaylistAck = page,
            newPlaylistAck = newPlaylistAck and 0x03,
            userChangePlaylistCounter = playlistChangeCounter
        )
    }

    @Synchronized
    fun selectPlaylistTrack(pagePlaylist: Int, slotIndex: Int): Boolean {
        if (!AppSettings.getInstance(context).isMediaControlEnabled) return false
        val now = SystemClock.elapsedRealtime()
        if ((now - lastSelectTrackTimeMs) < 400L) return false
        lastSelectTrackTimeMs = now

        val controller = findPreferredController() ?: return false
        val page = pagePlaylist.coerceAtLeast(1)
        val slot = slotIndex.coerceIn(0, 4)
        val absIndex = (page - 1) * 5 + slot

        val queue = controller.queue
        if (!queue.isNullOrEmpty() && absIndex in queue.indices) {
            return try {
                controller.transportControls.skipToQueueItem(queue[absIndex].queueId)
                _lastMediaEvent.value = "🎵 Playlist Track #${absIndex + 1}"
                clearBannerAfterDelay()
                true
            } catch (e: Exception) {
                Log.w(TAG, "skipToQueueItem failed: ${e.message}")
                false
            }
        }

        // Fallback when app does not expose MediaSession.queue: step relative to active track
        val activeTitle = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val titles = recentTrackTitles.toList()
        if (absIndex !in titles.indices) return false
        val currentIdx = titles.indexOf(activeTitle).coerceAtLeast(0)
        val delta = absIndex - currentIdx
        if (delta == 0) {
            dispatchPlay()
            return true
        }
        val keyCode = if (delta > 0) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS
        val steps = kotlin.math.abs(delta).coerceAtMost(5)
        repeat(steps) {
            sendMediaKey(keyCode)
        }
        _lastMediaEvent.value = "🎵 Selected Track #${absIndex + 1}"
        clearBannerAfterDelay()
        return true
    }

    fun getActiveAlbumArtJpeg(maxSizePx: Int = 160): ByteArray? {
        val controller = findPreferredController() ?: return null
        val metadata = controller.metadata ?: return null
        val rawBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: return null
        return encodeAlbumArtBitmapToJpeg(rawBitmap, maxSizePx)
    }

    internal fun encodeAlbumArtBitmapToJpeg(
        rawBitmap: android.graphics.Bitmap,
        maxSizePx: Int = 160
    ): ByteArray? {
        var softwareBitmap: android.graphics.Bitmap? = null
        var scaled: android.graphics.Bitmap? = null
        return try {
            val sourceBitmap = if (rawBitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
                rawBitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)?.also {
                    softwareBitmap = it
                } ?: return null
            } else {
                rawBitmap
            }
            val scaledBitmap = if (sourceBitmap.width == maxSizePx && sourceBitmap.height == maxSizePx) {
                sourceBitmap
            } else {
                android.graphics.Bitmap.createScaledBitmap(sourceBitmap, maxSizePx, maxSizePx, true).also {
                    if (it !== sourceBitmap) scaled = it
                }
            }
            val out = java.io.ByteArrayOutputStream()
            scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "Failed encoding album art JPEG: ${e.message}")
            null
        } finally {
            scaled?.recycle()
            softwareBitmap?.recycle()
        }
    }

    private fun findPreferredController(): MediaController? {
        return try {
            val mediaSessionManager =
                context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                    ?: return null
            val component = ComponentName(context, GoogleMapsNotificationListener::class.java)
            val activeSessions = mediaSessionManager.getActiveSessions(component)
            if (activeSessions.isNullOrEmpty()) return null
            activeSessions.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: activeSessions.firstOrNull {
                !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
            } ?: activeSessions.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "MediaSession query skipped: ${e.message}")
            null
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        val eventTime = SystemClock.uptimeMillis()
        val down = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)

        // 1. Attempt direct control via active MediaSession (Spotify, YT Music, Audible, etc.)
        val activeController = findPreferredController()
        if (activeController != null) {
            try {
                activeController.dispatchMediaButtonEvent(down)
                activeController.dispatchMediaButtonEvent(up)
                Log.i(TAG, "Dispatched key $keyCode to active MediaSession: ${activeController.packageName}")
                return
            } catch (e: Exception) {
                Log.w(TAG, "MediaSession dispatch fallback: ${e.message}")
            }
        }

        // 2. Fallback to system AudioManager broadcast
        audioManager?.dispatchMediaKeyEvent(down)
        audioManager?.dispatchMediaKeyEvent(up)
    }

    private fun clearBannerAfterDelay() {
        clearBannerJob?.cancel()
        clearBannerJob = scope.launch {
            delay(3500)
            _lastMediaEvent.value = null
        }
    }
}
