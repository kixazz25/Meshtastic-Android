package com.geeksville.mesh.convoy

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * What C2 (REDUCE) extracts from the Geofabrik GPKG into the skinny DB.
 *
 * OSM-C2-CATALOG-2026-07-28
 *
 * WHY THIS IS DATA AND NOT CODE (Fred 2026-07-28): "a json to control items
 * placed in skinny import file ... so we edit json rather than recompile
 * code." Deciding whether 48k POIs help or clutter should cost a file push
 * and a re-extract, not a build.
 *
 * ⚠ AN ASSET ALONE WOULD NOT DELIVER THAT. Files under app/src/main/assets/
 * are baked into the APK -- editing trail_sources.json means a rebuild, and
 * this file would inherit exactly that limitation. So the catalog is read
 * from an ON-DISK OVERRIDE if one is present, and falls back to the bundled
 * asset otherwise:
 *
 *   override  /sdcard/Documents/GroupTrack/config/osm_layers.json
 *   fallback  assets/osm_layers.json
 *
 * The override directory is PUBLIC deliberately -- run-as is blocked on
 * release builds, so app-private storage would not be reachable by adb push.
 * Same reasoning that put the planning state file in Documents/GroupTrack.
 *
 * Users never have an override and always get the shipped catalog. There is
 * no seeding and no migration, so a stale on-disk copy can never shadow a
 * newer release's defaults except when someone deliberately put it there.
 *
 * ⚠ A BROKEN OVERRIDE MUST NOT BRICK THE FEATURE. Any parse or validation
 * failure logs loudly and falls back to the asset. The failure mode of a
 * hand-edited config file is a typo, and a typo should cost you your
 * customization -- not the import.
 */
object OsmLayerCatalog {

    private const val TAG = "OsmLayers"
    private const val ASSET_NAME = "osm_layers.json"

    /** Public on purpose: adb-pushable without run-as. */
    fun overrideFile(): File = File(
        Environment.getExternalStorageDirectory(),
        "Documents/GroupTrack/config/$ASSET_NAME"
    )

    /**
     * One extractable layer.
     *
     * ⚠ CODE RULE 1 -- filterColumn is nullable, and here is the case for it.
     * A caller LEGITIMATELY omits it: point layers have no meaningful subset.
     * You want all 1,581 places, and separating peaks from springs is a
     * DISPLAY decision made per zoom, not an extraction one. Absent therefore
     * means "take every row", which is a real state and not a deferred
     * decision. When filterColumn is present, filterValues must be non-empty
     * -- validate() enforces the pairing so the two can never disagree.
     */
    data class Layer(
        val id: String,
        val label: String,
        val enabled: Boolean,
        val required: Boolean,
        val sourceTable: String,
        val geometry: String,
        val targetTable: String,
        val columns: List<String>,
        val filterColumn: String?,
        val filterValues: List<String>
    ) {
        val isLine: Boolean get() = geometry == GEOM_LINE
        val isPoint: Boolean get() = geometry == GEOM_POINT
    }

    const val GEOM_LINE = "line"
    const val GEOM_POINT = "point"

    /**
     * The catalog, override-first. Never throws: worst case you get the
     * asset's contents, which are known good because they shipped.
     */
    fun load(ctx: Context): List<Layer> {
        val ov = overrideFile()
        if (ov.exists() && ov.isFile) {
            try {
                val text = ov.readText(Charsets.UTF_8)
                val layers = parse(text)
                validate(layers)
                Log.i(TAG, "catalog loaded from OVERRIDE ${ov.absolutePath} (${layers.size} layers)")
                logLayers(layers)
                return layers
            } catch (e: Exception) {
                Log.e(TAG, "OVERRIDE REJECTED (${e.javaClass.simpleName}: ${e.message}) -- " +
                    "falling back to bundled asset. Fix ${ov.absolutePath} or delete it.")
            }
        }
        return try {
            val text = ctx.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val layers = parse(text)
            validate(layers)
            Log.i(TAG, "catalog loaded from ASSET (${layers.size} layers)")
            logLayers(layers)
            layers
        } catch (e: Exception) {
            Log.e(TAG, "ASSET CATALOG UNREADABLE: ${e.javaClass.simpleName} ${e.message}")
            emptyList()
        }
    }

