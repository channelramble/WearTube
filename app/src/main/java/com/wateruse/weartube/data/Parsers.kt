package com.wateruse.weartube.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * InnerTube response parsing.
 *
 * Modern responses use lockupViewModel; older shapes (videoRenderer / compactVideoRenderer /
 * channelRenderer / playlistRenderer) still appear in search. Both are handled.
 *
 * Rule from WristTube: classify lockup metadata parts by CONTENT (views / ago / name),
 * never by position — channel pages omit the uploader name entirely.
 */
object Parsers {

    // ---------- generic JSON walking (document order) ----------

    /** Depth-first, in-order collection of every JSONObject stored under any of [keys]. */
    fun collect(root: Any?, keys: Set<String>, out: MutableList<Pair<String, JSONObject>> = ArrayList()): List<Pair<String, JSONObject>> {
        when (root) {
            is JSONObject -> {
                for (k in root.keys()) {
                    val v = root.opt(k)
                    if (k in keys && v is JSONObject) out.add(k to v)
                    collect(v, keys, out)
                }
            }
            is JSONArray -> for (i in 0 until root.length()) collect(root.opt(i), keys, out)
        }
        return out
    }

    fun firstObject(root: Any?, key: String): JSONObject? =
        collect(root, setOf(key)).firstOrNull()?.second

