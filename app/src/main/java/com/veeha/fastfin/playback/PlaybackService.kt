package com.veeha.fastfin.playback

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.veeha.fastfin.FastFinApp

/**
 * Lends the app's one media session to the system. Media3 draws the
 * notification (and lock screen and Bluetooth controls) from it, with
 * previous/next episode; the session itself belongs to [PlaybackManager] and
 * lives exactly as long as the player does.
 */
class PlaybackService : MediaSessionService() {

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        (application as FastFinApp).graph.playback.session

    /** Swiped away from recents: nothing should keep going. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as FastFinApp).graph.playback.close()
        stopSelf()
    }
}
