package com.geeksville.mesh.convoy

import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * TrailFilterState — the Map Keys panel's state, and the SQL predicate derived
 * from it. TRAILFILTER-2026-09-01.
 *
 * ⭐⭐ ONE STATE, TWO MAPS, EVERY QUERY. Fred, 09-01: "same panel everything."
 * The convoy map, the planning map, the route builder and the route list all
 * read THIS object. ⛔ If the route builder had its own filter the two could
 * disagree, and a rider would see a map showing ground the router would not
 * use — which is the whole point of focusing the trails for route building
 * rather than merely tidying the display.
 *
 * ⭐ WHY A JSON FILE (Fred, 08-31): "json file, can be read at any point in any
 * process without having to scope data fields ... and it is available between
 * map panels." It survives the screen being destroyed and rebuilt — which the
 * map does routinely — it survives a reinstall the way trails do, and it is
 * inspectable with `adb shell cat` when something looks wrong.
 *
 * ⛔⛔ THE PREDICATE IS DERIVED, NEVER STORED. Fred asked why not save the SQL
 * in the JSON. Because a saved WHERE clause goes stale: rename a column or add
 * a category and every device keeps executing the old one — failing at query
 * time on the rider's device while the panel still looks correct. Deriving from
 * state means a schema change fixes itself. The cost is one string build per
 * panel change, not per query.
 *
 * ⛔⛔ AND IT MUST BE LOADED BEFORE THE FIRST DRAW. Fred, 09-01: "not just a
 * frame — everything until something changes that causes the filter to
 * recalc." An unloaded filter is not a brief flicker; it shows EVERYTHING for
 * the whole session, so a rider who set private off, closed the app and
 * reopened it sees their filter apparently ignored. *"I will get bombed with
 * questions if they open, the state is right but the display is wrong."*
 * ⭐ So [load] is called at the FRONT of the map composable — before the WebView
 * exists, while the GPS fix is still being acquired, which is dead time anyway.
 *
 * ⚠ NOT AT SPLASH, deliberately. The file lives in shared storage, and the
 * authority gate exists to stop anything touching shared storage before
 * all-files access is confirmed. By the time the map screen runs, the gate has
 * passed.
 */
object TrailFilterState {

    private const val TAG = "TrailFilter"
    private const val FILE_NAME = "map_keys.json"

    /** Current schema of the saved file. ⚠ Bump when the SHAPE changes so an
     *  old file is discarded rather than half-read. */
    private const val VERSION = 1

    // ── the state ───────────────────────────────────────────────────────
    /** PUBLIC | PRIVATE | ALL */
    var land: String = "ALL"; private set
    /** MOTORIZED | NON-MOTORIZED | ALL */
    var use: String = "ALL"; private set
    /** Categories the rider has switched OFF. Empty = everything shown. */
    var off: MutableSet<String> = HashSet(); private set

    /** Per-category presentation: colour, dash pattern, weight. The panel
     *  writes these; the maps read them. ⭐ Three attributes per row, so the
     *  panel can drive every presentation mode without the assets being
     *  touched again. */
    var style: MutableMap<String, Triple<String, String?, Int>> = HashMap()
        private set

    /**
     * ⚠ NULL MEANS NOT LOADED — it does NOT mean "no filter". A query that
     * finds null here has run before [load], which is a bug in call ordering,
     * and it should be visible to us rather than silently showing everything.
     */
    private var cachedWhere: String? = null

    private var loaded = false

    fun file(): File =
        File(File(Environment.getExternalStorageDirectory(), "Documents/GroupTrack"),
            FILE_NAME)

    // ── load ────────────────────────────────────────────────────────────

    /**
     * Read the saved state and derive the predicate. Idempotent: safe to call
     * from both map screens, and the second call is a no-op so they share one
     * state rather than loading their own.
     *
     * @param force re-read even if already loaded (after an external edit)
     */
    @Synchronized
    fun load(force: Boolean = false) {
        if (loaded && !force) return
        val f = file()
        try {
            if (f.exists() && f.length() > 2L) {
                val o = JSONObject(f.readText())
                val v = o.optInt("version", 0)
                if (v != VERSION) {
                    // ⚠ Shape changed: take the DEFAULTS rather than half-read
                    // an old file. Losing a preference is recoverable; showing
                    // a wrong map is a support call.
                    Log.w(TAG, "map_keys.json version $v != $VERSION -- defaults")
                    reset()
                } else {
                    land = o.optString("land", "ALL").uppercase()
                    use = o.optString("use", "ALL").uppercase()
                    off = HashSet()
                    val a = o.optJSONArray("off") ?: JSONArray()
                    for (i in 0 until a.length()) off.add(a.getString(i))
                    style = HashMap()
                    val s = o.optJSONObject("style")
                    if (s != null) {
                        for (k in s.keys()) {
                            val e = s.optJSONObject(k) ?: continue
                            style[k] = Triple(
                                e.optString("c", "#00FFFF"),
                                if (e.isNull("d")) null else e.optString("d"),
                                e.optInt("w", 2)
                            )
                        }
                    }
                }
            } else {
                reset()
            }
        } catch (e: Exception) {
            // A corrupt file must not stop the map opening.
            Log.e(TAG, "load failed, using defaults: ${e.message}")
            reset()
        }
        regenerate()
        loaded = true
        Log.i(TAG, "loaded: land=$land use=$use off=${off.size} -> $cachedWhere")
    }

