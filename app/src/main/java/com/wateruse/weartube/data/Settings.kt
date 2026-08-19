package com.wateruse.weartube.data

import android.content.Context
import android.content.SharedPreferences

object Settings {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("weartube", Context.MODE_PRIVATE)
    }

    /** 0 = Auto (prefer muxed 360p), else preferred adaptive height (144/240/360/480). */
    var preferredHeight: Int
        get() = prefs.getInt("preferredHeight", 0)
        set(v) { prefs.edit().putInt("preferredHeight", v).apply() }

    var autoplayNext: Boolean
        get() = prefs.getBoolean("autoplayNext", true)
        set(v) { prefs.edit().putBoolean("autoplayNext", v).apply() }

    var audioOnly: Boolean
        get() = prefs.getBoolean("audioOnly", false)
        set(v) { prefs.edit().putBoolean("audioOnly", v).apply() }
}
