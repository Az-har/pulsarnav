package com.pulsarnav.app.media

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import com.pulsarnav.app.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MediaControlManager private constructor(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _lastMediaEvent = MutableStateFlow<String?>(null)
    val lastMediaEvent: StateFlow<String?> = _lastMediaEvent.asStateFlow()

    companion object {
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

    @Synchronized
    fun handleHandlebarButton(isLongPress: Boolean): Boolean {
        if (!AppSettings.getInstance(context).isMediaControlEnabled) {
            return false
        }

        val now = SystemClock.uptimeMillis()

        if (isLongPress) {
            // Check if this long press was preceded by a short press within 1000ms ("Press then Long Press")
            val elapsedSinceShort = now - lastShortPressTimeMs
            if (elapsedSinceShort in 1..1000) {
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

    private fun sendMediaKey(keyCode: Int) {
        val eventTime = SystemClock.uptimeMillis()
        val down = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)

        // 1. Attempt direct control via active MediaSession (Spotify, YT Music, Audible, etc.)
        try {
            val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? android.media.session.MediaSessionManager
            val notificationListenerComponent = android.content.ComponentName(
                context,
                com.pulsarnav.app.service.GoogleMapsNotificationListener::class.java
            )
            val activeSessions = mediaSessionManager?.getActiveSessions(notificationListenerComponent)
            if (!activeSessions.isNullOrEmpty()) {
                val activeController = activeSessions.firstOrNull {
                    it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                } ?: activeSessions.first()

                activeController.dispatchMediaButtonEvent(down)
                activeController.dispatchMediaButtonEvent(up)
                android.util.Log.i("PulsarMedia", "Dispatched key $keyCode to active MediaSession: ${activeController.packageName}")
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("PulsarMedia", "MediaSession dispatch fallback: ${e.message}")
        }

        // 2. Fallback to system AudioManager broadcast
        audioManager?.dispatchMediaKeyEvent(down)
        audioManager?.dispatchMediaKeyEvent(up)
    }

    private fun clearBannerAfterDelay() {
        scope.launch {
            delay(3500)
            _lastMediaEvent.value = null
        }
    }
}
