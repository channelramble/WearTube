package com.wateruse.weartube.player

import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * Per-URL byte budgets and URL rotation for googlevideo streams.
 *
 * THE CENTRAL FACT (ported from WristTube, measured there over many builds):
 * a googlevideo URL serves roughly half a megabyte and then answers 403 to
 * EVERYTHING — regardless of how small the individual ranges are. The cap is a
 * per-URL byte budget, not a per-request size limit, and it varies with IP
 * reputation (~2MB on a clean IP, ~256-512KB once hammered).
 *
 * WearTube originally responded to 403s by halving the chunk size, which cannot
 * work: shrinking requests on a spent URL still 403s, all the way down to the
 * 32KB floor (observed on the watch — 29 refusals, playback dead). The fix is to
 * re-issue the URL from the player API before the budget runs out, and to have a
 * replacement already in hand so the swap costs no stall.
 *
 * Rotation itself is expensive and dangerous: each one is a player API call, and
 * issuing those in a loop is exactly what earns "Sign in to confirm you're not a
 * bot". So rotations are rate limited hard and concurrent callers are coalesced
 * (video and audio tracks hit their budgets at the same moment and would
 * otherwise fire two identical player requests).
 *
 * Progressive/muxed URLs are NOT throttled and get no budget at all — measured
 * on WristTube at 20.5MB from a single URL with zero refusals.
 */
object StreamUrls {

    enum class Kind { VIDEO, AUDIO, PROGRESSIVE }

    private const val SCHEME = "wt"

    /** Start conservative: the real cap is often ~256KB on a warm IP. */
    private const val BUDGET_START = 512L shl 10
    private const val BUDGET_FLOOR = 192L shl 10
    private const val BUDGET_CEILING = 2048L shl 10

    /** Minimum gap between player API calls for one video (bot-check safety). */
    private const val ROTATE_MIN_GAP_MS = 4_000L
    private const val PREFETCH_MIN_GAP_MS = 3_000L

    /** Build the stable URI handed to ExoPlayer; the real URL is resolved per read. */
    fun uriFor(videoId: String, kind: Kind, height: Int): String =
        "$SCHEME://stream/$videoId/${kind.name}/$height"

    fun parse(uri: String): Triple<String, Kind, Int>? {
        if (!uri.startsWith("$SCHEME://stream/")) return null
        val parts = uri.removePrefix("$SCHEME://stream/").split("/")
        if (parts.size < 3) return null
        val kind = runCatching { Kind.valueOf(parts[1]) }.getOrNull() ?: return null
        return Triple(parts[0], kind, parts[2].toIntOrNull() ?: 0)
    }

    private class State(
        var url: String? = null,
        var spare: String? = null,
        var budgetUsed: Long = 0,
        var budget: Long = BUDGET_START,
        var lastRotateAt: Long = 0,
        var prefetching: Boolean = false,
    )

    private val states = HashMap<String, State>()
    private val lock = Any()
    /** Player-API pacing is per video: the tracks share one upstream budget of goodwill. */
    private val lastCallAt = HashMap<String, Long>()
    private val callLock = Any()

    /**
     * The most recent player response per video, with the time it landed.
     *
     * ONE player call returns URLs for EVERY track, so a rotation triggered by the
     * video track can also serve the audio track. Without this the two tracks
     * alternate through the per-video pace gate — VIDEO at t, AUDIO at t+4s, VIDEO
     * at t+8s — so each track is refreshed only every 8s. At 480p a URL's budget
     * lasts ~3-4s, so playback starved permanently (measured on the Ultra 2:
     * 33 rotations, position frozen at 1:55). Sharing the response fixes the
     * starvation AND halves player-API traffic.
     */
    private val recentBundle = HashMap<String, Pair<Long, com.wateruse.weartube.data.StreamBundle>>()
    private const val BUNDLE_REUSE_MS = 3_000L

