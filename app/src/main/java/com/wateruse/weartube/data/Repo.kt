package com.wateruse.weartube.data

import com.wateruse.weartube.data.Auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Feed assembly on top of InnerTube + Store.
 *
 * WristTube facts honored here:
 *  - Channel videos MUST come from `browse` (params EgZ2aWRlb3M%3D = Videos tab),
 *    never the RSS feed — Google rate-limits RSS per IP until it 404s.
 *  - Logged-out FEwhat_to_watch / FEtrending return nothing, so Home is built from
 *    the subscription feed plus `next` (related) on recently watched videos.
 *  - Playlists browse with browseId "VL" + playlistId; items are ordinary lockups.
 *  - Feed fetches are concurrency-limited — parallel storms trip request timeouts.
 */
object Repo {

    private const val CHANNEL_VIDEOS_PARAMS = "EgZ2aWRlb3M%3D"

    // ---- search ----

    suspend fun search(query: String): Page<SearchItem> = withContext(Dispatchers.IO) {
        val resp = InnerTube.search(query)
        Page(Parsers.searchItems(resp), Parsers.continuationToken(resp))
    }

    suspend fun searchMore(token: String): Page<SearchItem> = withContext(Dispatchers.IO) {
        val resp = InnerTube.searchContinuation(token)
        Page(Parsers.searchItems(resp), Parsers.continuationToken(resp))
    }

    suspend fun suggestions(q: String): List<String> = withContext(Dispatchers.IO) {
        try { InnerTube.suggestions(q) } catch (_: Exception) { emptyList() }
    }

    // ---- channel ----

    suspend fun channel(channelId: String): Pair<Channel, Page<Video>> = withContext(Dispatchers.IO) {
        val resp = InnerTube.browse(channelId, CHANNEL_VIDEOS_PARAMS)
        val header = Parsers.channelHeader(resp, channelId)
        Pair(header, Page(Parsers.videos(resp), Parsers.continuationToken(resp)))
    }

    suspend fun channelMore(token: String): Page<Video> = withContext(Dispatchers.IO) {
        val resp = InnerTube.browseContinuation(token)
        Page(Parsers.videos(resp), Parsers.continuationToken(resp))
    }

    // ---- playlist ----

    suspend fun playlist(playlistId: String): Pair<String, Page<Video>> = withContext(Dispatchers.IO) {
        val resp = InnerTube.browse("VL$playlistId")
        Pair(Parsers.playlistTitle(resp), Page(Parsers.videos(resp), Parsers.continuationToken(resp)))
    }

    // ---- related / comments ----

    suspend fun related(videoId: String): List<Video> = withContext(Dispatchers.IO) {
        val resp = InnerTube.next(videoId)
        Parsers.videos(resp).filterNot { it.id == videoId }
    }

    suspend fun commentsFirstPage(videoId: String): Page<Comment>? = withContext(Dispatchers.IO) {
        val nextResp = InnerTube.next(videoId)
        val token = Parsers.commentsToken(nextResp) ?: return@withContext null
        Parsers.comments(InnerTube.nextContinuation(token))
    }

    suspend fun commentsMore(token: String): Page<Comment> = withContext(Dispatchers.IO) {
        Parsers.comments(InnerTube.nextContinuation(token))
    }

    /** Title + channel for [videoId] when no cached metadata is available. */
    suspend fun videoMeta(videoId: String): Pair<String, String> = withContext(Dispatchers.IO) {
        try { Parsers.videoMetaFromNext(InnerTube.next(videoId)) } catch (_: Exception) { "" to "" }
    }

    // ---- home ----

    data class HomeFeed(
        val subscriptionVideos: List<Video>,
        val forYou: List<Video>,
    )

