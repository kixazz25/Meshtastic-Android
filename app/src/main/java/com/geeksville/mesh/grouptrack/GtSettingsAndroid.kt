package com.geeksville.mesh.grouptrack

import android.content.Context
import android.content.SharedPreferences
import com.grouptrack.shared.settings.GtSettings
import com.grouptrack.shared.settings.GtSettingsEditor
import com.grouptrack.shared.settings.GtSettingsStore

/** STEP17G2B1-2026-11-12: the settings socket on Android = SharedPreferences(name, MODE_PRIVATE) -- the same files and keys as 2.7e. */
class GtSettingsAndroid(ctx: Context) : GtSettings {
    private val app = ctx.applicationContext
    override fun store(name: String): GtSettingsStore = Store(app.getSharedPreferences(name, Context.MODE_PRIVATE))

    private class Store(private val p: SharedPreferences) : GtSettingsStore {
        override fun getString(key: String, def: String?): String? = p.getString(key, def)
        override fun getInt(key: String, def: Int): Int = p.getInt(key, def)
        override fun getLong(key: String, def: Long): Long = p.getLong(key, def)
        override fun getFloat(key: String, def: Float): Float = p.getFloat(key, def)
        override fun getBoolean(key: String, def: Boolean): Boolean = p.getBoolean(key, def)
        override fun getStringSet(key: String, def: Set<String>?): Set<String>? = p.getStringSet(key, def)
        override fun contains(key: String): Boolean = p.contains(key)
        override fun all(): Map<String, Any?> = p.all
        override fun edit(block: GtSettingsEditor.() -> Unit) {
            val e = p.edit()
            object : GtSettingsEditor {
                override fun putString(key: String, value: String?) { e.putString(key, value) }
                override fun putInt(key: String, value: Int) { e.putInt(key, value) }
                override fun putLong(key: String, value: Long) { e.putLong(key, value) }
                override fun putFloat(key: String, value: Float) { e.putFloat(key, value) }
                override fun putBoolean(key: String, value: Boolean) { e.putBoolean(key, value) }
                override fun putStringSet(key: String, value: Set<String>?) { e.putStringSet(key, value) }
                override fun remove(key: String) { e.remove(key) }
                override fun clear() { e.clear() }
            }.block()
            e.apply()
        }
    }
}
