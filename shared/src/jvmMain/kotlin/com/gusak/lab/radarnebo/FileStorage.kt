package com.gusak.lab.radarnebo

import com.gusak.lab.radarnebo.data.KeyValueStorage
import java.io.File
import java.util.Properties

/** Desktop storage: a properties file in the user's home directory (`~/.radarnebo/settings.properties`). */
class FileStorage(
    private val file: File = File(System.getProperty("user.home"), ".radarnebo/settings.properties"),
) : KeyValueStorage {
    private val props = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    @Synchronized
    override fun getString(key: String): String? = props.getProperty(key)

    @Synchronized
    override fun putString(key: String, value: String?) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "RadarNebo settings") }
    }
}
