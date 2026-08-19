package com.wateruse.weartube.data

import android.util.Log
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * YouTube Data API v3 — used ONLY for account identity: the signed-in user's
 * subscriptions and playlists. Content browsing and playback stay on anonymous
 * InnerTube (personal OAuth tokens aren't valid there, and this keeps quota tiny:
 * one subscriptions.list + one playlists.list per feed refresh).
 */
object GoogleApi {

    private const val BASE = "https://www.googleapis.com/youtube/v3"

    private fun get(url: String): JSONObject {
        val token = Auth.token ?: throw IOException("not signed in")
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        Net.client.newCall(req).execute().use { resp ->
            if (resp.code == 401) {
                Auth.invalidateToken()
                throw IOException("token rejected")
            }
            if (!resp.isSuccessful) throw IOException("Data API HTTP ${resp.code}")
            return JSONObject(resp.body!!.string())
        }
    }

    /** The account's channel subscriptions (first 50, newest activity first). */
    fun mySubscriptions(): List<Channel> {
        val out = ArrayList<Channel>()
        try {
            val resp = get("$BASE/subscriptions?part=snippet&mine=true&maxResults=50&order=unread")
            val items = resp.optJSONArray("items") ?: return out
            for (i in 0 until items.length()) {
                val snippet = items.optJSONObject(i)?.optJSONObject("snippet") ?: continue
                val channelId = snippet.optJSONObject("resourceId")?.optString("channelId").orEmpty()
                if (channelId.isEmpty()) continue
                out.add(
                    Channel(
                        id = channelId,
                        title = snippet.optString("title"),
                        thumb = snippet.optJSONObject("thumbnails")
                            ?.optJSONObject("medium")?.optString("url")
                            ?: snippet.optJSONObject("thumbnails")
                                ?.optJSONObject("default")?.optString("url").orEmpty(),
                    )
                )
            }
        } catch (e: Exception) {
            Log.w("WTAuth", "mySubscriptions failed: ${e.message}")
        }
        return out
    }

    /** The account's own playlists (first 50). */
    fun myPlaylists(): List<Playlist> {
        val out = ArrayList<Playlist>()
        try {
            val resp = get("$BASE/playlists?part=snippet,contentDetails&mine=true&maxResults=50")
            val items = resp.optJSONArray("items") ?: return out
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isEmpty()) continue
                val snippet = item.optJSONObject("snippet")
                out.add(
                    Playlist(
                        id = id,
                        title = snippet?.optString("title").orEmpty(),
                        count = item.optJSONObject("contentDetails")?.optInt("itemCount")?.toString().orEmpty(),
                        thumb = snippet?.optJSONObject("thumbnails")
                            ?.optJSONObject("medium")?.optString("url").orEmpty(),
                    )
                )
            }
        } catch (e: Exception) {
            Log.w("WTAuth", "myPlaylists failed: ${e.message}")
        }
        return out
    }
}
