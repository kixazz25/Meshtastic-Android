package com.grouptrack.shared.osm

import com.grouptrack.shared.imports.GtImportEnv
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.log.gtLogger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okio.Path

/**
 * STEP15G-2026-11-10 (2.8 step 15g): 2.7e's OsmLayerCatalog (OSM-C2-CATALOG-2026-07-28), PORTED AS IS -- what C2 (REDUCE) extracts
 * from the Geofabrik GeoPackage into the skinny DB, read override-first:
 *   override  GroupTrackStorage.dir("config")/osm_layers.json  (env.paths.dir("config"))
 *   fallback  the bundled asset osm_layers.json                (env.platform.readAsset)
 * A broken override logs loudly and falls back; an unreadable asset gives an EMPTY catalog (never throws) -- as 2.7e.
 * WHAT CHANGED, and only this: org.json -> GtOrgJson (getJSONObject / getString throw where Android's throw; optString / optBoolean
 * give Android's answers); File -> okio via env.files.fs; Log -> gtLogger (same tag).
 */
class GtOsmLayers(private val env: GtImportEnv) {

    private val log = gtLogger(TAG)
    private val fs get() = env.files.fs

    /** Public on purpose: adb-pushable without run-as (2.7e STORAGEROOT2-2026-09-11 note applies). */
    fun overrideFile(): Path = env.paths.dir("config") / ASSET_NAME

    /** The catalog, override-first. Never throws: worst case you get the asset's contents. */
    fun load(): List<Layer> {
        val ov = overrideFile()
        if (fs.exists(ov) && fs.metadataOrNull(ov)?.isRegularFile == true) {
            try {
                val text = fs.read(ov) { readUtf8() }
                val layers = parse(text)
                validate(layers)
                log.i { "catalog loaded from OVERRIDE $ov (${layers.size} layers)" }
                logLayers(layers)
                return layers
            } catch (e: Exception) {
                log.e { "OVERRIDE REJECTED (${e::class.simpleName}: ${e.message}) -- falling back to bundled asset. Fix $ov or delete it." }
            }
        }
        return try {
            val text = env.platform.readAsset(ASSET_NAME)
            val layers = parse(text)
            validate(layers)
            log.i { "catalog loaded from ASSET (${layers.size} layers)" }
            logLayers(layers)
            layers
        } catch (e: Exception) {
            log.e { "ASSET CATALOG UNREADABLE: ${e::class.simpleName} ${e.message}" }
            emptyList()
        }
    }

    /** Only the layers C2 will actually extract. */
    fun enabledLayers(): List<Layer> = load().filter { it.enabled }

    private fun logLayers(layers: List<Layer>) {
        for (l in layers) {
            log.i { "  [${if (l.enabled) "x" else " "}] ${l.id.padEnd(10)} " +
                "${l.sourceTable} -> ${l.targetTable} (${l.geometry})" +
                if (l.filterColumn != null) " filter ${l.filterColumn} in ${l.filterValues.size} values" else "" }
        }
    }

    /**
     * One extractable layer. CODE RULE 1 (2.7e's own note): filterColumn is nullable because a point layer LEGITIMATELY has no subset
     * -- absent means "take every row"; when present, filterValues must be non-empty ([validate] enforces the pairing).
     */
    data class Layer(
        val id: String, val label: String, val enabled: Boolean, val required: Boolean,
        val sourceTable: String, val geometry: String, val targetTable: String,
        val columns: List<String>, val filterColumn: String?, val filterValues: List<String>
    ) {
        val isLine: Boolean get() = geometry == GEOM_LINE
        val isPoint: Boolean get() = geometry == GEOM_POINT
    }

