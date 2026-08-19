package com.wateruse.weartube.ui

import com.wateruse.weartube.data.Video

/**
 * Wear-nav routes only carry strings; full Video objects (title/thumb/progress)
 * are stashed here right before navigating so the player can start instantly
 * with metadata instead of waiting on the resolver.
 */
object NavCache {
    private val videos = HashMap<String, Video>()
    private var queue: List<Video>? = null

    fun put(video: Video) {
        videos[video.id] = video
    }

    fun video(id: String): Video = videos[id] ?: Video(id = id, title = "")

    fun setQueue(list: List<Video>) {
        queue = list
    }

    fun takeQueue(): List<Video>? {
        val q = queue
        queue = null
        return q
    }
}