    private fun reset() {
        land = "ALL"; use = "ALL"; off = HashSet(); style = HashMap()
    }

    // ── the predicate ───────────────────────────────────────────────────

    /**
     * The WHERE fragment, ready to append. Starts with AND, or is empty when
     * nothing is filtered.
     *
     * ⚠ Throws if [load] has not run. That is deliberate — see cachedWhere.
     */
    fun where(): String = cachedWhere
        ?: throw IllegalStateException(
            "TrailFilterState.where() before load(). The map composable must " +
                "call load() before the WebView is constructed.")

    /** Same, but never throws — for paths that legitimately run early. */
    fun whereOrEmpty(): String = cachedWhere ?: ""

    @Synchronized
    private fun regenerate() {
        val sb = StringBuilder()
        if (land == "PUBLIC" || land == "PRIVATE") {
            sb.append(" AND t.land_status='").append(land).append("'")
        }
        if (use == "MOTORIZED" || use == "NON-MOTORIZED") {
            sb.append(" AND t.use_type='").append(use).append("'")
        }
        if (off.isNotEmpty()) {
            // ⚠ Categories are our own controlled vocabulary, not rider text,
            // so they cannot carry a quote. The apostrophe strip is belt and
            // braces: a category that somehow did would break the statement.
            sb.append(" AND t.carto_code NOT IN (")
            sb.append(off.joinToString(",") { "'" + it.replace("'", "") + "'" })
            sb.append(")")
        }
        cachedWhere = sb.toString()
    }

    // ── mutation, from the panel ────────────────────────────────────────

    @Synchronized
    fun setLand(v: String) { land = v.uppercase(); regenerate(); save() }

    @Synchronized
    fun setUse(v: String) { use = v.uppercase(); regenerate(); save() }

    @Synchronized
    fun toggleCategory(name: String) {
        if (!off.remove(name)) off.add(name)
        regenerate(); save()
    }

    /** Whole column on or off — the header checkbox. */
    @Synchronized
    fun setGroup(names: List<String>, on: Boolean) {
        if (on) off.removeAll(names.toSet()) else off.addAll(names)
        regenerate(); save()
    }

    @Synchronized
    fun setStyle(name: String, colour: String, dash: String?, weight: Int) {
        style[name] = Triple(colour, dash, weight)
        save()
    }

    fun isOn(name: String): Boolean = !off.contains(name)

    // ── save ────────────────────────────────────────────────────────────

    @Synchronized
    fun save() {
        try {
            val o = JSONObject()
            o.put("version", VERSION)
            o.put("land", land)
            o.put("use", use)
            o.put("off", JSONArray(off.toList()))
            val s = JSONObject()
            for ((k, v) in style) {
                s.put(k, JSONObject()
                    .put("c", v.first)
                    .put("d", v.second ?: JSONObject.NULL)
                    .put("w", v.third))
            }
            o.put("style", s)
            val f = file()
            f.parentFile?.mkdirs()
            // ⚠ Write to a temp and rename: a half-written file on a kill would
            // fail the version check and silently reset the rider's panel.
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(o.toString(2))
            if (!tmp.renameTo(f)) {
                f.writeText(o.toString(2))
                tmp.delete()
            }
        } catch (e: Exception) {
            // A failed save must never stop the rider using the map.
            Log.e(TAG, "save failed: ${e.message}")
        }
    }

    /** The style map as JSON, for handing to the WebView. */
    fun styleJson(): String {
        val s = JSONObject()
        for ((k, v) in style) {
            s.put(k, JSONObject()
                .put("c", v.first)
                .put("d", v.second ?: JSONObject.NULL)
                .put("w", v.third))
        }
        return s.toString()
    }
}