    /**
     * Subscriptions: the account's channels (Data API) when signed in, else local ones —
     * uploads fetched per channel via anonymous browse, interleaved round-robin so one
     * prolific channel can't flood the feed.
     * For You: related videos of up to 3 recently watched videos, deduped.
     */
    suspend fun homeFeed(): HomeFeed = withContext(Dispatchers.IO) {
        // Signed in to the TV client: serve the account's REAL feeds.
        if (TvAuth.isSignedIn && TvAuth.refreshIfNeeded() != null) {
            val recs = try { Parsers.videos(InnerTube.tvBrowse("FEwhat_to_watch")) } catch (e: Exception) {
                android.util.Log.w("WTAuth", "FEwhat_to_watch failed: ${e.message}"); emptyList()
            }
            val subsFeed = try { Parsers.videos(InnerTube.tvBrowse("FEsubscriptions")) } catch (e: Exception) {
                android.util.Log.w("WTAuth", "FEsubscriptions failed: ${e.message}"); emptyList()
            }
            android.util.Log.i("WTAuth", "TV feeds: recs=${recs.size} subs=${subsFeed.size}")
            if (recs.isNotEmpty() || subsFeed.isNotEmpty()) {
                val seen = HashSet<String>()
                subsFeed.forEach { seen.add(it.id) }
                return@withContext HomeFeed(subsFeed, recs.filter { seen.add(it.id) })
            }
            // both empty -> fall through to the local strategy below
        }
        coroutineScope {
            val accountChannels = if (Auth.token != null) GoogleApi.mySubscriptions() else emptyList()
            val subs = accountChannels.ifEmpty { Store.subscriptions() }.take(20)
            val gate = Semaphore(4)
            val perChannel = subs.map { ch ->
                async {
                    gate.withPermit {
                        try {
                            Parsers.videos(InnerTube.browse(ch.id, CHANNEL_VIDEOS_PARAMS)).take(5)
                        } catch (_: Exception) { emptyList() }
                    }
                }
            }.map { it.await() }

            val subVideos = interleave(perChannel)

            val recents = Store.history().take(3)
            val relatedLists = recents.map { v ->
                async {
                    gate.withPermit {
                        try { related(v.id).take(10) } catch (_: Exception) { emptyList() }
                    }
                }
            }.map { it.await() }
            val seen = HashSet<String>()
            subVideos.forEach { seen.add(it.id) }
            Store.history().take(50).forEach { seen.add(it.id) }
            val forYou = interleave(relatedLists).filter { seen.add(it.id) }

            HomeFeed(subVideos, forYou)
        }
    }

    /**
     * Channel ids the signed-in account follows. Uses FEchannels (the subscription
     * LIST) — NOT FEsubscriptions, which is the recent-uploads VIDEO feed and omits
     * subscribed channels that haven't posted lately.
     */
    private var accountSubIds: Set<String> = emptySet()
    private var accountSubIdsAt: Long = 0

    suspend fun accountSubscribed(channelId: String): Boolean = withContext(Dispatchers.IO) {
        if (!TvAuth.isSignedIn) return@withContext false
        val fresh = System.currentTimeMillis() - accountSubIdsAt < 5 * 60_000
        if (!fresh) {
            accountSubIds = try {
                val raw = InnerTube.tvBrowse("FEchannels").toString()
                Regex("\"browseId\":\"(UC[A-Za-z0-9_-]{22})\"").findAll(raw).map { it.groupValues[1] }.toSet()
            } catch (_: Exception) { accountSubIds }
            accountSubIdsAt = System.currentTimeMillis()
        }
        channelId in accountSubIds
    }

    /** The signed-in account's subscribed channels (TV client, FEchannels). */
    suspend fun accountChannels(): List<Channel> = withContext(Dispatchers.IO) {
        if (!TvAuth.isSignedIn || TvAuth.refreshIfNeeded() == null) return@withContext emptyList()
        try {
            val list = Parsers.channelTiles(InnerTube.tvBrowse("FEchannels"))
            android.util.Log.i("WTAuth", "account channels: ${list.size}")
            list
        } catch (e: Exception) {
            android.util.Log.w("WTAuth", "FEchannels failed: ${e.message}")
            emptyList()
        }
    }

    /** The signed-in account's playlists (TV client). Includes Liked videos / Watch Later. */
    suspend fun accountPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        if (!TvAuth.isSignedIn || TvAuth.refreshIfNeeded() == null) return@withContext emptyList()
        try {
            val list = Parsers.playlistTiles(InnerTube.tvBrowse("FEplaylist_aggregation"))
            android.util.Log.i("WTAuth", "account playlists: ${list.size}")
            list
        } catch (e: Exception) {
            android.util.Log.w("WTAuth", "FEplaylist_aggregation failed: ${e.message}")
            emptyList()
        }
    }

    private fun interleave(lists: List<List<Video>>): List<Video> {
        val out = ArrayList<Video>()
        val seen = HashSet<String>()
        var i = 0
        while (true) {
            var any = false
            for (l in lists) {
                if (i < l.size) {
                    any = true
                    if (seen.add(l[i].id)) out.add(l[i])
                }
            }
            if (!any) break
            i++
        }
        return out
    }
}
