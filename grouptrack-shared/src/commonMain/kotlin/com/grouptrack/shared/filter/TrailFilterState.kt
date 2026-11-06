package com.grouptrack.shared.filter

import com.grouptrack.shared.log.gtLogger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * TrailFilterState -- the Map Keys panel's state, and the SQL predicate derived from it. TRAILFILTER-2026-09-01.
 * GTBATCH1A-2026-11-06 (2.8): MOVED to shared Kotlin from app/.../convoy/TrailFilterState.kt -- the rules, the file format and the log
 * lines are unchanged; org.json -> kotlinx JSON, java.io.File -> Okio, android.util.Log -> Kermit (same tag "TrailFilter"). The Android
 * object of the same name is now a pass-through to this one (and holds the lock -- see below).
 *
 * The design notes stay true (full text in the 2.7e file, git history): ONE STATE, TWO MAPS, EVERY QUERY; a JSON file (map_keys.json)
 * readable from any process; THE PREDICATE IS DERIVED, NEVER STORED; it MUST be loaded before the first draw; NOT at splash.
 *
 * ⚠ LOCKING: Kotlin common code has no @Synchronized. On Android every call comes through the Android pass-through, whose methods keep
 *   @Synchronized -- the same serialisation as before. The iPad calls from its main thread only.
 * ⚠ PLATFORM INPUTS: where the file lives ([pathProvider], set once by the platform) and the shipped defaults' text (passed in by the
 *   caller, which reads its own asset).
 */
object TrailFilterState {

    private val log = gtLogger("TrailFilter")   // GTLOG-2026-11-06: the platform log, never analytics
    const val FILE_NAME = "map_keys.json"

    /** ⭐ A panel row that acts on `status`, not `carto_code`. Prefixed so it can never collide with a real category value. */
    const val ROW_UNOFFICIAL = "__unofficial"

    /** Current schema of the saved file. ⚠ Bump when the SHAPE changes so an old file is discarded rather than half-read. */
    private const val VERSION = 1

    /** The full path of map_keys.json. Set ONCE by the platform before [load] (Android: GroupTrackStorage.root()/map_keys.json).
     *  Not set = a call-ordering bug, and it says so (CODE RULE 1: no silent default). */
    var pathProvider: () -> String = { throw IllegalStateException("TrailFilterState.pathProvider not set by the platform") }

    // ── the state ───────────────────────────────────────────────────────
    /** PUBLIC | PRIVATE | ALL */
    var land: String = "PUBLIC"; private set   // PUBLICDEFAULT-2026-10-25 (Fred 10-24 13:48): private land hidden by default
    /** LANDDISPLAY-2026-11-02: the third LAND tick -- RESTRICTED trails (red) and areas (red stripes). On by default. */
    var restricted: Boolean = true; private set
    /** LANDAREAS-2026-11-02: the striped OVERLAYS, on their own (not the trail ticks). Both on by default. */
    var areaPublic: Boolean = true; private set
    var areaRestricted: Boolean = true; private set
    /** MOTORIZED | NON-MOTORIZED | ALL */
    var use: String = "ALL"; private set
    /** Categories the rider has switched OFF. Empty = everything shown. */
    var off: MutableSet<String> = HashSet(); private set
    /** Per-category presentation: colour, dash pattern, weight. */
    var style: MutableMap<String, Triple<String, String?, Int>> = HashMap(); private set

    /** ⚠ NULL MEANS NOT LOADED -- it does NOT mean "no filter". */
    private var cachedWhere: String? = null
    private var loaded = false

    private val fs: FileSystem get() = FileSystem.SYSTEM
    private fun path() = pathProvider().toPath()

    // ── defaults (DEFAULTS-2026-09-02): the shipped palette, copied out the first time; the caller reads its own asset ──
    /** Copy the shipped defaults over the working file. ⚠ Returns false rather than throwing. */
    fun installDefaults(readDefaults: () -> String): Boolean = try {
        val text = readDefaults()
        val p = path()
        p.parent?.let { fs.createDirectories(it) }
        fs.write(p) { writeUtf8(text) }
        log.i { "installed defaults (${text.length} bytes)" }
        true
    } catch (e: Exception) {
        log.e { "installDefaults failed: ${e.message}" }
        false
    }

