package com.wateruse.weartube.data

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log

/**
 * Google sign-in via the watch's OWN Google accounts (AccountManager / GMS),
 * no WebView and no password handling anywhere.
 *
 * The OAuth token is attached as a Bearer header to browse/search/next ONLY.
 * The player endpoint stays anonymous — WristTube measured that mixing account
 * auth into player requests gets HTTP 400 and risks flagging the account, and
 * anonymous playback keeps watching out of the account's history.
 */
object Auth {

    // read-only is all the Data API layer needs (subscriptions.list, playlists.list)
    private const val SCOPE = "oauth2:https://www.googleapis.com/auth/youtube.readonly"
    private const val TYPE_GOOGLE = "com.google"

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    @Volatile
    var token: String? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("weartube_auth", Context.MODE_PRIVATE)
    }

    var accountName: String?
        get() = prefs.getString("account", null)
        set(v) {
            prefs.edit().putString("account", v).apply()
        }

    val isSignedIn: Boolean get() = accountName != null

    fun chooseAccountIntent(): Intent =
        AccountManager.newChooseAccountIntent(
            null, null, arrayOf(TYPE_GOOGLE), null, null, null, null
        )

    /**
     * Blocking — call on Dispatchers.IO. With [activity] set, GMS may show its
     * consent screen; with null it fails quietly (used for silent refresh at launch).
     */
    fun fetchToken(activity: Activity?): String? {
        val name = accountName ?: return null
        return try {
            val am = AccountManager.get(appContext)
            val account = Account(name, TYPE_GOOGLE)
            val bundle = if (activity != null) {
                am.getAuthToken(account, SCOPE, null, activity, null, null).result
            } else {
                am.getAuthToken(account, SCOPE, null, false, null, null).result
            }
            val t = bundle.getString(AccountManager.KEY_AUTHTOKEN)
            if (t == null && bundle.containsKey(AccountManager.KEY_INTENT)) {
                Log.w("WTAuth", "consent needed; token not granted silently")
            }
            token = t
            t
        } catch (e: Exception) {
            Log.w("WTAuth", "getAuthToken failed: ${e.message}")
            null
        }
    }

    /** Drop a rejected token so the next fetch mints a fresh one. */
    fun invalidateToken() {
        val t = token ?: return
        try {
            AccountManager.get(appContext).invalidateAuthToken(TYPE_GOOGLE, t)
        } catch (_: Exception) {
        }
        token = null
    }

    fun signOut() {
        invalidateToken()
        accountName = null
    }
}
