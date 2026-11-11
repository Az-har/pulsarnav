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
    private val scope = CoroutineScope(Dispatchers.Main)

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

    private var lastShortPressTimeMs = 0L
    private var pendingPlayPauseJob: kotlinx.coroutines.Job? = null
    private var clearBannerJob: kotlinx.coroutines.Job? = null

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
        val inCall = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
        val stream = if (inCall) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
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
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVol <= 0) return 8
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return ((current.toDouble() / maxVol.toDouble()) * 10.0).roundToInt().coerceIn(0, 10)
    }

    fun getActiveMediaSnapshot(): MediaMetadataSnapshot {
        val controller = findPreferredController() ?: return MediaMetadataSnapshot()
        val metadata = controller.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
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

        return MediaMetadataSnapshot(
            title = title,
            artist = artist,
            album = album,
            elapsedSeconds = elapsedSec,
            durationSeconds = durationSec,
            playStatus = playStatus
        )
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
