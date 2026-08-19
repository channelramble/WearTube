package com.wateruse.weartube.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.wateruse.weartube.data.InnerTube
import com.wateruse.weartube.data.Net
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/**
 * DataSource for googlevideo streams, with per-URL budget rotation.
 *
 * googlevideo rules, all measured (WristTube, then re-measured here):
 *  - Every request must carry a bounded Range. No Range, or an open-ended
 *    `bytes=N-`, is refused outright.
 *  - Each URL serves only ~0.5-2MB in total before answering 403 to everything.
 *    That budget is per URL, NOT per request, so shrinking ranges does not help
 *    (proven here: refusals continued all the way down to a 32KB floor). The URL
 *    must be re-issued from the player API — see [StreamUrls].
 *  - The stream User-Agent must match the InnerTube client that issued the URL.
 *
 * ExoPlayer is fed a stable `wt://stream/<id>/<kind>/<height>` URI so the media
 * item never holds a URL that can go stale; the real URL is resolved per read
 * and swapped underneath. A refusal is handled here by rotating and retrying —
 * ExoPlayer only ever sees an error once rotation itself has genuinely failed,
 * because a surfaced error tears down the track.
 */
@OptIn(UnstableApi::class)
class RangedDataSource : BaseDataSource(/* isNetwork = */ true) {

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = RangedDataSource()
    }

    companion object {
        private const val READ_SIZE = 256L shl 10
        private const val MIN_READ = 64L shl 10
        private const val MAX_ATTEMPTS = 5

        /**
         * UA for stream requests; must match the InnerTube client that issued the
         * current URLs (googlevideo cross-checks). Set per resolved bundle.
         */
        @Volatile
        var streamUserAgent: String = InnerTube.playerClients.first().ua
    }

    private var spec: DataSpec? = null
    private var body: InputStream? = null
    private var response: Response? = null

    private var videoId: String = ""
    private var kind: StreamUrls.Kind = StreamUrls.Kind.PROGRESSIVE
    private var height: Int = 0
    private var directUrl: String? = null   // non-wt:// URIs (unused today, kept honest)

    private var position = 0L
    private var limit = C.LENGTH_UNSET.toLong()
    private var total = C.LENGTH_UNSET.toLong()
    private var chunkRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        position = dataSpec.position
        transferInitializing(dataSpec)

        val parsed = StreamUrls.parse(dataSpec.uri.toString())
        if (parsed != null) {
            videoId = parsed.first
            kind = parsed.second
            height = parsed.third
            directUrl = null
        } else {
            directUrl = dataSpec.uri.toString()
        }

        openChunk()

        limit = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.position + dataSpec.length
            total != C.LENGTH_UNSET.toLong() -> total
            else -> C.LENGTH_UNSET.toLong()
        }

        opened = true
        transferStarted(dataSpec)
        return if (limit == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else limit - dataSpec.position
    }

    /** Current URL for this track, rotating first if the budget is spent. */
    private fun resolveUrl(want: Long): String? =
        directUrl ?: StreamUrls.urlForRead(videoId, kind, height, want)

    private fun openChunk() {
        closeStream()
        val dataSpec = spec ?: throw IOException("not opened")
        val hardEnd = when {
            limit != C.LENGTH_UNSET.toLong() -> limit
            total != C.LENGTH_UNSET.toLong() -> total
            else -> Long.MAX_VALUE
        }

        var attempt = 0
        while (true) {
            val want = minOf(READ_SIZE, maxOf(MIN_READ, READ_SIZE))
            val end = minOf(position + want, hardEnd) - 1
            if (end < position) { chunkRemaining = 0; return }

            val url = resolveUrl(end - position + 1)
                ?: throw IOException("no stream URL available")

            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=$position-$end")
                .header("User-Agent", streamUserAgent)
                .header("Accept", "*/*")
                .build()

            val resp = try {
                Net.client.newCall(req).execute()
            } catch (e: IOException) {
                attempt++
                if (attempt >= MAX_ATTEMPTS) throw e
                Thread.sleep(backoffMs(attempt))
                continue
            }

            if (resp.code == 200 || resp.code == 206) {
                finishChunk(resp)
                return
            }

            val code = resp.code
            resp.close()
            attempt++
            // 403 = this URL is spent. Rotate and retry; only give up (and let
            // ExoPlayer tear the track down) once rotation itself stops working.
            if (code == 403 && attempt < MAX_ATTEMPTS) {
                val freshlyRotated = StreamUrls.bytesServedOnCurrentUrl(videoId, kind, height) == 0L
                val rotated = StreamUrls.onRefused(videoId, kind, height)
                android.util.Log.i(
                    "WTStream",
                    "403 on $kind ${height}p, rotated=$rotated fresh=$freshlyRotated (attempt $attempt)"
                )
                if (freshlyRotated) {
                    // A URL that refuses before serving a single byte is the
                    // rate-limit signature; rotating again just burns player calls.
                    StreamUrls.noteRateLimited()
                    if (StreamUrls.looksRateLimited()) {
                        throw com.wateruse.weartube.data.PlaybackBlockedException(
                            "YouTube is rate-limiting this network. Try again in a while."
                        )
                    }
                }
                if (!rotated) Thread.sleep(backoffMs(attempt))
                continue
            }
            if (attempt < MAX_ATTEMPTS && code >= 500) {
                Thread.sleep(backoffMs(attempt))
                continue
            }
            android.util.Log.w("WTStream", "chunk $code range=$position-$end kind=$kind")
            throw HttpDataSource.InvalidResponseCodeException(
                code, null, null, emptyMap(), dataSpec, ByteArray(0)
            )
        }
    }

    /** Steep backoff: hammering is what trips the bot checks in the first place. */
    private fun backoffMs(attempt: Int): Long = minOf(4_000L, 250L * (1L shl attempt))

    private fun finishChunk(resp: Response) {
        resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { total = it }
        if (resp.code == 200) {
            resp.body?.contentLength()?.takeIf { it > 0 }
                ?.let { if (total == C.LENGTH_UNSET.toLong()) total = position + it }
        }
        response = resp
        body = resp.body?.byteStream() ?: throw IOException("empty body")
        chunkRemaining = resp.body!!.contentLength().let { if (it >= 0) it else READ_SIZE }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val remainingOverall = if (limit == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else limit - position
        if (remainingOverall <= 0) return C.RESULT_END_OF_INPUT

        if (chunkRemaining <= 0) {
            if (total != C.LENGTH_UNSET.toLong() && position >= total) return C.RESULT_END_OF_INPUT
            openChunk()
            if (chunkRemaining <= 0) return C.RESULT_END_OF_INPUT
        }

        val want = minOf(length.toLong(), chunkRemaining, remainingOverall).toInt()
        val n = try {
            body!!.read(buffer, offset, want)
        } catch (e: IOException) {
            // Mid-chunk connection death: re-open at the current offset rather
            // than failing the track.
            chunkRemaining = 0
            openChunk()
            if (chunkRemaining <= 0) return C.RESULT_END_OF_INPUT
            body!!.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
        }
        if (n == -1) {
            chunkRemaining = 0
            return read(buffer, offset, length)
        }
        position += n
        chunkRemaining -= n
        if (directUrl == null) StreamUrls.onRead(videoId, kind, height, n.toLong())
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = spec?.uri

    private fun closeStream() {
        try { body?.close() } catch (_: IOException) {}
        try { response?.close() } catch (_: Exception) {}
        body = null
        response = null
        chunkRemaining = 0
    }

    override fun close() {
        closeStream()
        if (opened) {
            opened = false
            transferEnded()
        }
        spec = null
        total = C.LENGTH_UNSET.toLong()
        limit = C.LENGTH_UNSET.toLong()
    }
}
