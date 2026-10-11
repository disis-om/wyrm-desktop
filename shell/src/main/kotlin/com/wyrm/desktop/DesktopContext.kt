package com.wyrm.desktop

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.Properties

/**
 * The desktop's android.content.Context: one properties file per preference
 * name in %APPDATA%\Wyrm\prefs. Every value is stored as its text (a string
 * set as its members joined by U+001F); account sync (DesktopAccountSync)
 * saves and restores them as text too.
 */
object DesktopContext : Context() {
    private val dir: File = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Wyrm/prefs").apply { mkdirs() }
    private val open = mutableMapOf<String, FilePreferences>()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        synchronized(open) { open.getOrPut(name) { FilePreferences(File(dir, "$name.properties")) } }
}

private class FilePreferences(private val file: File) : SharedPreferences {
    private val values = Properties().apply {
        if (file.exists()) runCatching { file.inputStream().use { load(it) } }
    }

    override fun getInt(key: String, defValue: Int) = values.getProperty(key)?.toIntOrNull() ?: defValue
    override fun getLong(key: String, defValue: Long) = values.getProperty(key)?.toLongOrNull() ?: defValue
    override fun getFloat(key: String, defValue: Float) = values.getProperty(key)?.toFloatOrNull() ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values.getProperty(key)?.toBooleanStrictOrNull() ?: defValue
    override fun getString(key: String, defValue: String?): String? = values.getProperty(key) ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        values.getProperty(key)?.let { raw -> if (raw.isEmpty()) mutableSetOf() else raw.split(SET_SEPARATOR).toMutableSet() } ?: defValues
    override fun contains(key: String) = values.containsKey(key)
    override fun getAll(): MutableMap<String, *> = synchronized(values) {
        values.stringPropertyNames().associateWith { values.getProperty(it) }.toMutableMap()
    }
    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()
        private var clearAll = false
        override fun putInt(key: String, value: Int) = apply { pending[key] = value.toString() }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value.toString() }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value.toString() }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value.toString() }
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply {
            pending[key] = values?.joinToString(SET_SEPARATOR)
        }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clearAll = true }
        override fun apply() { commit() }
        override fun commit(): Boolean = synchronized(values) {
            if (clearAll) values.clear()
            clearAll = false
            for ((k, v) in pending) if (v == null) values.remove(k) else values.setProperty(k, v)
            runCatching { file.outputStream().use { values.store(it, null) } }.isSuccess
        }
    }
}

private const val SET_SEPARATOR = "\u001F"
