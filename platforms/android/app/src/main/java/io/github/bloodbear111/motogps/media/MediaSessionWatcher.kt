package io.github.bloodbear111.motogps.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService

/** What the round display's music page needs, from whichever app is playing. */
data class MediaSnapshot(
    val sourceName: String,
    val title: String,
    val artist: String,
    val playing: Boolean,
    val positionS: Int,
    val durationS: Int,
    val trackToken: Int,
)

/** Transport commands the display can send (DeviceCommand kinds 16..18). */
enum class MediaTransport { Previous, TogglePlayback, Next }

/**
 * Reads the system media session and drives it.
 *
 * Android only lets an app see another app's session through the notification
 * listener permission, so this is a [NotificationListenerService]: the rider
 * grants "通知使用权" once in Settings and the music page starts receiving data.
 * NetEase Cloud Music (`com.netease.cloudmusic`) is preferred when several
 * sessions are active; anything else that publishes a session with a title is
 * used as a fallback rather than showing an empty page.
 *
 * The controller is kept in the companion because the transport buttons arrive
 * from the BLE thread while the service instance is owned by the system.
 */
class MediaSessionWatcher : NotificationListenerService() {

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish(controller)
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish(controller)
        override fun onSessionDestroyed() = attach(null)
    }

    private var controller: MediaController? = null

    private val sessionsChanged =
        android.media.session.MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attach(best(controllers.orEmpty()))
        }

    override fun onListenerConnected() {
        // NotificationListenerService has no "sessions changed" hook of its
        // own; the listener comes from the session manager, scoped to this
        // component (which is what the notification-access grant authorises).
        try {
            getSystemService(android.media.session.MediaSessionManager::class.java)
                ?.addOnActiveSessionsChangedListener(
                    sessionsChanged,
                    ComponentName(this, MediaSessionWatcher::class.java),
                )
        } catch (error: Throwable) {
            // Without the listener the page still works from the one-shot read
            // below; it just will not follow a track change immediately.
        }
        attach(best(bestSessions()))
    }

    override fun onListenerDisconnected() {
        attach(null)
    }

    override fun onDestroy() {
        runCatching {
            getSystemService(android.media.session.MediaSessionManager::class.java)
                ?.removeOnActiveSessionsChangedListener(sessionsChanged)
        }
        attach(null)
        super.onDestroy()
    }

    private fun bestSessions(): List<MediaController> = try {
        val manager = getSystemService(android.media.session.MediaSessionManager::class.java)
            ?: return emptyList()
        @Suppress("MissingPermission") // granted through notification access
        val sessions = manager.getActiveSessions(
            ComponentName(this, MediaSessionWatcher::class.java),
        )
        sessions.orEmpty()
    } catch (error: Throwable) {
        emptyList()
    }

    private fun attach(next: MediaController?) {
        controller?.unregisterCallback(callback)
        controller = next
        next?.registerCallback(callback)
        publish(next)
    }

    private fun publish(source: MediaController?) {
        Companion.publish(source, this)
    }

    companion object {

        const val NETEASE_PACKAGE = "com.netease.cloudmusic"

        /**
         * NetEase first, then any session that actually has a title. A session
         * with no metadata is a media button receiver, not something worth
         * showing, and picking it would leave the page blank.
         */
        private fun best(candidates: List<MediaController>): MediaController? {
            val usable = candidates.filter {
                it.packageName != null &&
                    (it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
                        .isNotEmpty()
            }
            return usable.firstOrNull { it.packageName == NETEASE_PACKAGE }
                ?: usable.firstOrNull()
                ?: candidates.firstOrNull()
        }

        @Volatile
        private var active: MediaController? = null

        private val _state = kotlinx.coroutines.flow.MutableStateFlow<MediaSnapshot?>(null)
        val state: kotlinx.coroutines.flow.StateFlow<MediaSnapshot?> = _state

        private fun publish(source: MediaController?, context: Context) {
            active = source
            if (source == null) {
                _state.value = null
                return
            }
            val metadata = source.metadata
            val playback = source.playbackState
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            val application = try {
                val info = context.packageManager.getApplicationInfo(source.packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            } catch (error: Throwable) {
                source.packageName.orEmpty()
            }
            _state.value = MediaSnapshot(
                sourceName = application,
                title = title,
                artist = artist,
                playing = playback?.state == PlaybackState.STATE_PLAYING,
                positionS = ((playback?.position ?: 0L) / 1_000L).toInt(),
                durationS = ((metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) /
                    1_000L).toInt(),
                // Stable across restarts so the device can tell a repeat of the
                // same track from a new one without comparing the text.
                trackToken = "$NETEASE_PACKAGE|$title|$artist".hashCode(),
            )
        }

        /**
         * True when the rider has granted notification access. Without it the
         * music page has nothing to show and no buttons can be acted on, so the
         * UI has to say which of the two it is.
         */
        fun isAuthorized(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            return enabled.contains(context.packageName)
        }

        /** @return false when no session is available to drive. */
        fun transport(command: MediaTransport): Boolean {
            val controls = active?.transportControls ?: return false
            return try {
                when (command) {
                    MediaTransport.Previous -> controls.skipToPrevious()
                    MediaTransport.Next -> controls.skipToNext()
                    MediaTransport.TogglePlayback -> {
                        if (active?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                            controls.pause()
                        } else {
                            controls.play()
                        }
                    }
                }
                true
            } catch (error: Throwable) {
                false
            }
        }
    }
}
