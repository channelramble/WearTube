package com.wateruse.weartube.player

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the ExoPlayer + MediaSession so playback (esp. audio-only) survives the
 * activity being backgrounded / the screen turning off. The activity lives in the
 * same process and drives the player directly through PlayerController.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // modest buffers: googlevideo throttles greedy read-ahead (WristTube lesson)
                    // ~30s lead, matching WristTube's readAheadSeconds. A 60s
                    // ceiling pulled far more of the stream than playback needed,
                    // which burns per-URL budgets (and therefore player API calls)
                    // several times faster than necessary.
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 15_000,
                        /* maxBufferMs = */ 30_000,
                        /* bufferForPlaybackMs = */ 2_000,
                        /* bufferForPlaybackAfterRebufferMs = */ 4_000,
                    )
                    .setBackBuffer(/* backBufferDurationMs = */ 10_000, /* retainBackBufferFromKeyframe = */ true)
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        session = MediaSession.Builder(this, player).build()
        // MUST addSession: without it the service never posts a media notification,
        // so the system logs `setFgsIfNoSessionIsLinkedToNotification`, refuses
        // foreground-service status, and destroys the session ~60s after the app
        // backgrounds — background audio dies. Measured on the watch 2026-08-18.
        session?.let { addSession(it) }
        // Decorate the media notification with a Wear Ongoing Activity so playback
        // is visible (and tappable) from the watch face itself.
        // NOTE: deliberately NO custom Wear OngoingActivity here. Media3's own
        // media notification already produces the watch-face chip, and tapping it
        // opens the system media controller (play/pause, skip, scrub, output).
        // Adding an OngoingActivity on top produced a SECOND, worse chip in the
        // ongoing list — verified on the Ultra 2 by A/B: the chip is present with
        // the provider removed. addSession() above is what makes it appear.
        PlayerController.onServiceCreated(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        PlayerController.onServiceDestroyed()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
