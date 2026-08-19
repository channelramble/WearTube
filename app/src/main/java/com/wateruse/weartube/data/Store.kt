package com.wateruse.weartube.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local persistence: subscriptions, history, playback positions, watch later,
 * recent searches. Plain JSON files in filesDir, atomic tmp+rename writes.
 * NewPipe-style local accounts: no Google sign-in anywhere.
 */
object Store {

    private lateinit var dir: File
    private val lock = Any()

    fun init(context: Context) {
        dir = context.filesDir
    }

    // ---- generic helpers ----

    private fun readArray(name: String): JSONArray = synchronized(lock) {
        val f = File(dir, name)
        if (!f.exists()) return JSONArray()
        return try { JSONArray(f.readText()) } catch (_: Exception) { JSONArray() }
    }

    private fun readObject(name: String): JSONObject = synchronized(lock) {
        val f = File(dir, name)
        if (!f.exists()) return JSONObject()
        return try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() }
    }

    private fun write(name: String, content: String) = synchronized(lock) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(content)
        tmp.renameTo(File(dir, name))
    }

    // ---- subscriptions ----

    fun subscriptions(): List<Channel> {
        val arr = readArray("subscriptions.json")
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map(Channel::fromJson)
    }

    fun isSubscribed(channelId: String): Boolean = subscriptions().any { it.id == channelId }

    fun toggleSubscription(channel: Channel): Boolean {
        val current = subscriptions().toMutableList()
        val existing = current.indexOfFirst { it.id == channel.id }
        val nowSubscribed = if (existing >= 0) { current.removeAt(existing); false } else { current.add(0, channel); true }
        val arr = JSONArray(); current.forEach { arr.put(it.toJson()) }
        write("subscriptions.json", arr.toString())
        return nowSubscribed
    }

    // ---- history ----

    fun history(): List<Video> {
        val arr = readArray("history.json")
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map(Video::fromJson)
    }

    fun addHistory(video: Video) {
        val current = history().filterNot { it.id == video.id }.toMutableList()
        current.add(0, video)
        while (current.size > 200) current.removeAt(current.size - 1)
        val arr = JSONArray(); current.forEach { arr.put(it.toJson()) }
        write("history.json", arr.toString())
    }

    fun clearHistory() = write("history.json", "[]")

    // ---- positions (resume) ----

    fun position(videoId: String): Long = readObject("positions.json").optLong(videoId, 0L)

    fun savePosition(videoId: String, seconds: Long) {
        val o = readObject("positions.json")
        o.put(videoId, seconds)
        // cap size: drop arbitrary old keys beyond 300
        if (o.length() > 300) {
            val names = o.names()
            if (names != null && names.length() > 0) o.remove(names.optString(0))
        }
        write("positions.json", o.toString())
    }

    // ---- watch later ----

    fun watchLater(): List<Video> {
        val arr = readArray("watchlater.json")
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map(Video::fromJson)
    }

    fun inWatchLater(videoId: String): Boolean = watchLater().any { it.id == videoId }

    fun toggleWatchLater(video: Video): Boolean {
        val current = watchLater().toMutableList()
        val idx = current.indexOfFirst { it.id == video.id }
        val nowIn = if (idx >= 0) { current.removeAt(idx); false } else { current.add(0, video); true }
        val arr = JSONArray(); current.forEach { arr.put(it.toJson()) }
        write("watchlater.json", arr.toString())
        return nowIn
    }

    // ---- saved playlists ----

    fun savedPlaylists(): List<Playlist> {
        val arr = readArray("playlists.json")
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map(Playlist::fromJson)
    }

    fun isPlaylistSaved(playlistId: String): Boolean = savedPlaylists().any { it.id == playlistId }

    fun togglePlaylist(playlist: Playlist): Boolean {
        val current = savedPlaylists().toMutableList()
        val idx = current.indexOfFirst { it.id == playlist.id }
        val nowSaved = if (idx >= 0) { current.removeAt(idx); false } else { current.add(0, playlist); true }
        val arr = JSONArray(); current.forEach { arr.put(it.toJson()) }
        write("playlists.json", arr.toString())
        return nowSaved
    }

    // ---- recent searches ----

    fun recentSearches(): List<String> {
        val arr = readArray("searches.json")
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
    }

    fun addSearch(q: String) {
        val current = recentSearches().filterNot { it.equals(q, ignoreCase = true) }.toMutableList()
        current.add(0, q)
        while (current.size > 20) current.removeAt(current.size - 1)
        write("searches.json", JSONArray(current).toString())
    }

    fun clearSearches() = write("searches.json", "[]")
}
