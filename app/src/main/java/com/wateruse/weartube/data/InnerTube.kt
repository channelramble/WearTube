package com.wateruse.weartube.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/**
 * Raw InnerTube (youtubei/v1) transport.
 *
 * Client selection rules (hard-won on WristTube + re-measured here 2026-08-18):
 *  - player: OLD PINNED client versions only. Current versions get SABR responses
 *    that withhold every stream URL (ANDROID 20.50+/21.x -> 0/25 URLs; 20.10.38 -> 25/25).
 *  - ANDROID 20.10.38 comes FIRST on this platform: googlevideo enforces per-video
 *    that the transport looks like the claimed client, so claiming IOS from Android's
 *    TLS stack gets 403s on enforced videos (measured: same URL+headers 206 via curl,
 *    403 in-app; dQw4w9WgXcQ grandfathered, n61ULEU7CO0 enforced).
 *  - The stream request User-Agent must match the client that issued the URLs.
 *  - search/browse/next: WEB client. The player request stays anonymous, always.
 */
object InnerTube {

    private val JSON = "application/json".toMediaType()
    private const val BASE = "https://www.youtube.com/youtubei/v1"

    data class PlayerClient(val name: String, val version: String, val headerId: String, val ua: String)

    val playerClients = listOf(
        PlayerClient("ANDROID", "20.10.38", "3", "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip"),
        PlayerClient("IOS", "20.10.4", "5", iosUserAgent("20.10.4")),
        PlayerClient("IOS", "21.32.4", "5", iosUserAgent("21.32.4")),
    )

    private const val WEB_VERSION = "2.20250612.01.00"
    private const val WEB_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private fun hl(): String = Locale.getDefault().language.ifBlank { "en" }
    private fun gl(): String = Locale.getDefault().country.ifBlank { "US" }

    fun iosUserAgent(version: String) =
        "com.google.ios.youtube/$version (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"

    private fun webContext(): JSONObject = JSONObject().put(
        "client", JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", WEB_VERSION)
            .put("hl", hl())
            .put("gl", gl())
            .put("userAgent", WEB_UA)
            .put("platform", "DESKTOP")
    )

    private fun playerContext(client: PlayerClient): JSONObject {
        val c = JSONObject()
            .put("clientName", client.name)
            .put("clientVersion", client.version)
            .put("hl", hl())
            .put("gl", gl())
            .put("userAgent", client.ua)
        if (client.name == "IOS") {
            c.put("deviceMake", "Apple")
                .put("deviceModel", "iPhone16,2")
                .put("osName", "iPhone")
                .put("osVersion", "18.3.2.22D82")
        } else {
            c.put("osName", "Android")
                .put("osVersion", "14")
                .put("androidSdkVersion", 34)
        }
        return JSONObject().put("client", c)
    }

    private fun post(endpoint: String, body: JSONObject, web: Boolean, client: PlayerClient? = null): JSONObject {
        val builder = Request.Builder()
            .url("$BASE/$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
        if (web) {
            builder.header("User-Agent", WEB_UA)
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", WEB_VERSION)
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
            // NOTE: no Authorization here on purpose. Personal OAuth tokens are not
            // valid for youtubei endpoints; the account is used via GoogleApi (Data API)
            // and every InnerTube request stays anonymous.
        } else {
            builder.header("User-Agent", client!!.ua)
                .header("X-YouTube-Client-Name", client.headerId)
                .header("X-YouTube-Client-Version", client.version)
        }
        Net.client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("$endpoint HTTP ${resp.code}")
            return JSONObject(resp.body!!.string())
        }
    }

    // ---- endpoints ----

    fun player(videoId: String, client: PlayerClient): JSONObject {
        val body = JSONObject()
            .put("context", playerContext(client))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        return post("player", body, web = false, client = client)
    }

    fun search(query: String, params: String? = null): JSONObject {
        val body = JSONObject().put("context", webContext()).put("query", query)
        if (params != null) body.put("params", params)
        return post("search", body, web = true)
    }

    fun searchContinuation(token: String): JSONObject =
        post("search", JSONObject().put("context", webContext()).put("continuation", token), web = true)

    fun browse(browseId: String, params: String? = null): JSONObject {
        val body = JSONObject().put("context", webContext()).put("browseId", browseId)
        if (params != null) body.put("params", params)
        return post("browse", body, web = true)
    }

    fun browseContinuation(token: String): JSONObject =
        post("browse", JSONObject().put("context", webContext()).put("continuation", token), web = true)

