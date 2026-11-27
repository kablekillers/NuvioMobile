package com.nuvio.app.features.streams

import android.content.Context
import android.content.SharedPreferences

internal actual object LinkMemoryStorage {
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences("kk_link_memory", Context.MODE_PRIVATE)
    }

    actual fun load(): String? = preferences?.getString("records", null)

    actual fun save(payload: String) {
        preferences?.edit()?.putString("records", payload)?.apply()
    }
}