    /** First continuation token in document order (used for "load more"). */
    fun continuationToken(root: Any?): String? {
        val found = ArrayList<String>()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val cc = node.optJSONObject("continuationCommand")
                    val token = cc?.optString("token")
                    if (!token.isNullOrEmpty()) found.add(token)
                    for (k in node.keys()) walk(node.opt(k))
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
            }
        }
        walk(root)
        return found.lastOrNull()
    }

    // ---------- text helpers ----------

    fun text(node: JSONObject?): String {
        node ?: return ""
        node.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        node.optString("content").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = node.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        return sb.toString()
    }

    private fun fixUrl(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        else -> u
    }

    /** Pick a thumbnail around 320-500px wide from a sources/thumbnails array. */
    private fun pickThumb(arr: JSONArray?): String {
        arr ?: return ""
        var best = ""
        var bestW = Int.MAX_VALUE
        var fallback = ""
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isEmpty()) continue
            val w = o.optInt("width", 0)
            fallback = url
            if (w in 240..640 && w < bestW) { best = url; bestW = w }
        }
        return fixUrl(best.ifEmpty { fallback })
    }

    private fun thumbFrom(node: JSONObject?): String {
        node ?: return ""
        firstArray(node, "sources")?.let { return pickThumb(it) }
        firstArray(node, "thumbnails")?.let { return pickThumb(it) }
        return ""
    }

    private fun firstArray(root: Any?, key: String): JSONArray? {
        when (root) {
            is JSONObject -> {
                for (k in root.keys()) {
                    val v = root.opt(k)
                    if (k == key && v is JSONArray) return v
                    firstArray(v, key)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until root.length()) firstArray(root.opt(i), key)?.let { return it }
        }
        return null
    }

    private val viewsRe = Regex("""^[\d.,]+[KMB]?\s+\S*view""", RegexOption.IGNORE_CASE)
    private val agoRe = Regex("""ago$""", RegexOption.IGNORE_CASE)
    private val watchingRe = Regex("""watching""", RegexOption.IGNORE_CASE)

    // ---------- lockupViewModel ----------

    /** Parse a lockupViewModel into a Video, or a Playlist when contentType says so. */
    fun itemFromLockup(lockup: JSONObject): SearchItem? {
        val contentId = lockup.optString("contentId")
        if (contentId.isEmpty()) return null
        val contentType = lockup.optString("contentType")

        val meta = firstObject(lockup, "lockupMetadataViewModel")
        val title = text(meta?.optJSONObject("title"))

        // thumbnail
        val image = lockup.optJSONObject("contentImage")
        val thumb = thumbFrom(image)

        // duration overlay + watched progress
        var duration = ""
        var watched = 0
        var live = false
        collect(image, setOf("thumbnailOverlayBadgeViewModel", "thumbnailBadgeViewModel")).forEach { (_, o) ->
            val t = text(o.optJSONObject("text"))
            if (t.isNotEmpty() && duration.isEmpty() && t.contains(":")) duration = t
            if (o.optString("badgeStyle").contains("LIVE", ignoreCase = true) ||
                t.equals("LIVE", ignoreCase = true)
            ) live = true
        }
        firstObject(image, "thumbnailOverlayProgressBarViewModel")?.let {
            watched = it.optInt("startPercent", 0)
        }
        firstObject(image, "progressBarViewModel")?.let {
            val p = it.optInt("percentWatched", 0)
            if (p > 0) watched = p
        }

        // metadata rows -> classify by content
        var channel = ""
        var views = ""
        var ago = ""
        var channelId = ""
        meta?.let { m ->
            val parts = ArrayList<JSONObject>()
            fun grabParts(node: Any?) {
                when (node) {
                    is JSONObject -> {
                        val arr = node.optJSONArray("metadataParts")
                        if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(parts::add)
                        for (k in node.keys()) grabParts(node.opt(k))
                    }
                    is JSONArray -> for (i in 0 until node.length()) grabParts(node.opt(i))
                }
            }
            grabParts(m.optJSONObject("metadata"))
            for (p in parts) {
                val t = text(p.optJSONObject("text"))
                if (t.isEmpty()) continue
                when {
                    viewsRe.containsMatchIn(t) || watchingRe.containsMatchIn(t) -> if (views.isEmpty()) views = t
                    agoRe.containsMatchIn(t) -> if (ago.isEmpty()) ago = t
                    else -> if (channel.isEmpty()) channel = t
                }
            }
            // channel browseId if present anywhere in metadata endpoints
            channelId = findChannelId(m).orEmpty()
        }
        if (channelId.isEmpty()) channelId = findChannelId(lockup).orEmpty()

        if (contentType.contains("PLAYLIST")) {
            return SearchItem.P(
                Playlist(id = contentId, title = title, channel = channel, count = views, thumb = thumb)
            )
        }
        if (contentType.contains("CHANNEL")) {
            return SearchItem.C(Channel(id = contentId, title = title, subs = views, thumb = thumb))
        }
        return SearchItem.V(
            Video(
                id = contentId, title = title, channel = channel, channelId = channelId,
                views = views, published = ago, duration = duration, thumb = thumb,
                watchedPercent = watched, isLive = live,
            )
        )
    }

    private fun findChannelId(root: Any?): String? {
        val eps = collect(root, setOf("browseEndpoint"))
        for ((_, ep) in eps) {
            val id = ep.optString("browseId")
            if (id.startsWith("UC")) return id
        }
        return null
    }

    // ---------- classic renderers ----------

    fun videoFromRenderer(r: JSONObject): Video? {
        val id = r.optString("videoId")
        if (id.isEmpty()) return null
        val title = text(r.optJSONObject("title")).ifEmpty { text(r.optJSONObject("headline")) }
        val owner = r.optJSONObject("ownerText") ?: r.optJSONObject("longBylineText") ?: r.optJSONObject("shortBylineText")
        val channel = text(owner)
        val channelId = findChannelId(owner).orEmpty()
        var live = false
        collect(r.optJSONArray("badges"), setOf("metadataBadgeRenderer")).forEach { (_, b) ->
            if (b.optString("style").contains("LIVE")) live = true
        }
        var watched = 0
        firstObject(r, "thumbnailOverlayResumePlaybackRenderer")?.let {
            watched = it.optInt("percentDurationWatched", 0)
        }
        return Video(
            id = id,
            title = title,
            channel = channel,
            channelId = channelId,
            views = text(r.optJSONObject("shortViewCountText")).ifEmpty { text(r.optJSONObject("viewCountText")) },
            published = text(r.optJSONObject("publishedTimeText")),
            duration = text(r.optJSONObject("lengthText")),
            thumb = thumbFrom(r.optJSONObject("thumbnail")),
            watchedPercent = watched,
            isLive = live,
        )
    }

    fun channelFromRenderer(r: JSONObject): Channel? {
        val id = r.optString("channelId")
        if (id.isEmpty()) return null
        return Channel(
            id = id,
            title = text(r.optJSONObject("title")),
            subs = text(r.optJSONObject("videoCountText")).ifEmpty { text(r.optJSONObject("subscriberCountText")) },
            thumb = thumbFrom(r.optJSONObject("thumbnail")),
        )
    }

    fun playlistFromRenderer(r: JSONObject): Playlist? {
        val id = r.optString("playlistId")
        if (id.isEmpty()) return null
        return Playlist(
            id = id,
            title = text(r.optJSONObject("title")),
            channel = text(r.optJSONObject("shortBylineText")),
            count = text(r.optJSONObject("videoCountText")).ifEmpty { r.optString("videoCount") },
            thumb = thumbFrom(r),
        )
    }

    // ---------- TVHTML5 tileRenderer ----------

    /**
     * TV client feeds (FEwhat_to_watch / FEsubscriptions via TVHTML5) use an entirely
     * different item shape from WEB: tileRenderer, whose metadata lives in
     * tileMetadataRenderer.lines[].lineRenderer.items[].lineItemRenderer.text.
     * Without this, an authenticated TV response parses to zero videos and the app
     * silently falls back to the local feed.
     */
    fun videoFromTile(tile: JSONObject): Video? {
        val id = tile.optString("contentId").ifEmpty {
            firstObject(tile.optJSONObject("onSelectCommand"), "watchEndpoint")?.optString("videoId").orEmpty()
        }
        if (id.isEmpty() || id.length != 11) return null

        val meta = firstObject(tile, "tileMetadataRenderer")
        val title = text(meta?.optJSONObject("title"))
        if (title.isEmpty()) return null

        // metadata lines: channel name / views / age, in no guaranteed order
        val parts = ArrayList<String>()
        meta?.optJSONArray("lines")?.let { lines ->
            for (i in 0 until lines.length()) {
                val items = firstObject(lines.optJSONObject(i), "lineRenderer")?.optJSONArray("items") ?: continue
                for (j in 0 until items.length()) {
                    val li = items.optJSONObject(j)?.optJSONObject("lineItemRenderer") ?: continue
                    val t = text(li.optJSONObject("text"))
                    if (t.isNotEmpty() && t != "\u2022") parts.add(t)
                }
            }
        }
        var channel = ""
        var views = ""
        var published = ""
        for (part in parts) {
            when {
                viewsRe.containsMatchIn(part) || watchingRe.containsMatchIn(part) ->
                    if (views.isEmpty()) views = part
                agoRe.containsMatchIn(part) -> if (published.isEmpty()) published = part
                else -> if (channel.isEmpty()) channel = part
            }
        }

        val header = firstObject(tile, "tileHeaderRenderer")
        val thumb = thumbFrom(header).ifEmpty { thumbFrom(tile) }
        val duration = firstObject(tile, "thumbnailOverlayTimeStatusRenderer")
            ?.let { text(it.optJSONObject("text")) }.orEmpty()

        return Video(
            id = id,
            title = title,
            channel = channel,
            views = views,
            published = published,
            duration = duration,
            thumb = thumb,
        )
    }

    /**
     * Channel tile from the TV client's FEchannels (subscription list). Same
     * tileRenderer shape as videos, but contentId is a UC… channel id and the
     * metadata lines carry the @handle / subscriber count.
     */
    fun channelFromTile(tile: JSONObject): Channel? {
        val id = tile.optString("contentId").ifEmpty {
            firstObject(tile.optJSONObject("onSelectCommand"), "browseEndpoint")?.optString("browseId").orEmpty()
        }
        if (!id.startsWith("UC") || id.length != 24) return null
        val meta = firstObject(tile, "tileMetadataRenderer")
        val title = text(meta?.optJSONObject("title"))
        if (title.isEmpty()) return null
        var subs = ""
        meta?.optJSONArray("lines")?.let { lines ->
            for (i in 0 until lines.length()) {
                val items = firstObject(lines.optJSONObject(i), "lineRenderer")?.optJSONArray("items") ?: continue
                for (j in 0 until items.length()) {
                    val t = text(items.optJSONObject(j)?.optJSONObject("lineItemRenderer")?.optJSONObject("text"))
                    if (t.contains("subscriber", ignoreCase = true) && subs.isEmpty()) subs = t
                }
            }
        }
        return Channel(id = id, title = title, subs = subs, thumb = thumbFrom(firstObject(tile, "tileHeaderRenderer")))
    }

    /** All channel tiles of a TV browse response, deduped, document order. */
    fun channelTiles(response: JSONObject): List<Channel> {
        val out = ArrayList<Channel>()
        val seen = HashSet<String>()
        for ((_, obj) in collect(response, setOf("tileRenderer"))) {
            val ch = channelFromTile(obj) ?: continue
            if (seen.add(ch.id)) out.add(ch)
        }
        return out
    }

    /**
     * Playlist tile from the TV client (FEplaylist_aggregation). contentType is
     * TILE_CONTENT_TYPE_PLAYLIST; contentId is a PL…/LL/WL id ("LL" = Liked videos,
     * "WL" = Watch Later, both valid and short — do not length-check playlist ids).
     */
    fun playlistFromTile(tile: JSONObject): Playlist? {
        if (!tile.optString("contentType").contains("PLAYLIST")) return null
        val id = tile.optString("contentId")
        if (id.isEmpty()) return null
        val meta = firstObject(tile, "tileMetadataRenderer")
        val title = text(meta?.optJSONObject("title"))
        if (title.isEmpty()) return null
        var count = ""
        meta?.optJSONArray("lines")?.let { lines ->
            for (i in 0 until lines.length()) {
                val items = firstObject(lines.optJSONObject(i), "lineRenderer")?.optJSONArray("items") ?: continue
                for (j in 0 until items.length()) {
                    val t = text(items.optJSONObject(j)?.optJSONObject("lineItemRenderer")?.optJSONObject("text"))
                    if (t.contains("video", ignoreCase = true) && count.isEmpty()) count = t
                }
            }
        }
        return Playlist(id = id, title = title, count = count, thumb = thumbFrom(firstObject(tile, "tileHeaderRenderer")))
    }

    /** All playlist tiles of a TV browse response, deduped, document order. */
    fun playlistTiles(response: JSONObject): List<Playlist> {
        val out = ArrayList<Playlist>()
        val seen = HashSet<String>()
        for ((_, obj) in collect(response, setOf("tileRenderer"))) {
            val pl = playlistFromTile(obj) ?: continue
            if (seen.add(pl.id)) out.add(pl)
        }
        return out
    }

    /**
     * Title + channel for a video from a `next` response. Used when a screen is
     * reached without cached metadata (deep link, or the app was killed and the
     * in-memory NavCache is gone) — otherwise Watch Later would store a blank row.
     */
    fun videoMetaFromNext(response: JSONObject): Pair<String, String> {
        val title = firstObject(response, "videoPrimaryInfoRenderer")
            ?.let { text(it.optJSONObject("title")) }.orEmpty()
        val owner = firstObject(response, "videoOwnerRenderer")
            ?.let { text(it.optJSONObject("title")) }.orEmpty()
        return title to owner
    }

    // ---------- page-level parsers ----------

    /** All video/channel/playlist items of a response, document order, deduped. */
    fun searchItems(response: JSONObject): List<SearchItem> {
        val out = ArrayList<SearchItem>()
        val seen = HashSet<String>()
        val found = collect(
            response,
            setOf(
                "lockupViewModel", "videoRenderer", "compactVideoRenderer",
                "gridVideoRenderer", "tileRenderer",
                "channelRenderer", "playlistRenderer",
            )
        )
        for ((key, obj) in found) {
            val item: SearchItem? = when (key) {
                "lockupViewModel" -> itemFromLockup(obj)
                "videoRenderer", "compactVideoRenderer", "gridVideoRenderer" ->
                    videoFromRenderer(obj)?.let { SearchItem.V(it) }
                "tileRenderer" -> videoFromTile(obj)?.let { SearchItem.V(it) }
                "channelRenderer" -> channelFromRenderer(obj)?.let { SearchItem.C(it) }
                "playlistRenderer" -> playlistFromRenderer(obj)?.let { SearchItem.P(it) }
                else -> null
            }
            item ?: continue
            val dedupeKey = when (item) {
                is SearchItem.V -> "v:${item.video.id}"
                is SearchItem.C -> "c:${item.channel.id}"
                is SearchItem.P -> "p:${item.playlist.id}"
            }
            if (seen.add(dedupeKey)) out.add(item)
        }
        return out
    }

    fun videos(response: JSONObject): List<Video> =
        searchItems(response).mapNotNull { (it as? SearchItem.V)?.video }

    /** Channel header from a browse UC... response (new + legacy shapes). */
    fun channelHeader(response: JSONObject, channelId: String): Channel {
        firstObject(response, "c4TabbedHeaderRenderer")?.let { h ->
            return Channel(
                id = channelId,
                title = text(h.optJSONObject("title")).ifEmpty { h.optString("title") },
                subs = text(h.optJSONObject("subscriberCountText")),
                thumb = thumbFrom(h.optJSONObject("avatar")),
            )
        }
        val header = response.optJSONObject("header")
        val titleFromPage = firstObject(header, "pageHeaderRenderer")?.optString("pageTitle").orEmpty()
        var subs = ""
        var thumb = ""
        firstObject(header, "pageHeaderViewModel")?.let { vm ->
            thumb = thumbFrom(firstObject(vm, "avatarViewModel"))
            // metadata rows: handle + subscriber count live in contentMetadataViewModel
            val partsTexts = ArrayList<String>()
            fun grab(node: Any?) {
                when (node) {
                    is JSONObject -> {
                        node.optJSONArray("metadataParts")?.let { arr ->
                            for (i in 0 until arr.length()) {
                                val t = text(arr.optJSONObject(i)?.optJSONObject("text"))
                                if (t.isNotEmpty()) partsTexts.add(t)
                            }
                        }
                        for (k in node.keys()) grab(node.opt(k))
                    }
                    is JSONArray -> for (i in 0 until node.length()) grab(node.opt(i))
                }
            }
            grab(vm.optJSONObject("metadata"))
            subs = partsTexts.firstOrNull { it.contains("subscriber", ignoreCase = true) }.orEmpty()
        }
        return Channel(id = channelId, title = titleFromPage, subs = subs, thumb = thumb)
    }

    fun playlistTitle(response: JSONObject): String {
        firstObject(response, "pageHeaderRenderer")?.optString("pageTitle")?.takeIf { it.isNotEmpty() }?.let { return it }
        firstObject(response, "playlistHeaderRenderer")?.let { return text(it.optJSONObject("title")) }
        return ""
    }

    // ---------- comments ----------

    /** Token for the comments section from a `next` response, or null when comments are off. */
    fun commentsToken(nextResponse: JSONObject): String? {
        val sections = collect(nextResponse, setOf("itemSectionRenderer"))
        for ((_, s) in sections) {
            if (s.optString("sectionIdentifier") == "comment-item-section") {
                return continuationTokenIn(s)
            }
        }
        return null
    }

    private fun continuationTokenIn(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("continuationCommand")?.optString("token")
                    ?.takeIf { it.isNotEmpty() }?.let { return it }
                for (k in node.keys()) continuationTokenIn(node.opt(k))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) continuationTokenIn(node.opt(i))?.let { return it }
        }
        return null
    }

    /**
     * Comments arrive split: order in commentThreadRenderer -> commentViewModel {commentKey},
     * content in frameworkUpdates ... commentEntityPayload keyed by that key.
     */
    fun comments(response: JSONObject): Page<Comment> {
        val payloads = HashMap<String, JSONObject>()
        collect(response, setOf("commentEntityPayload")).forEach { (_, p) ->
            val key = p.optString("key")
            if (key.isNotEmpty()) payloads[key] = p
        }
        val out = ArrayList<Comment>()
        val vms = collect(response, setOf("commentViewModel"))
        val seenKeys = HashSet<String>()
        for ((_, vmWrap) in vms) {
            // commentViewModel sometimes nests one level: {commentViewModel:{...}}
            val vm = vmWrap.optJSONObject("commentViewModel") ?: vmWrap
            val key = vm.optString("commentKey")
            if (key.isEmpty() || !seenKeys.add(key)) continue
            val p = payloads[key] ?: continue
            val props = p.optJSONObject("properties")
            val author = p.optJSONObject("author")
            val toolbar = p.optJSONObject("toolbar")
            out.add(
                Comment(
                    author = author?.optString("displayName").orEmpty(),
                    text = text(props?.optJSONObject("content")),
                    likes = toolbar?.optString("likeCountNotliked").orEmpty(),
                    published = props?.optString("publishedTime").orEmpty(),
                    avatar = author?.optString("avatarThumbnailUrl").orEmpty(),
                    replyCount = toolbar?.optString("replyCount").orEmpty(),
                )
            )
        }
        // fallback: legacy commentRenderer
        if (out.isEmpty()) {
            collect(response, setOf("commentRenderer")).forEach { (_, r) ->
                out.add(
                    Comment(
                        author = text(r.optJSONObject("authorText")),
                        text = text(r.optJSONObject("contentText")),
                        likes = text(r.optJSONObject("voteCount")),
                        published = text(r.optJSONObject("publishedTimeText")),
                        avatar = thumbFrom(r.optJSONObject("authorThumbnail")),
                    )
                )
            }
        }
        return Page(out, continuationToken(response))
    }
}