    /** Only the layers C2 will actually extract. */
    fun enabledLayers(ctx: Context): List<Layer> = load(ctx).filter { it.enabled }

    // -- parsing ------------------------------------------------------------

    fun parse(text: String): List<Layer> {
        val root = JSONObject(text)
        val arr = root.optJSONArray("layers")
            ?: throw IllegalArgumentException("no \"layers\" array")
        val out = mutableListOf<Layer>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val cols = mutableListOf<String>()
            o.optJSONArray("columns")?.let { c ->
                for (j in 0 until c.length()) cols.add(c.getString(j))
            }
            val fvals = mutableListOf<String>()
            o.optJSONArray("filter_values")?.let { f ->
                for (j in 0 until f.length()) fvals.add(f.getString(j))
            }
            val fcolRaw = o.optString("filter_column", "")
            out.add(
                Layer(
                    id = o.getString("id"),
                    label = o.optString("label", o.getString("id")),
                    enabled = o.optBoolean("enabled", false),
                    required = o.optBoolean("required", false),
                    sourceTable = o.getString("source_table"),
                    geometry = o.getString("geometry"),
                    targetTable = o.getString("target_table"),
                    columns = cols,
                    filterColumn = if (fcolRaw.isBlank()) null else fcolRaw,
                    filterValues = fvals
                )
            )
        }
        return out
    }

    /**
     * Throwing here rejects the OVERRIDE and falls back, so these checks cost
     * a user their customization and nothing more.
     */
    fun validate(layers: List<Layer>) {
        if (layers.isEmpty()) throw IllegalArgumentException("catalog is empty")

        val ids = layers.map { it.id }
        val dupe = ids.groupBy { it }.filterValues { it.size > 1 }.keys
        if (dupe.isNotEmpty()) throw IllegalArgumentException("duplicate layer id(s): $dupe")

        val tgts = layers.filter { it.enabled }.map { it.targetTable }
        val dupeT = tgts.groupBy { it }.filterValues { it.size > 1 }.keys
        if (dupeT.isNotEmpty())
            throw IllegalArgumentException("two enabled layers write the same target_table: $dupeT")

        for (l in layers) {
            if (l.geometry != GEOM_LINE && l.geometry != GEOM_POINT)
                throw IllegalArgumentException("layer '${l.id}': geometry must be " +
                    "'$GEOM_LINE' or '$GEOM_POINT', got '${l.geometry}'")
            if (l.sourceTable.isBlank())
                throw IllegalArgumentException("layer '${l.id}': source_table is blank")
            if (l.targetTable.isBlank())
                throw IllegalArgumentException("layer '${l.id}': target_table is blank")
            if (l.columns.isEmpty())
                throw IllegalArgumentException("layer '${l.id}': columns is empty")
            // See the Code Rule 1 note on Layer.filterColumn: absent is fine,
            // present-but-empty is a typo that would silently keep nothing.
            if (l.filterColumn != null && l.filterValues.isEmpty())
                throw IllegalArgumentException("layer '${l.id}': filter_column set " +
                    "but filter_values is empty -- that would extract nothing")

            // ⚠ This is a TRAIL importer. A config that turns trails off
            // produces a run that looks successful and imports nothing, which
            // is the worst kind of wrong.
            if (l.required && !l.enabled)
                throw IllegalArgumentException("layer '${l.id}' is required and cannot be disabled")
        }
    }

    private fun logLayers(layers: List<Layer>) {
        for (l in layers) {
            Log.i(TAG, "  [${if (l.enabled) "x" else " "}] ${l.id.padEnd(10)} " +
                "${l.sourceTable} -> ${l.targetTable} (${l.geometry})" +
                if (l.filterColumn != null) " filter ${l.filterColumn} in ${l.filterValues.size} values" else "")
        }
    }
}
