package com.geeksville.mesh.convoy

import java.io.File
import com.grouptrack.shared.filter.TrailFilterState as Shared

/**
 * TrailFilterState -- GTBATCH1A-2026-11-06 (2.8): a PASS-THROUGH to the shared one (grouptrack-shared,
 * com.grouptrack.shared.filter.TrailFilterState), which now holds the state, the rules, the file format and the log lines -- the
 * Map Keys panel's state, and the SQL predicate derived from it (TRAILFILTER-2026-09-01). The design notes are there and in this
 * file's 2.7e history.
 *
 * Same names as 2.7e, so no caller changed. What stays here: the lock (@Synchronized -- common Kotlin has none; every Android call
 * comes through here, the same serialisation as before), where map_keys.json lives (GroupTrackStorage.root()), and reading the
 * shipped defaults from the APK assets.
 * The state reads are now read-only views (callers only ever read them).
 */
object TrailFilterState {

    /** ⭐ A panel row that acts on `status`, not `carto_code`. */
    const val ROW_UNOFFICIAL = Shared.ROW_UNOFFICIAL

    /** DEFAULTS-2026-09-02: the shipped palette inside the APK (assets/map_keys_defaults.json). */
    private const val DEFAULTS_ASSET = "map_keys_defaults.json"

    // STORAGEROOT2-2026-09-11: map_keys.json, at the root -- asked fresh each time, as before (the root can change after migration).
    init { Shared.pathProvider = { file().path } }

    val land: String get() = Shared.land
    val restricted: Boolean get() = Shared.restricted
    val areaPublic: Boolean get() = Shared.areaPublic
    val areaRestricted: Boolean get() = Shared.areaRestricted
    val use: String get() = Shared.use
    val off: Set<String> get() = Shared.off
    val style: Map<String, Triple<String, String?, Int>> get() = Shared.style

    fun file(): File = File(GroupTrackStorage.root(), Shared.FILE_NAME)

    private fun asset(context: android.content.Context): () -> String =
        { context.assets.open(DEFAULTS_ASSET).bufferedReader().use { it.readText() } }

    @Synchronized fun installDefaults(context: android.content.Context): Boolean = Shared.installDefaults(asset(context))
    @Synchronized fun ensureDefaults(context: android.content.Context) = Shared.ensureDefaults(asset(context))
    @Synchronized fun load(force: Boolean = false) = Shared.load(force)
    fun where(): String = Shared.where()
    fun whereOrEmpty(): String = Shared.whereOrEmpty()
    @Synchronized fun setLand(v: String) = Shared.setLand(v)
    @Synchronized fun setRestricted(v: Boolean) = Shared.setRestricted(v)
    @Synchronized fun setAreaPublic(v: Boolean) = Shared.setAreaPublic(v)
    @Synchronized fun setAreaRestricted(v: Boolean) = Shared.setAreaRestricted(v)
    @Synchronized fun setUse(v: String) = Shared.setUse(v)
    @Synchronized fun forRouteBuild() = Shared.forRouteBuild()
    @Synchronized fun toggleCategory(name: String) = Shared.toggleCategory(name)
    @Synchronized fun setGroup(names: List<String>, on: Boolean) = Shared.setGroup(names, on)
    @Synchronized fun setStyle(name: String, colour: String, dash: String?, weight: Int) = Shared.setStyle(name, colour, dash, weight)
    fun isOn(name: String): Boolean = Shared.isOn(name)
    @Synchronized fun mergeShippedCategories(context: android.content.Context): List<String> = Shared.mergeShippedCategories(asset(context))
    @Synchronized fun save() = Shared.save()
    fun styleJson(): String = Shared.styleJson()
}
