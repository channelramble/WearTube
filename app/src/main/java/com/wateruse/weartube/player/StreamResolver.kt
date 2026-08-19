package com.wateruse.weartube.player

import com.wateruse.weartube.data.InnerTube
import com.wateruse.weartube.data.PlaybackBlockedException
import com.wateruse.weartube.data.StreamBundle
import com.wateruse.weartube.data.StreamChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Turns a videoId into playable stream URLs.
 *
 * Rules carried over from WristTube (all measured, don't relitigate):
 *  - iOS client pinned to 20.10.4 first; newest client versions withhold URLs (SABR).
 *  - H.264 (avc1) video + AAC (mp4a) audio only.
 *  - Muxed itag 18 (360p) is the cheapest reliable rendition; prefer it for Auto.
 *  - Audio language: device language -> audioIsDefault -> lowest bitrate. Formats
 *    without audioTrack fall through to lowest bitrate.
 *  - The player request stays anonymous. Never attach cookies or auth.
 */
object StreamResolver {

    private val cache = HashMap<String, StreamBundle>()
    /**
     * In-flight player requests, coalesced per video. NO time-based cache here:
     * a caller asking for a fresh resolve does so precisely because its URL is
     * spent, and handing back a cached response returns the same dead URLs.
     * Only genuinely concurrent callers share — which is what the video and audio
     * tracks rotating together actually need.
     */
    private val inFlight = HashMap<String, kotlinx.coroutines.Deferred<StreamBundle>>()
    private val inFlightLock = Any()
    private const val FRESH_MS = 4 * 60 * 60 * 1000L // stream URLs last ~6h; refresh well before

    /**
     * [startClient] rotates the client order on stall recovery: attempt 0 resolves with
     * the preferred client, later recoveries move down InnerTube.playerClients so an
     * enforcement 403 on one client's URLs gets a different client's URLs next.
     */
    suspend fun resolve(videoId: String, forceFresh: Boolean = false, startClient: Int = 0): StreamBundle = withContext(Dispatchers.IO) {
        synchronized(cache) {
            val hit = cache[videoId]
            if (!forceFresh && hit != null && System.currentTimeMillis() - hit.resolvedAtMs < FRESH_MS) return@withContext hit
        }
        // coalesce concurrent resolves of the same video
        val existing = synchronized(inFlightLock) { inFlight[videoId] }
        if (existing != null) return@withContext existing.await()
        val job = kotlinx.coroutines.CompletableDeferred<StreamBundle>()
        val mine = synchronized(inFlightLock) {
            if (inFlight[videoId] == null) {
                @Suppress("UNCHECKED_CAST")
                inFlight[videoId] = job as kotlinx.coroutines.Deferred<StreamBundle>
                true
            } else false
        }
        if (!mine) return@withContext (synchronized(inFlightLock) { inFlight[videoId] }!!).await()
        try {
        var lastReason = "No playable stream"
        val clients = InnerTube.playerClients
        for (offset in clients.indices) {
            val index = (startClient + offset) % clients.size
            val client = clients[index]
            try {
                val resp = InnerTube.player(videoId, client)
                val status = resp.optJSONObject("playabilityStatus")
                val ok = status?.optString("status") == "OK"
                if (!ok) {
                    android.util.Log.w(
                        "WTStream",
                        "client ${client.name}/${client.version} refused $videoId: " +
                            "${status?.optString("status")} ${status?.optString("reason")}"
                    )
                    lastReason = status?.optString("reason")?.ifEmpty { null }
                        ?: status?.optString("status") ?: "Not playable"
                    continue
                }
                val bundle = buildBundle(videoId, resp, index, client.ua) ?: continue
                android.util.Log.i("WTStream", "resolved $videoId via ${client.name}/${client.version} (idx $index)")
                synchronized(cache) { cache[videoId] = bundle }
                job.complete(bundle)
                return@withContext bundle
            } catch (e: PlaybackBlockedException) {
                throw e
            } catch (e: Exception) {
                lastReason = e.message ?: "Request failed"
            }
        }
        val err = PlaybackBlockedException(lastReason)
        job.completeExceptionally(err)
        throw err
        } finally {
            synchronized(inFlightLock) { inFlight.remove(videoId) }
        }
    }

    fun invalidate(videoId: String) {
        synchronized(cache) { cache.remove(videoId) }
    }

    private fun buildBundle(videoId: String, resp: JSONObject, clientIndex: Int, streamUa: String): StreamBundle? {
        val streaming = resp.optJSONObject("streamingData") ?: return null
        val details = resp.optJSONObject("videoDetails")
        val title = details?.optString("title").orEmpty()
        val channel = details?.optString("author").orEmpty()
        val durationSec = details?.optLong("lengthSeconds", 0L) ?: 0L

        val choices = ArrayList<StreamChoice>()

        // muxed itag 18: 360p H.264+AAC in one progressive stream
        var muxedUrl: String? = null
        streaming.optJSONArray("formats")?.let { formats ->
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val url = f.optString("url")
                if (url.isEmpty()) continue
                val mime = f.optString("mimeType")
                if (f.optInt("itag") == 18 || (mime.contains("avc1") && mime.contains("mp4a"))) {
                    muxedUrl = url
                    break
                }
            }
        }

        val adaptive = streaming.optJSONArray("adaptiveFormats")
        val audioUrl = pickAudio(adaptive)
        val videoByHeight = pickVideos(adaptive)

        if (muxedUrl != null) {
            choices.add(StreamChoice(label = "360p", height = 360, muxedUrl = muxedUrl, videoUrl = null, audioUrl = null))
        }
        if (audioUrl != null) {
            for ((height, url) in videoByHeight) {
                if (height == 360 && muxedUrl != null) continue // muxed already covers 360p, cheaper
                choices.add(
                    StreamChoice(label = "${height}p", height = height, muxedUrl = null, videoUrl = url, audioUrl = audioUrl)
                )
            }
            choices.add(StreamChoice(label = "Audio only", height = 0, muxedUrl = null, videoUrl = null, audioUrl = audioUrl))
        }
        if (choices.isEmpty()) return null
        choices.sortByDescending { it.height }
        return StreamBundle(videoId, title, channel, durationSec, choices, clientIndex, streamUa)
    }

    /** height -> url for H.264 video-only streams, best (highest bitrate) per height, capped at 480. */
    private fun pickVideos(adaptive: JSONArray?): Map<Int, String> {
        adaptive ?: return emptyMap()
        val best = HashMap<Int, Pair<Long, String>>()
        for (i in 0 until adaptive.length()) {
            val f = adaptive.optJSONObject(i) ?: continue
            val mime = f.optString("mimeType")
            if (!mime.startsWith("video/mp4") || !mime.contains("avc1")) continue
            val url = f.optString("url")
            if (url.isEmpty()) continue
            val height = f.optInt("height")
            if (height !in listOf(144, 240, 360, 480)) continue
            val bitrate = f.optLong("bitrate")
            val cur = best[height]
            if (cur == null || bitrate > cur.first) best[height] = bitrate to url
        }
        return best.mapValues { it.value.second }
    }

    /** Device language -> audioIsDefault -> lowest bitrate (untagged formats compete on bitrate). */
    private fun pickAudio(adaptive: JSONArray?): String? {
        adaptive ?: return null
        data class A(val url: String, val bitrate: Long, val langMatch: Boolean, val isDefault: Boolean, val tagged: Boolean)
        val lang = Locale.getDefault().language
        val all = ArrayList<A>()
        for (i in 0 until adaptive.length()) {
            val f = adaptive.optJSONObject(i) ?: continue
            val mime = f.optString("mimeType")
            if (!mime.startsWith("audio/mp4") || !mime.contains("mp4a")) continue
            val url = f.optString("url")
            if (url.isEmpty()) continue
            // itags 599/600 are the "ultralow" AAC/Opus DRC renditions. They are the
            // lowest-bitrate mp4a entries, so a naive cheapest-wins pick lands on them,
            // and their googlevideo URLs 403 every chunk on this client (measured on
            // the watch 2026-08-18: itag 599 -> 16 consecutive 403s, playback dead).
            val itag = f.optInt("itag")
            if (itag == 599 || itag == 600) continue
            val track = f.optJSONObject("audioTrack")
            all.add(
                A(
                    url = url,
                    bitrate = f.optLong("bitrate"),
                    langMatch = track?.optString("id")?.startsWith(lang) == true,
                    isDefault = track?.optBoolean("audioIsDefault") == true,
                    tagged = track != null,
                )
            )
        }
        if (all.isEmpty()) return null
        all.firstOrNull { it.langMatch }?.let { return it.url }
        all.firstOrNull { it.isDefault }?.let { return it.url }
        return all.minByOrNull { it.bitrate }?.url
    }

    /** Choose the rendition for the current settings. */
    fun choose(bundle: StreamBundle, preferredHeight: Int, audioOnly: Boolean): StreamChoice {
        if (audioOnly) bundle.choices.firstOrNull { it.height == 0 }?.let { return it }
        val playable = bundle.choices.filter { it.height > 0 }
        if (playable.isEmpty()) return bundle.choices.first()
        if (preferredHeight == 0) {
            return playable.firstOrNull { it.muxedUrl != null }
                ?: playable.minByOrNull { kotlin.math.abs(it.height - 360) }!!
        }
        return playable.minByOrNull { kotlin.math.abs(it.height - preferredHeight) }!!
    }
}
