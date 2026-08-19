package com.wateruse.weartube.data

import org.json.JSONObject

data class Video(
    val id: String,
    val title: String,
    val channel: String = "",
    val channelId: String = "",
    val views: String = "",
    val published: String = "",
    val duration: String = "",
    val thumb: String = "",
    val watchedPercent: Int = 0,
    val isLive: Boolean = false,
) {
    /** Thumbnails are derivable from the id, so a card is never blank. */
    val thumbOrFallback: String
        get() = thumb.ifEmpty { "https://i.ytimg.com/vi/$id/mqdefault.jpg" }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("channel", channel)
        .put("channelId", channelId).put("views", views).put("published", published)
        .put("duration", duration).put("thumb", thumb)

    companion object {
        fun fromJson(o: JSONObject) = Video(
            id = o.optString("id"),
            title = o.optString("title"),
            channel = o.optString("channel"),
            channelId = o.optString("channelId"),
            views = o.optString("views"),
            published = o.optString("published"),
            duration = o.optString("duration"),
            thumb = o.optString("thumb"),
        )
    }
}

data class Channel(
    val id: String,
    val title: String,
    val subs: String = "",
    val thumb: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("subs", subs).put("thumb", thumb)

    companion object {
        fun fromJson(o: JSONObject) = Channel(
            id = o.optString("id"),
            title = o.optString("title"),
            subs = o.optString("subs"),
            thumb = o.optString("thumb"),
        )
    }
}

data class Playlist(
    val id: String,
    val title: String,
    val channel: String = "",
    val count: String = "",
    val thumb: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("channel", channel)
        .put("count", count).put("thumb", thumb)

    companion object {
        fun fromJson(o: JSONObject) = Playlist(
            id = o.optString("id"),
            title = o.optString("title"),
            channel = o.optString("channel"),
            count = o.optString("count"),
            thumb = o.optString("thumb"),
        )
    }
}

sealed class SearchItem {
    data class V(val video: Video) : SearchItem()
    data class C(val channel: Channel) : SearchItem()
    data class P(val playlist: Playlist) : SearchItem()
}

data class Comment(
    val author: String,
    val text: String,
    val likes: String = "",
    val published: String = "",
    val avatar: String = "",
    val replyCount: String = "",
)

data class Page<T>(
    val items: List<T>,
    val continuation: String? = null,
)

/** One playable rendition choice offered in the quality menu. */
data class StreamChoice(
    val label: String,          // "360p", "144p", "Audio only"
    val height: Int,            // 0 for audio-only
    val muxedUrl: String?,      // set for progressive muxed (itag 18)
    val videoUrl: String?,      // set for adaptive pair
    val audioUrl: String?,      // adaptive audio (also used alone for audio-only)
)

data class StreamBundle(
    val videoId: String,
    val title: String,
    val channel: String,
    val durationSec: Long,
    val choices: List<StreamChoice>,
    /** Index into InnerTube.playerClients of the client that issued these URLs. */
    val clientIndex: Int = 0,
    /** User-Agent that MUST accompany stream requests for these URLs. */
    val streamUa: String = "",
    val resolvedAtMs: Long = System.currentTimeMillis(),
)

class PlaybackBlockedException(val reason: String) : Exception(reason)