    private fun paceCall(videoId: String, gapMs: Long) {
        val waitMs: Long
        synchronized(callLock) {
            val now = System.currentTimeMillis()
            val since = now - (lastCallAt[videoId] ?: 0L)
            waitMs = if (since >= gapMs) 0L else gapMs - since
            lastCallAt[videoId] = now + waitMs
        }
        if (waitMs > 0) try { Thread.sleep(waitMs) } catch (_: InterruptedException) {}
    }

    private fun key(videoId: String, kind: Kind, height: Int) = "$videoId|$kind|$height"

    private fun state(videoId: String, kind: Kind, height: Int): State =
        synchronized(lock) { states.getOrPut(key(videoId, kind, height)) { State() } }

    fun forget(videoId: String) {
        synchronized(lock) { states.keys.removeAll { it.startsWith("$videoId|") } }
        synchronized(callLock) { recentBundle.remove(videoId) }
        clearRateLimit()
    }

    private fun isUnthrottled(kind: Kind) = kind == Kind.PROGRESSIVE

    /** Bytes served by the URL currently in hand (0 right after a rotation). */
    fun bytesServedOnCurrentUrl(videoId: String, kind: Kind, height: Int): Long {
        val st = state(videoId, kind, height)
        return synchronized(st) { st.budgetUsed }
    }

    // Rate-limit detection: fresh URLs refusing before serving any bytes.
    private var freshRefusals = 0
    private var freshRefusalWindowStart = 0L
    /**
     * Once tripped, stays tripped for a while. The rolling counter alone reset
     * between the refusals and the moment the UI asked, so a genuinely
     * rate-limited stream reported the generic "won't keep up" message instead.
     */
    private var rateLimitedUntil = 0L

    fun noteRateLimited() = synchronized(callLock) {
        val now = System.currentTimeMillis()
        if (now - freshRefusalWindowStart > 60_000) {
            freshRefusalWindowStart = now
            freshRefusals = 0
        }
        freshRefusals++
        if (freshRefusals >= 3) rateLimitedUntil = now + 120_000
    }

    /** Three fresh URLs refused within a minute: the network is flagged, not us. */
    fun looksRateLimited(): Boolean =
        synchronized(callLock) { freshRefusals >= 3 || System.currentTimeMillis() < rateLimitedUntil }

    fun clearRateLimit() = synchronized(callLock) { freshRefusals = 0; rateLimitedUntil = 0 }

    /**
     * The URL to use for the next read of [count] bytes, rotating first if this
     * read would exceed the current URL's budget.
     */
    fun urlForRead(videoId: String, kind: Kind, height: Int, count: Long): String? {
        val st = state(videoId, kind, height)
        synchronized(st) {
            if (st.url == null) st.url = fetchFresh(videoId, kind, height)
            if (isUnthrottled(kind)) return st.url

            if (st.budgetUsed + count > st.budget) {
                val spare = st.spare
                if (spare != null) {
                    // Prefetched in advance: swapping costs nothing here, which is
                    // what keeps playback from stalling at every budget boundary.
                    st.url = spare
                    st.spare = null
                    st.budgetUsed = 0
                } else {
                    val fresh = fetchFresh(videoId, kind, height, replacing = st.url)
                    if (fresh != null) {
                        st.url = fresh
                        st.budgetUsed = 0
                    }
                }
            }
            maybePrefetchSpare(videoId, kind, height, st)
            return st.url
        }
    }

    /** Record a successful read; eases the budget up while reads keep landing. */
    fun onRead(videoId: String, kind: Kind, height: Int, bytes: Long) {
        if (isUnthrottled(kind)) return
        val st = state(videoId, kind, height)
        synchronized(st) {
            st.budgetUsed += bytes
            // Double rather than inch upward: each rotation costs a player API
            // request, so a budget that climbs slowly makes API traffic dwarf media.
            if (st.budgetUsed >= st.budget / 2 && st.budget < BUDGET_CEILING) {
                st.budget = minOf(BUDGET_CEILING, st.budget * 2)
            }
        }
    }