    /**
     * Authenticated write against the TV client (subscribe/unsubscribe/playlist edits).
     * Verified working: returns responseContext with logged_in=1.
     * The `params` value is the standard subscribe token InnerTube expects alongside
     * channelIds — channelIds alone yields HTTP 400.
     */
    fun tvAction(endpoint: String, body: JSONObject): Boolean {
        val token = TvAuth.accessToken ?: return false
        body.put(
            "context",
            JSONObject().put(
                "client", JSONObject()
                    .put("clientName", "TVHTML5")
                    .put("clientVersion", TV_VERSION)
                    .put("hl", hl())
                    .put("gl", gl())
            )
        )
        val req = Request.Builder()
            .url("$BASE/$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("User-Agent", TV_UA)
            .header("X-YouTube-Client-Name", "7")
            .header("X-YouTube-Client-Version", TV_VERSION)
            .header("Authorization", "Bearer $token")
            .build()
        return try {
            Net.client.newCall(req).execute().use { resp ->
                val ok = resp.isSuccessful
                android.util.Log.i("WTAuth", "tvAction $endpoint -> HTTP ${resp.code}")
                ok
            }
        } catch (e: Exception) {
            android.util.Log.w("WTAuth", "tvAction $endpoint failed: ${e.message}")
            false
        }
    }

    /** Subscribe the signed-in account to [channelId]. No-op when not signed in. */
    fun subscribeAccount(channelId: String): Boolean =
        tvAction(
            "subscription/subscribe",
            JSONObject()
                .put("channelIds", org.json.JSONArray().put(channelId))
                .put("params", "EgIIAhgA")
        )

    /** Unsubscribe the signed-in account from [channelId]. */
    fun unsubscribeAccount(channelId: String): Boolean =
        tvAction(
            "subscription/unsubscribe",
            JSONObject()
                .put("channelIds", org.json.JSONArray().put(channelId))
                .put("params", "CgIIAhgA")
        )

    private const val TV_VERSION = "7.20250101.00.00"
    private const val TV_UA =
        "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version,gzip(gfe) (unlike Gecko) v8/8.8.278.8-jit gles Starboard/13"

    /**
     * Authenticated browse against the TVHTML5 client — the only route to the
     * account's personalized feeds (FEwhat_to_watch, FEsubscriptions). The context
     * client must match the client the TV token was minted for.
     */
    fun tvBrowse(browseId: String, params: String? = null, continuation: String? = null): JSONObject {
        val token = TvAuth.accessToken ?: throw IOException("not signed in to TV client")
        val context = JSONObject().put(
            "client", JSONObject()
                .put("clientName", "TVHTML5")
                .put("clientVersion", TV_VERSION)
                .put("hl", hl())
                .put("gl", gl())
                .put("userAgent", TV_UA)
                .put("platform", "TV")
        )
        val body = JSONObject().put("context", context)
        if (continuation != null) body.put("continuation", continuation) else body.put("browseId", browseId)
        if (params != null) body.put("params", params)

        val req = Request.Builder()
            .url("$BASE/browse?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("User-Agent", TV_UA)
            .header("X-YouTube-Client-Name", "7")
            .header("X-YouTube-Client-Version", TV_VERSION)
            .header("Authorization", "Bearer $token")
            .header("Origin", "https://www.youtube.com")
            .build()
        Net.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("tvBrowse HTTP ${resp.code}")
            return JSONObject(resp.body!!.string())
        }
    }

    fun next(videoId: String): JSONObject =
        post("next", JSONObject().put("context", webContext()).put("videoId", videoId), web = true)

    fun nextContinuation(token: String): JSONObject =
        post("next", JSONObject().put("context", webContext()).put("continuation", token), web = true)

    /** Search suggestions; client=firefox returns plain JSON: ["q", ["s1","s2",...]] */
    fun suggestions(q: String): List<String> {
        val url = "https://suggestqueries-clients6.youtube.com/complete/search" +
                "?client=firefox&ds=yt&hl=${hl()}&q=${URLEncoder.encode(q, "UTF-8")}"
        val req = Request.Builder().url(url).header("User-Agent", WEB_UA).build()
        Net.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val arr = JSONArray(resp.body!!.string())
            val out = ArrayList<String>()
            val list = arr.optJSONArray(1) ?: return emptyList()
            for (i in 0 until list.length()) out.add(list.optString(i))
            return out
        }
    }
}