    /** Before [load] when opening the key panel: copies the shipped palette out ONLY if the rider has no file of their own. */
    fun ensureDefaults(readDefaults: () -> String) {
        val size = fs.metadataOrNull(path())?.size ?: -1L
        if (size < 2L) {
            log.i { "no map_keys.json -- installing shipped defaults" }
            if (installDefaults(readDefaults)) load(force = true)
        }
    }

    // ── load ────────────────────────────────────────────────────────────
    fun load(force: Boolean = false) {
        if (loaded && !force) return
        try {
            val p = path()
            val size = fs.metadataOrNull(p)?.size ?: -1L
            if (size > 2L) {
                val o = Json.parseToJsonElement(fs.read(p) { readUtf8() }).jsonObject
                val v = o.int("version", 0)
                if (v != VERSION) {
                    log.w { "map_keys.json version $v != $VERSION -- defaults" }
                    reset()
                } else {
                    land = o.str("land", "PUBLIC").uppercase()   // PUBLICDEFAULT-2026-10-25: a saved choice is kept
                    restricted = o.bool("restricted", true)   // LANDDISPLAY-2026-11-02
                    areaPublic = o.bool("area_public", true); areaRestricted = o.bool("area_restricted", true)   // LANDAREAS-2026-11-02
                    use = o.str("use", "ALL").uppercase()
                    off = HashSet()
                    (o["off"] as? JsonArray)?.forEach { e -> (e as? JsonPrimitive)?.contentOrNull?.let { off.add(it) } }
                    style = parseStyle(o["style"] as? JsonObject)
                }
            } else {
                reset()
            }
        } catch (e: Exception) {
            log.e { "load failed, using defaults: ${e.message}" }
            reset()
        }
        regenerate()
        loaded = true
        log.i { "loaded: land=$land use=$use off=${off.size} -> $cachedWhere" }
    }

    private fun reset() {
        land = "PUBLIC"; use = "ALL"; off = HashSet(); style = HashMap()   // PUBLICDEFAULT-2026-10-25
        restricted = true   // LANDDISPLAY-2026-11-02
        areaPublic = true; areaRestricted = true   // LANDAREAS-2026-11-02
    }

    /** The WHERE fragment, ready to append. Starts with AND. ⚠ Throws if [load] has not run -- deliberate. */
    fun where(): String = cachedWhere
        ?: throw IllegalStateException("TrailFilterState.where() before load(). The map composable must call load() before the WebView is constructed.")

    /** Same, but never throws -- for paths that legitimately run early. */
    fun whereOrEmpty(): String = cachedWhere ?: ""

    /** The 2.7e rules, unchanged: CLOSED never draws; NULL land/use passes (CLASSIFYCOLS-2026-09-02); the three LAND ticks
     *  (LANDDISPLAY-2026-11-02); unofficial; switched-off categories. */
    private fun regenerate() {
        val sb = StringBuilder()
        sb.append(" AND (status IS NULL OR status <> 'CLOSED')")
        val lands = ArrayList<String>()
        if (land != "PRIVATE") lands.add("PUBLIC")
        if (land != "PUBLIC") lands.add("PRIVATE")
        if (restricted) lands.add("RESTRICTED")
        sb.append(" AND (land_status IS NULL OR land_status IN (" + lands.joinToString(",") { "'$it'" } + "))")
        if (use == "MOTORIZED" || use == "NON-MOTORIZED") sb.append(" AND (use_type IS NULL OR use_type='").append(use).append("')")
        if (off.contains(ROW_UNOFFICIAL)) sb.append(" AND (status IS NULL OR status NOT IN ('UNOFFICIAL','UNCERTAIN'))")
        val cats = off.filter { it != ROW_UNOFFICIAL }
        if (cats.isNotEmpty()) {
            sb.append(" AND COALESCE(carto_code,'shape only') NOT IN (")
            sb.append(cats.joinToString(",") { "'" + it.replace("'", "") + "'" })
            sb.append(")")
        }
        cachedWhere = sb.toString()
    }

    // ── the panel's changes ─────────────────────────────────────────────
    fun setLand(v: String) { land = v.uppercase(); regenerate(); save() }
    /** LANDDISPLAY-2026-11-02 */
    fun setRestricted(v: Boolean) { restricted = v; regenerate(); save() }
    /** LANDAREAS-2026-11-02: the overlays -- no effect on which trails are drawn, so no regenerate(). */
    fun setAreaPublic(v: Boolean) { areaPublic = v; save() }
    fun setAreaRestricted(v: Boolean) { areaRestricted = v; save() }
    fun setUse(v: String) { use = v.uppercase(); regenerate(); save() }