    /**
     * A refusal: the URL gave less than we asked of it. Halve the budget estimate
     * and rotate. Returns true when a different URL is now in place.
     */
    fun onRefused(videoId: String, kind: Kind, height: Int): Boolean {
        val st = state(videoId, kind, height)
        synchronized(st) {
            st.budget = maxOf(BUDGET_FLOOR, st.budget / 2)
            val spare = st.spare
            if (spare != null) {
                st.url = spare
                st.spare = null
                st.budgetUsed = 0
                return true
            }
            val fresh = fetchFresh(videoId, kind, height, replacing = st.url) ?: return false
            st.url = fresh
            st.budgetUsed = 0
            return true
        }
    }

    private fun maybePrefetchSpare(videoId: String, kind: Kind, height: Int, st: State) {
        if (st.spare != null || st.prefetching) return
        if (st.budgetUsed <= st.budget * 3 / 4) return
        st.prefetching = true
        Thread {
            val cur = synchronized(st) { st.url }
            val url = fetchFresh(videoId, kind, height, prefetch = true, replacing = cur)
            synchronized(st) {
                if (url != null) st.spare = url
                st.prefetching = false
            }
        }.apply { isDaemon = true }.start()
    }

    private fun urlFrom(
        bundle: com.wateruse.weartube.data.StreamBundle,
        kind: Kind,
        height: Int,
    ): String? {
        val choice = bundle.choices.firstOrNull { it.height == height } ?: bundle.choices.firstOrNull()
        return when (kind) {
            Kind.VIDEO -> choice?.videoUrl
            Kind.AUDIO -> choice?.audioUrl ?: bundle.choices.firstNotNullOfOrNull { it.audioUrl }
            Kind.PROGRESSIVE -> choice?.muxedUrl
        }
    }

    /**
     * Re-resolve the video and pick the URL for this track. Rate limited per
     * video; StreamResolver coalesces genuinely concurrent callers.
     */
    private fun fetchFresh(
        videoId: String,
        kind: Kind,
        height: Int,
        prefetch: Boolean = false,
        replacing: String? = null,
    ): String? {
        // A sibling track may have just re-resolved this video; its URLs are ours too.
        synchronized(callLock) {
            recentBundle[videoId]?.let { (at, b) ->
                if (System.currentTimeMillis() - at < BUNDLE_REUSE_MS) {
                    // Never hand back the URL we are replacing: a shared bundle holds
                    // one URL per track, so reusing it for the track that just spent
                    // that URL returns the dead one and 403s instantly.
                    urlFrom(b, kind, height)?.takeIf { it != replacing }?.let {
                        Log.i("WTStream", "reused fresh bundle for $kind ${height}p ($videoId)")
                        return it
                    }
                }
            }
        }
        paceCall(videoId, if (prefetch) PREFETCH_MIN_GAP_MS else ROTATE_MIN_GAP_MS)
        // Re-check: while we waited on the pace gate, a sibling may have resolved.
        synchronized(callLock) {
            recentBundle[videoId]?.let { (at, b) ->
                if (System.currentTimeMillis() - at < BUNDLE_REUSE_MS) {
                    urlFrom(b, kind, height)?.takeIf { it != replacing }?.let { return it }
                }
            }
        }
        return try {
            val bundle = runBlocking { StreamResolver.resolve(videoId, forceFresh = true) }
            synchronized(callLock) { recentBundle[videoId] = System.currentTimeMillis() to bundle }
            RangedDataSource.streamUserAgent = bundle.streamUa.ifEmpty { RangedDataSource.streamUserAgent }
            val url = urlFrom(bundle, kind, height)
            Log.i("WTStream", "rotated $kind ${height}p for $videoId -> ${if (url != null) "ok" else "MISS"}")
            url
        } catch (e: Exception) {
            Log.w("WTStream", "rotate failed for $videoId $kind: ${e.message}")
            null
        }
    }
}
