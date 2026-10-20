package com.gusak.lab.radarnebo

import com.gusak.lab.radarnebo.data.KeyValueStorage
import platform.Foundation.NSUserDefaults

class UserDefaultsStorage : KeyValueStorage {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, key)
    }
}