    /** ROUTEFILTER-2026-11-03 v2 (Fred 18:31): a route build SHOWS the land-area overlays; the filter VALUES are not touched. */
    fun forRouteBuild() {
        areaPublic = true; areaRestricted = true; save()
        log.i { "ROUTEFILTER route build: land-area overlays shown; filter values kept: land=$land use=$use off=${off.size} -> $cachedWhere" }
    }

    fun toggleCategory(name: String) { if (!off.remove(name)) off.add(name); regenerate(); save() }

    /** Whole column on or off -- the header checkbox. */
    fun setGroup(names: List<String>, on: Boolean) { if (on) off.removeAll(names.toSet()) else off.addAll(names); regenerate(); save() }

    fun setStyle(name: String, colour: String, dash: String?, weight: Int) { style[name] = Triple(colour, dash, weight); save() }

    fun isOn(name: String): Boolean = !off.contains(name)

    /** EVERYLAUNCH-2026-09-08: add categories the shipped defaults have and the rider's file does not. ⛔ Existing entries never touched;
     *  style only (a new category is not added to `off`). Returns the names added. */
    fun mergeShippedCategories(readDefaults: () -> String): List<String> = try {
        val shipped = Json.parseToJsonElement(readDefaults()).jsonObject["style"] as? JsonObject
        if (shipped == null) emptyList() else {
            load()
            val added = ArrayList<String>()
            for ((k, v) in parseStyle(shipped)) { if (style.containsKey(k)) continue; style[k] = v; added.add(k) }
            if (added.isNotEmpty()) { log.i { "merged ${added.size} new categor(y/ies): $added" }; regenerate(); save() }
            added
        }
    } catch (e: Exception) {
        log.e { "mergeShippedCategories failed: ${e.message}" }
        emptyList()
    }

    // ── save ────────────────────────────────────────────────────────────
    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun save() {
        try {
            val o = buildJsonObject {
                put("version", VERSION)
                put("land", land)
                put("restricted", restricted)   // LANDDISPLAY-2026-11-02
                put("area_public", areaPublic); put("area_restricted", areaRestricted)   // LANDAREAS-2026-11-02
                put("use", use)
                put("off", JsonArray(off.toList().map { JsonPrimitive(it) }))
                put("style", styleObject())
            }
            val text = pretty.encodeToString(JsonObject.serializer(), o)
            val p = path()
            p.parent?.let { fs.createDirectories(it) }
            val tmp = (p.toString() + ".tmp").toPath()
            fs.write(tmp) { writeUtf8(text) }
            try { fs.atomicMove(tmp, p) } catch (e: Exception) { fs.write(p) { writeUtf8(text) }; fs.delete(tmp, mustExist = false) }
        } catch (e: Exception) {
            log.e { "save failed: ${e.message}" }
        }
    }

    /** The style map as JSON, for handing to the map page (setTrailStyles). */
    fun styleJson(): String = styleObject().toString()

    private fun styleObject() = JsonObject(style.mapValues { (_, v) ->
        JsonObject(mapOf("c" to JsonPrimitive(v.first), "d" to (v.second?.let { JsonPrimitive(it) } ?: JsonNull), "w" to JsonPrimitive(v.third)))
    })

    private fun parseStyle(s: JsonObject?): MutableMap<String, Triple<String, String?, Int>> {
        val out = HashMap<String, Triple<String, String?, Int>>()
        if (s == null) return out
        for ((k, el) in s) {
            val e = el as? JsonObject ?: continue
            val d = e["d"]
            out[k] = Triple(e.str("c", "#00FFFF"), if (d == null || d is JsonNull) null else (d as? JsonPrimitive)?.content, e.int("w", 2))
        }
        return out
    }

    // org.json's optString / optBoolean / optInt, as used by the 2.7e file
    private fun JsonObject.str(k: String, def: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: def
    private fun JsonObject.bool(k: String, def: Boolean) = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() } ?: def
    private fun JsonObject.int(k: String, def: Int) = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: def
}
