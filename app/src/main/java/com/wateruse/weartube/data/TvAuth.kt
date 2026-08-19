package com.wateruse.weartube.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * YouTube TV "device code" sign-in — the living-room flow.
 *
 * The user enters a short code at google.com/device in their own browser; this app
 * never sees a password. The resulting token authenticates the INTERNAL youtubei
 * API as the user, which is the only place the personalized home feed
 * (FEwhat_to_watch) exists — the public Data API has no recommendations endpoint.
 *
 * Requests made with this token must present the TVHTML5 client context, matching
 * the client the token was minted for (same fingerprint-coherence rule that governs
 * the player clients).
 *
 * Playback still resolves anonymously via StreamResolver — the token is for feeds only.
 */
object TvAuth {

    private const val CLIENT_ID = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
    private const val CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT"
    private const val SCOPE = "https://www.googleapis.com/auth/youtube"
    private const val GRANT_DEVICE = "http://oauth.net/grant_type/device/1.0"

    private val JSON = "application/json".toMediaType()

    private lateinit var prefs: SharedPreferences

    @Volatile
    var accessToken: String? = null
        private set

    private var expiresAtMs: Long = 0

    fun init(context: Context) {
        prefs = context.getSharedPreferences("weartube_tv_auth", Context.MODE_PRIVATE)
        expiresAtMs = prefs.getLong("expires_at", 0)
        accessToken = prefs.getString("access", null)?.takeIf { expiresAtMs > System.currentTimeMillis() }
    }

    val refreshToken: String? get() = prefs.getString("refresh", null)

    val isSignedIn: Boolean get() = refreshToken != null

    data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val intervalSec: Int)

    private fun post(url: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .build()
        Net.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (text.isEmpty()) throw IOException("empty response ${resp.code}")
            return JSONObject(text)
        }
    }

    /** Step 1: ask Google for a code the user types at google.com/device. */
    fun requestDeviceCode(): DeviceCode {
        val resp = post(
            "https://www.youtube.com/o/oauth2/device/code",
            JSONObject()
                .put("client_id", CLIENT_ID)
                .put("scope", SCOPE)
                .put("device_id", java.util.UUID.randomUUID().toString().replace("-", ""))
                .put("device_model", "ytlr::")
        )
        val code = resp.optString("device_code")
        if (code.isEmpty()) throw IOException("no device code: ${resp.optString("error")}")
        return DeviceCode(
            deviceCode = code,
            userCode = resp.optString("user_code"),
            url = resp.optString("verification_url").ifEmpty { "google.com/device" },
            intervalSec = resp.optInt("interval", 5),
        )
    }

    /**
     * Step 2: one poll attempt. Returns true once authorized; false while the user
     * has not finished yet. Throws when the code is denied or expired.
     */
    fun pollOnce(deviceCode: String): Boolean {
        val resp = post(
            "https://www.youtube.com/o/oauth2/token",
            JSONObject()
                .put("client_id", CLIENT_ID)
                .put("client_secret", CLIENT_SECRET)
                .put("code", deviceCode)
                .put("grant_type", GRANT_DEVICE)
        )
        val access = resp.optString("access_token")
        if (access.isNotEmpty()) {
            store(access, resp.optString("refresh_token"), resp.optInt("expires_in", 3600))
            return true
        }
        when (val err = resp.optString("error")) {
            "authorization_pending", "slow_down" -> return false
            "" -> return false
            else -> throw IOException(err)
        }
    }

    private fun store(access: String, refresh: String?, expiresInSec: Int) {
        accessToken = access
        expiresAtMs = System.currentTimeMillis() + (expiresInSec - 60) * 1000L
        prefs.edit().apply {
            putString("access", access)
            putLong("expires_at", expiresAtMs)
            if (!refresh.isNullOrEmpty()) putString("refresh", refresh)
        }.apply()
    }

    /** Mint a fresh access token from the stored refresh token. Blocking; IO thread. */
    fun refreshIfNeeded(): String? {
        if (accessToken != null && expiresAtMs > System.currentTimeMillis()) return accessToken
        val refresh = refreshToken ?: return null
        return try {
            val resp = post(
                "https://www.youtube.com/o/oauth2/token",
                JSONObject()
                    .put("client_id", CLIENT_ID)
                    .put("client_secret", CLIENT_SECRET)
                    .put("refresh_token", refresh)
                    .put("grant_type", "refresh_token")
            )
            val access = resp.optString("access_token")
            if (access.isEmpty()) {
                Log.w("WTAuth", "refresh failed: ${resp.optString("error")}")
                null
            } else {
                store(access, null, resp.optInt("expires_in", 3600))
                access
            }
        } catch (e: Exception) {
            Log.w("WTAuth", "refresh error: ${e.message}")
            null
        }
    }

    fun signOut() {
        accessToken = null
        expiresAtMs = 0
        prefs.edit().clear().apply()
    }
}
