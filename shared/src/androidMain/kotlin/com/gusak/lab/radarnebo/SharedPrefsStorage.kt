package com.gusak.lab.radarnebo

import android.content.Context
import com.gusak.lab.radarnebo.data.KeyValueStorage

class SharedPrefsStorage(context: Context) : KeyValueStorage {
    private val prefs = context.applicationContext.getSharedPreferences("radar_nebo", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
