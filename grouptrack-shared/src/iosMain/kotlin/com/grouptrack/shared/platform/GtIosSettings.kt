package com.grouptrack.shared.platform

import com.grouptrack.shared.settings.GtSettings
import com.grouptrack.shared.settings.GtSettingsEditor
import com.grouptrack.shared.settings.GtSettingsStore
import platform.Foundation.NSUserDefaults

/**
 * CERTVALUES-2026-11-16 (seam 2, batch 2): the iPad's SETTINGS socket.
 *
 * The design was fixed when the socket was written -- GtSettings.kt: "Android = SharedPreferences(name):
 * the same files, the same keys; the iPad = UserDefaults, ONE SUITE PER STORE NAME, at step 18." This is
 * that step. Nothing here is invented; every member mirrors what SharedPreferences does, and where the
 * two platforms' APIs disagree the Android behaviour wins.
 *
 * ⛔ FOUR PLACES WHERE THE OBVIOUS CALL IS WRONG. Each compiles, each returns a plausible value, and
 * three of them are silent -- which is why they are written out rather than discovered later:
 *
 *  1. ABSENT KEYS. SharedPreferences.getInt/getLong/getFloat/getBoolean return THE CALLER'S DEFAULT when
 *     the key is missing. NSUserDefaults' integerForKey:/boolForKey:/floatForKey: return 0 and false.
 *     GtSession.isSubscribed() reads getLong("subscription_expires_at", 0L) and getBoolean(
 *     "dev_subscribed_override", false) -- those two happen to match, but getSearchRadius() reads
 *     getInt("search_radius_miles", 25) and would silently become 0 miles. Every getter below tests
 *     objectForKey first and returns the caller's default when it is null.
 *
 *  2. all(). A suite's dictionaryRepresentation() returns the MERGED SEARCH LIST -- NSGlobalDomain and
 *     the argument domain included, hundreds of keys that were never written here. SharedPreferences.getAll
 *     returns that one file's keys and nothing else. persistentDomainForName returns only what the suite
 *     itself persisted, which is the matching behaviour. GtCert17b compares all() key for key, so the
 *     merged dictionary would have buried the real comparison under system noise.
 *
 *  3. clear(). SharedPreferences.Editor.clear() empties that file. Removing keys one at a time from a
 *     suite leaves anything written outside this object's knowledge; removePersistentDomainForName empties
 *     the suite. GtSession.clearSession() depends on it -- GtCert17b asserts both stores report 0 keys.
 *
 *  4. getStringSet. SharedPreferences stores a Set<String> and returns an UNORDERED set. UserDefaults has
 *     no set type, so it is stored as an array and read back as a Set -- order is dropped deliberately,
 *     matching SharedPreferences. An NSArray value in all() is therefore reported as a Set, which is
 *     sound here because putStringSet is the only writer that stores one.
 *
 * ⚠ ONE DIVERGENCE NOT REPRODUCED, RECORDED (the GtHelpLinks precedent): SharedPreferences THROWS
 * ClassCastException when a key stored as a String is read with getInt. NSUserDefaults coerces instead.
 * No shared code reads a key at two types, so nothing exercises it; if something ever does, Android will
 * crash where the iPad returns a number.
 *
 * ⚠ A write is applied at once in memory and saved in the background on Android (apply()). UserDefaults
 * makes a set immediately visible and persists it itself, so edit() applies each call as it is made. No
 * synchronize() call: it has been a no-op-with-a-deprecation for years and the socket promises visibility,
 * not a flush.
 */
class IosSettings : GtSettings {
    override fun store(name: String): GtSettingsStore = IosSettingsStore(name)
}

class IosSettingsStore(private val suite: String) : GtSettingsStore {

    private val d: NSUserDefaults get() = NSUserDefaults(suiteName = suite)

    /** ⛔ the absent-key test that every getter below depends on (trap 1). */
    private fun has(key: String): Boolean = d.objectForKey(key) != null

    override fun getString(key: String, def: String?): String? =
        if (!has(key)) def else d.stringForKey(key) ?: def

    override fun getInt(key: String, def: Int): Int =
        if (!has(key)) def else d.integerForKey(key).toInt()

    override fun getLong(key: String, def: Long): Long =
        if (!has(key)) def else d.integerForKey(key)

    override fun getFloat(key: String, def: Float): Float =
        if (!has(key)) def else d.floatForKey(key)

    override fun getBoolean(key: String, def: Boolean): Boolean =
        if (!has(key)) def else d.boolForKey(key)

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, def: Set<String>?): Set<String>? {
        if (!has(key)) return def
        val a = d.arrayForKey(key) ?: return def
        return a.mapNotNull { it as? String }.toSet()
    }

    override fun contains(key: String): Boolean = has(key)

    /** ⛔ persistentDomainForName, NOT dictionaryRepresentation (trap 2). */
    override fun all(): Map<String, Any?> {
        val dom = NSUserDefaults.standardUserDefaults.persistentDomainForName(suite) ?: return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in dom) {
            val key = k as? String ?: continue
            out[key] = when (v) {
                is List<*> -> v.mapNotNull { it as? String }.toSet()   // trap 4: an array reads back as a Set
                else -> v
            }
        }
        return out
    }

    override fun edit(block: GtSettingsEditor.() -> Unit) {
        IosSettingsEditor(suite).block()
    }
}

class IosSettingsEditor(private val suite: String) : GtSettingsEditor {

    private val d: NSUserDefaults get() = NSUserDefaults(suiteName = suite)

    /** SharedPreferences.putString(key, null) REMOVES the key; it does not store a null. */
    override fun putString(key: String, value: String?) {
        if (value == null) d.removeObjectForKey(key) else d.setObject(value, key)
    }

    override fun putInt(key: String, value: Int) = d.setInteger(value.toLong(), key)
    override fun putLong(key: String, value: Long) = d.setInteger(value, key)
    override fun putFloat(key: String, value: Float) = d.setFloat(value, key)
    override fun putBoolean(key: String, value: Boolean) = d.setBool(value, key)

    /** As putString: a null value removes the key (trap 4 for the stored shape). */
    override fun putStringSet(key: String, value: Set<String>?) {
        if (value == null) d.removeObjectForKey(key) else d.setObject(value.toList(), key)
    }

    override fun remove(key: String) = d.removeObjectForKey(key)

    /** ⛔ removePersistentDomainForName, not key-by-key (trap 3). */
    override fun clear() {
        NSUserDefaults.standardUserDefaults.removePersistentDomainForName(suite)
    }
}
