package com.wateruse.weartube.player

import com.wateruse.weartube.data.friendlyError
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.wateruse.weartube.data.Repo
import com.wateruse.weartube.data.Settings
import com.wateruse.weartube.data.Store
import com.wateruse.weartube.data.StreamBundle
import com.wateruse.weartube.data.StreamChoice
import com.wateruse.weartube.data.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single source of truth for playback. Activity UIs observe the flows; the
 * ExoPlayer itself lives in PlaybackService (same process, direct access).
 *
 * Stall/expiry recovery follows WristTube's conservative rule: individual HTTP
 * failures are normal (URLs rotate), so recovery re-resolves fresh URLs and
 * seeks back, at most [MAX_RECOVERIES] times per video.
 */
@OptIn(UnstableApi::class)
object PlayerController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var player: ExoPlayer? = null
        private set

    /**
     * Observable mirror of [player] for Compose. AndroidView's update block only
     * re-runs on snapshot-state changes, so the PlayerView surface attach MUST read
     * this flow — reading the plain var meant the surface never attached when the
     * service finished creating the player after first composition (audio-only bug).
     */
    private val _playerFlow = MutableStateFlow<ExoPlayer?>(null)
    val playerFlow: StateFlow<ExoPlayer?> = _playerFlow

    private val _nowPlaying = MutableStateFlow<Video?>(null)
    val nowPlaying: StateFlow<Video?> = _nowPlaying

    private val _bundle = MutableStateFlow<StreamBundle?>(null)
    val bundle: StateFlow<StreamBundle?> = _bundle

    private val _choice = MutableStateFlow<StreamChoice?>(null)
    val choice: StateFlow<StreamChoice?> = _choice

    private val _upNext = MutableStateFlow<List<Video>>(emptyList())
    val upNext: StateFlow<List<Video>> = _upNext

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private var stallWatchdog: Job? = null
    private var downshifts = 0
    private var queue: MutableList<Video> = mutableListOf()
    private var recoveries = 0
    private var positionJob: Job? = null
    private var pendingPlay: Video? = null

    private const val MAX_RECOVERIES = 3
    private const val MAX_DOWNSHIFTS = 3

    fun ensureService(context: Context) {
        if (player == null) {
            val intent = Intent(context, PlaybackService::class.java)
            // startForegroundService so the service may promote itself while the
            // activity is backgrounded (plain startService cannot on Android 12+).
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    internal fun onServiceCreated(p: ExoPlayer) {
        player = p
        _playerFlow.value = p
        p.addListener(listener)
        pendingPlay?.let { v ->
            pendingPlay = null
            play(v)
        }
    }

    internal fun onServiceDestroyed() {
        player?.removeListener(listener)
        player = null
        _playerFlow.value = null
        positionJob?.cancel()
        _nowPlaying.value = null
    }

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            android.util.Log.i("WTStream", "first video frame rendered to surface")
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                saveCurrentPosition(ended = true)
                if (Settings.autoplayNext) playNext()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val v = _nowPlaying.value ?: return
            if (recoveries >= MAX_RECOVERIES) {
                // Prefer the reason we actually diagnosed. ExoPlayer wraps our
                // PlaybackBlockedException several layers deep and its own
                // errorCodeName ("ERROR_CODE_IO_UNSPECIFIED") tells the user nothing.
                _error.value = rootReason(error) ?: "Playback failed: ${error.errorCodeName}"
                return
            }
            recoveries++
            // Recoveries 1-2 re-resolve with the SAME client (fresh URLs — the usual
            // cause is URL expiry/rotation). Only a third failure rotates clients, and
            // that is a last resort: on Android hardware, IOS-issued googlevideo URLs
            // 403 on every chunk (fingerprint coherence), so rotating away from ANDROID
            // turns a transient stall into permanent failure. Measured 2026-08-18.
            val current = _bundle.value?.clientIndex ?: 0
            val fromClient = if (recoveries >= 3) current + 1 else current
            val resumeAt = player?.currentPosition ?: 0L
            scope.launch {
                try {
                    StreamResolver.invalidate(v.id)
                    val fresh = StreamResolver.resolve(v.id, forceFresh = true, startClient = fromClient)
                    _bundle.value = fresh
                    val sel = StreamResolver.choose(fresh, Settings.preferredHeight, Settings.audioOnly)
                    prepareAndPlay(v, fresh, sel, resumeAt)
                } catch (e: Exception) {
                    _error.value = friendlyError(e, "Playback failed")
                }
            }
        }
    }

    /** Deepest message we raised ourselves, if any, from a wrapped ExoPlayer error. */
    private fun rootReason(e: Throwable): String? {
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is com.wateruse.weartube.data.PlaybackBlockedException) return cur.message
            cur = cur.cause
        }
        return null
    }

    /** Start playing a video; [withQueue] replaces the queue (e.g. playlist Play All). */
    fun play(video: Video, withQueue: List<Video>? = null) {
        val p = player
        if (p == null) {
            pendingPlay = video
            return
        }
        recoveries = 0
        downshifts = 0
        StreamUrls.forget(video.id)
        _error.value = null
        _isLoading.value = true
        _nowPlaying.value = video
        _bundle.value = null
        _choice.value = null
        if (withQueue != null) {
            queue = withQueue.filterNot { it.id == video.id }.toMutableList()
        }
        scope.launch {
            withContext(Dispatchers.IO) { Store.addHistory(video) }
            try {
                val bundle = StreamResolver.resolve(video.id)
                _bundle.value = bundle
                val sel = StreamResolver.choose(bundle, Settings.preferredHeight, Settings.audioOnly)
                val resumeAt = resumePositionMs(video, bundle)
                prepareAndPlay(video, bundle, sel, resumeAt)
            } catch (e: Exception) {
                _isLoading.value = false
                _error.value = friendlyError(e, "Can't play this video")
            }
            // fetch related in the background for Up Next / autoplay
            try {
                val rel = Repo.related(video.id)
                _upNext.value = rel
            } catch (_: Exception) {
                _upNext.value = emptyList()
            }
        }
    }

    private fun resumePositionMs(video: Video, bundle: StreamBundle): Long {
        val saved = Store.position(video.id)
        if (saved > 5) return saved * 1000L
        // fall back to the account-less watched percent from feed thumbnails
        if (video.watchedPercent in 3..94 && bundle.durationSec > 0) {
            return video.watchedPercent * bundle.durationSec * 10L // percent/100 * sec * 1000
        }
        return 0L
    }

    private fun prepareAndPlay(video: Video, bundle: StreamBundle, sel: StreamChoice, positionMs: Long) {
        val p = player ?: return
        _choice.value = sel
        RangedDataSource.streamUserAgent = bundle.streamUa.ifEmpty { RangedDataSource.streamUserAgent }
        val meta = MediaMetadata.Builder()
            .setTitle(bundle.title.ifEmpty { video.title })
            .setArtist(bundle.channel.ifEmpty { video.channel })
            .apply { if (video.thumb.isNotEmpty()) setArtworkUri(Uri.parse(video.thumb)) }
            .build()
        p.setMediaSource(buildSource(sel, meta, video.id))
        p.prepare()
        if (positionMs > 0) p.seekTo(positionMs)
        p.playWhenReady = true
        _isLoading.value = false
        startPositionSaver(video.id)
        startStallWatchdog(video)
    }

    /**
     * Feed ExoPlayer STABLE wt:// URIs rather than googlevideo URLs.
     *
     * A googlevideo URL is spent after ~0.5-2MB and must be re-issued; if the
     * MediaItem held the raw URL there would be nothing to swap it for mid-play.
     * RangedDataSource resolves wt:// per read and rotates underneath (StreamUrls).
     */
    /**
     * Steps quality down when the stream cannot be sustained.
     *
     * A googlevideo URL yields only a few hundred KB before refusing, and fresh
     * URLs can only be requested every few seconds without tripping bot checks.
     * That puts a hard ceiling on deliverable bitrate: measured on the Ultra 2,
     * 480p (~150KB/s) needs a new URL roughly every 1.5s against a 4s floor, so it
     * buffers forever — position frozen, dozens of rotations, no recovery. 360p and
     * below fit comfortably. Rather than let the user pick a setting that bricks
     * playback, detect the stall and drop a rung.
     */
    private fun startStallWatchdog(video: Video) {
        stallWatchdog?.cancel()
        stallWatchdog = scope.launch {
            var lastPos = -1L
            var stuckSince = 0L
            while (isActive) {
                delay(3_000)
                val p = player ?: continue
                val pos = p.currentPosition
                val buffering = p.playbackState == Player.STATE_BUFFERING
                if (buffering && p.playWhenReady && pos == lastPos) {
                    if (stuckSince == 0L) stuckSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - stuckSince >= 12_000) {
                        stuckSince = 0L
                        if (!downshift(video)) {
                            _error.value =
                                if (StreamUrls.looksRateLimited())
                                    "YouTube is rate-limiting this network. Try again later."
                                else "Stream won't keep up on this connection."
                            player?.pause()   // stop burning requests on a dead stream
                        }
                    }
                } else {
                    stuckSince = 0L
                }
                lastPos = pos
            }
        }
    }

    /**
     * Drop to the next lower rendition; false when there is nowhere lower to go.
     *
     * Two guards matter here. Picking a rung equal to the current one re-prepares
     * the same stream forever — at audio-only the "next lower" search used to
     * return audio-only itself, which looped every 12s and poured player-API calls
     * into an already-throttled connection (measured: 14 downshifts, 70 API calls,
     * position frozen). And a stall that survives a few steps down is not a bitrate
     * problem at all — it is the network refusing us — so stepping further only
     * makes that worse.
     */
    private fun downshift(video: Video): Boolean {
        if (downshifts >= MAX_DOWNSHIFTS) return false
        val b = _bundle.value ?: return false
        val current = _choice.value ?: return false
        val lower = b.choices
            .filter { it.height in 1 until current.height }
            .maxByOrNull { it.height }
            ?: b.choices.firstOrNull { it.height == 0 }
            ?: return false
        // already at the bottom rung: nothing to gain by re-preparing it
        if (lower.height == current.height) return false
        downshifts++
        android.util.Log.w(
            "WTStream",
            "stalled at ${current.label}; stepping down to ${lower.label} ($downshifts/$MAX_DOWNSHIFTS)"
        )
        applyChoice(lower, persist = false)
        return true
    }

    private fun buildSource(sel: StreamChoice, meta: MediaMetadata, videoId: String): MediaSource {
        val factory = ProgressiveMediaSource.Factory(RangedDataSource.Factory())
        fun item(uri: String) = MediaItem.Builder().setUri(uri).setMediaMetadata(meta).build()
        fun uri(kind: StreamUrls.Kind) = StreamUrls.uriFor(videoId, kind, sel.height)
        return when {
            sel.muxedUrl != null ->
                factory.createMediaSource(item(uri(StreamUrls.Kind.PROGRESSIVE)))
            sel.videoUrl != null && sel.audioUrl != null -> MergingMediaSource(
                factory.createMediaSource(item(uri(StreamUrls.Kind.VIDEO))),
                factory.createMediaSource(item(uri(StreamUrls.Kind.AUDIO))),
            )
            else -> factory.createMediaSource(item(uri(StreamUrls.Kind.AUDIO)))
        }
    }

    /**
     * Switch rendition in place, keeping position.
     *
     * [persist] must be false for automatic changes. An auto-downshift that wrote
     * the setting turned a momentary network dip into a permanent preference —
     * after one stall the app played audio-only for every video afterwards, with
     * nothing on screen explaining why. Only an explicit pick in the quality menu
     * changes what the user has chosen.
     */
    fun applyChoice(sel: StreamChoice, persist: Boolean = true) {
        val p = player ?: return
        val v = _nowPlaying.value ?: return
        val b = _bundle.value ?: return
        val pos = p.currentPosition
        val wasPlaying = p.playWhenReady
        _choice.value = sel
        if (persist) {
            Settings.audioOnly = sel.height == 0
            if (sel.height > 0) Settings.preferredHeight = if (sel.muxedUrl != null) 0 else sel.height
        }
        val meta = MediaMetadata.Builder().setTitle(b.title).setArtist(b.channel).build()
        p.setMediaSource(buildSource(sel, meta, v.id))
        p.prepare()
        p.seekTo(pos)
        p.playWhenReady = wasPlaying
    }

    /**
     * Advance to the next video. Works the same whether the app is on screen or
     * backgrounded — the listener that calls this lives on the service's player.
     *
     * Previously this gave up silently when both the queue and the related list
     * were empty, which is exactly what happens if the background related-fetch
     * lost a race or failed on a flaky connection: the video ended and playback
     * simply stopped. Now the list is fetched on demand before conceding, and
     * anything just watched is skipped so autoplay cannot loop on one video.
     */
    fun playNext() {
        val queued = queue.removeFirstOrNull()
        if (queued != null) {
            play(queued)
            return
        }
        val current = _nowPlaying.value?.id
        _upNext.value.firstOrNull { it.id != current }?.let {
            android.util.Log.i("WTStream", "autoplay -> ${it.id} (from related)")
            play(it)
            return
        }
        // nothing in hand: fetch related now rather than ending playback
        val videoId = current ?: return
        scope.launch {
            val rel = try {
                Repo.related(videoId)
            } catch (e: Exception) {
                android.util.Log.w("WTStream", "autoplay related fetch failed: ${e.message}")
                emptyList()
            }
            val recent = withContext(Dispatchers.IO) { Store.history().take(5).map { it.id }.toSet() }
            val next = rel.firstOrNull { it.id != videoId && it.id !in recent }
                ?: rel.firstOrNull { it.id != videoId }
            if (next != null) {
                android.util.Log.i("WTStream", "autoplay -> ${next.id} (fetched on demand)")
                play(next)
            } else {
                android.util.Log.w("WTStream", "autoplay: nothing to play next")
            }
        }
    }

    fun stop() {
        saveCurrentPosition()
        stallWatchdog?.cancel()
        positionJob?.cancel()
        player?.stop()
        player?.clearMediaItems()
        _nowPlaying.value = null
        _bundle.value = null
        _choice.value = null
        _error.value = null
        queue.clear()
    }

    private fun startPositionSaver(videoId: String) {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                delay(5000)
                saveCurrentPosition()
            }
        }
    }

    private fun saveCurrentPosition(ended: Boolean = false) {
        val p = player ?: return
        val v = _nowPlaying.value ?: return
        val posSec = if (ended) 0L else p.currentPosition / 1000
        scope.launch(Dispatchers.IO) { Store.savePosition(v.id, posSec) }
    }
}