    companion object {
        private const val TAG = "OsmLayers"
        const val ASSET_NAME = "osm_layers.json"
        const val GEOM_LINE = "line"
        const val GEOM_POINT = "point"

        /** org.json's JSONException, as the shared code throws it (the message is Android's). */
        class JsonEx(msg: String) : RuntimeException(msg)

        private fun getString(o: JsonObject, k: String): String =
            if (o.containsKey(k)) GtOrgJson.valueText(o[k]) ?: "null" else throw JsonEx("No value for $k")
        private fun getString(a: JsonArray, i: Int): String =
            if (i in a.indices) GtOrgJson.valueText(a[i]) ?: "null" else throw JsonEx("Index $i out of range [0..${a.size})")
        private fun optArray(o: JsonObject, k: String): JsonArray? = o[k] as? JsonArray
        private fun getObject(a: JsonArray, i: Int): JsonObject {
            if (i !in a.indices) throw JsonEx("Index $i out of range [0..${a.size})")
            val e: JsonElement = a[i]
            return e as? JsonObject ?: throw JsonEx("Value ${if (e is JsonNull) "null" else GtOrgJson.valueText(e)} at $i of type " +
                "${if (e is JsonArray) "org.json.JSONArray" else "java.lang.String"} cannot be converted to JSONObject")
        }

        /** JSONObject(text): a top-level value that is not an object throws (org.json "cannot be converted to JSONObject"). */
        fun parse(text: String): List<Layer> {
            val root = GtOrgJson.parse(text) as? JsonObject ?: throw JsonEx("Value of type java.lang.String cannot be converted to JSONObject")
            val arr = optArray(root, "layers") ?: throw IllegalArgumentException("no \"layers\" array")
            val out = mutableListOf<Layer>()
            for (i in 0 until arr.size) {
                val o = getObject(arr, i)
                val cols = mutableListOf<String>()
                optArray(o, "columns")?.let { c -> for (j in 0 until c.size) cols.add(getString(c, j)) }
                val fvals = mutableListOf<String>()
                optArray(o, "filter_values")?.let { f -> for (j in 0 until f.size) fvals.add(getString(f, j)) }
                val fcolRaw = GtOrgJson.optString(o, "filter_column", "")
                out.add(Layer(
                    id = getString(o, "id"),
                    label = GtOrgJson.optString(o, "label", getString(o, "id")),
                    enabled = GtOrgJson.optBoolean(o, "enabled", false),
                    required = GtOrgJson.optBoolean(o, "required", false),
                    sourceTable = getString(o, "source_table"),
                    geometry = getString(o, "geometry"),
                    targetTable = getString(o, "target_table"),
                    columns = cols,
                    filterColumn = if (fcolRaw.isBlank()) null else fcolRaw,
                    filterValues = fvals
                ))
            }
            return out
        }

        /** Throwing here rejects the OVERRIDE and falls back -- as 2.7e. */
        fun validate(layers: List<Layer>) {
            if (layers.isEmpty()) throw IllegalArgumentException("catalog is empty")
            val ids = layers.map { it.id }
            val dupe = ids.groupBy { it }.filterValues { it.size > 1 }.keys
            if (dupe.isNotEmpty()) throw IllegalArgumentException("duplicate layer id(s): $dupe")
            val tgts = layers.filter { it.enabled }.map { it.targetTable }
            val dupeT = tgts.groupBy { it }.filterValues { it.size > 1 }.keys
            if (dupeT.isNotEmpty()) throw IllegalArgumentException("two enabled layers write the same target_table: $dupeT")
            for (l in layers) {
                if (l.geometry != GEOM_LINE && l.geometry != GEOM_POINT)
                    throw IllegalArgumentException("layer '${l.id}': geometry must be '$GEOM_LINE' or '$GEOM_POINT', got '${l.geometry}'")
                if (l.sourceTable.isBlank()) throw IllegalArgumentException("layer '${l.id}': source_table is blank")
                if (l.targetTable.isBlank()) throw IllegalArgumentException("layer '${l.id}': target_table is blank")
                if (l.columns.isEmpty()) throw IllegalArgumentException("layer '${l.id}': columns is empty")
                if (l.filterColumn != null && l.filterValues.isEmpty())
                    throw IllegalArgumentException("layer '${l.id}': filter_column set but filter_values is empty -- that would extract nothing")
                if (l.required && !l.enabled) throw IllegalArgumentException("layer '${l.id}' is required and cannot be disabled")
            }
        }
    }
}
