package com.grouptrack.shared.mapsource

import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.log.gtLogger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.Volatile

/**
 * GtMapSpec — the shared port of 2.7e's VectorTiles and ElevationTiles source resolution
 * (2.8 step 17, G1c). STEP17G1C-2026-11-13
 *
 * ⭐ MOST OF G1c WAS ALREADY DONE. VectorTiles has 13 members and NINE of them are already front
 * doors to GtVectorQueue, certified at 16c (Droid OLD = NEW, iPad = Droid, 6,399 values):
 * recipeBoxes, boxesOf, squaresInBox, enqueueWithElevation x2, squaresFor, queueSquares,
 * queueSquaresNamed, queueFromRecipe. They stay exactly as they are. What was left is what this
 * file ports: the SOURCE SPEC — where the tile URLs come from, and the recipe's old-tile count.
 *
 * ⛔ NOTHING HERE IS SKIPPED. Every member has live callers, checked against the 20:04 index:
 *   oldTilesOf    EmergencyRestore:109, MapConversion:63, MapConversion:138
 *   downloadSources (both)  ConvoyDownloadWorker:83, :178, :179
 *   tileTemplate  VectorMapTest:238 (and internally by downloadSources)
 *   urlTemplate   VectorMapTest:244
 *   Z_MAX (both)  7 references each
 *
 * ══════════════════════════════════════════════════════════════════════════════
 *  THE SEAMS — three, all already existing patterns
 * ══════════════════════════════════════════════════════════════════════════════
 *  readAsset(name)  map_sources.json ships in the APK's assets. Android's AssetManager has no
 *                   common equivalent; on the iPad it is a bundle resource. Same shape as
 *                   GtImportEnv:81 / GtRides:145 / GtHelpAssets.
 *  httpGet(url, ms) the TileJSON fetch. OpenFreeMap VERSIONS its tile path, so the template cannot
 *                   be a constant — it is read from the TileJSON at runtime. Same shape as
 *                   GtHttp.get (GtImportEnv:68).
 *  ⚠ NO FILE SEAM. 2.7e's oldTilesOf takes a java.io.File and calls readText(). Here it takes the
 *  TEXT. Reading the file is the front door's job, which keeps this file free of filesystem access
 *  and makes the member trivially testable with a fixture string.
 *
 * ⛔ JSON IS STRICT HERE, LENIENT IN 2.7e. 2.7e uses org.json (lenient); this uses GtOrgJson
 * (strict) — the GtConfigs precedent from G2g. A malformed map_sources.json or TileJSON body can
 * therefore be accepted by OLD and rejected by NEW. That divergence is RECORDED, NOT REPRODUCED:
 * the certification scripts a malformed fixture so the difference is measured rather than assumed,
 * and both sides' catch-all returns the same "give up" answer (null / 0) in any case.
 *
 * ⚠ PORTED AS IS, WARTS INCLUDED:
 *   - spec() re-reads and re-parses the whole asset on EVERY call. 2.7e does; so does this.
 *   - tileTemplate caches the resolved template FOREVER in a @Volatile var, with no invalidation.
 *     If OpenFreeMap re-versions its path mid-session, 2.7e keeps the stale one until the process
 *     restarts. Reproduced exactly — see [resetForCert] for how the test gets around it.
 *   - a failed TileJSON fetch is NOT cached, so every call retries. Also 2.7e's behaviour.
 */
object GtMapSpec {

    const val VEC_SLOT = "VEC"
    const val VEC_Z_MAX = 14    // VECENQUEUE-2026-10-30: OpenFreeMap ends at z14; never list tiles above it
    const val DEM_SLOT = "DEM"
    const val DEM_Z_MAX = 12    // DEMSOURCE-2026-10-30: elevation to z12; MapLibre stretches it closer in

    private const val ASSET = "map_sources.json"
    private val log = gtLogger("GtMapSpec")

    /** ⛔ 2.7e's `@Volatile private var resolved` in VectorTiles. Cached for the process's life, never
     *  invalidated. Requires kotlin.concurrent.Volatile in commonMain (the G2g lesson). */
    @Volatile
    private var resolved: String? = null

    /** TEST-ONLY: 2.7e has no such function — the cache is only clearable by restarting the process.
     *  The certification needs to drive the cold path repeatedly, so the OLD side gets the same hook.
     *  ⚠ Not a behaviour difference: production never calls it. */
    fun resetForCert() { resolved = null }

    /** One layer the worker should download. Shared's own type — see the note on G1e below. */
    /** ⛔ FIELD NAMES MIRROR 2.7e's MapSourceManager.TileLayer EXACTLY (role, urlTemplate,
     *  tileFormat, cacheDir, renderOrder) -- not "improved" to format/order. Same lesson as
     *  maxZoomForSource's ignored parameter: the port reproduces the shape, so OLD and NEW line up
     *  field for field and the front door maps one to the other without a translation table. */
    data class GtTileLayerSpec(
        val role: String,
        val urlTemplate: String,
        val tileFormat: String,
        val cacheDir: String,
        val renderOrder: Int = 0,
    )

    /**
     * ⚠ WHY A SHARED TYPE RATHER THAN MapSourceManager.TileLayer: that type belongs to
     * MapSourceManager, which is G1e (599 lines, 20 callers) and not ported yet. Blocking G1c on
     * G1e would invert the inventory's order for no benefit. The Android front door maps this to
     * MapSourceManager.TileLayer field for field; the certification compares the FIELDS, never the
     * type, exactly as G1a compared GtTileKey to TileKey by value.
     */

    /** 2.7e: JSONObject(assetText).optJSONArray(array)?.optJSONObject(0).
     *  ⚠ GtOrgJson has no optJSONArray/optJSONObject equivalents -- it is a VALUE helper over kotlinx
     *  elements -- so the navigation is done with kotlinx casts. A wrong type yields null, which is
     *  what org.json's optJSONArray/optJSONObject also do (they return null rather than throwing). */
    private fun specOf(env: GtMapSpecEnv, array: String): JsonObject? = try {
        val root = GtOrgJson.parse(env.readAsset(ASSET)) as? JsonObject
        (root?.get(array) as? JsonArray)?.getOrNull(0) as? JsonObject
    } catch (e: Exception) {
        log.e { "$ASSET: ${e.message}" }   // 2.7e logs and returns null
        null
    }

    // ---- vector ---------------------------------------------------------

    /** 2.7e VectorTiles.tileTemplate. The {z}/{x}/{y} template from the TileJSON — OpenFreeMap
     *  versions its tile path, so it cannot be a constant. Null = not reachable now. */
    fun vectorTileTemplate(env: GtMapSpecEnv): String? {
        resolved?.let { return it }
        // 2.7e: spec(context)?.optString("tilejson")?.takeIf { it.isNotBlank() }
        // GtOrgJson.optString returns the fallback "" for a missing key, exactly as org.json's does,
        // so takeIf { isNotBlank() } lands on null either way.
        val tj = specOf(env, "vector_sources")
            ?.let { GtOrgJson.optString(it, "tilejson") }?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val body = env.httpGet(tj, 6_000)
            if (body == null) { log.e { "TileJSON not fetched" }; return null }
            // 2.7e: JSONObject(body).optJSONArray("tiles")?.optString(0)
            val tiles = (GtOrgJson.parse(body) as? JsonObject)?.get("tiles") as? JsonArray
            val t = if (tiles == null) null else GtOrgJson.optString(tiles, 0)
            if (t.isNullOrBlank()) { log.e { "TileJSON has no tiles" }; null }
            else { resolved = t; log.i { "VECSOURCE-2026-10-30 tiles: $t" }; t }
        } catch (e: Exception) { log.e { "TileJSON: ${e.message}" }; null }
    }

    /** 2.7e VectorTiles.downloadSources. Role "overlay" = stored as received; vector tiles are
     *  protobuf and re-encoding them as an image would corrupt them. */
    fun vectorDownloadSources(env: GtMapSpecEnv): List<Pair<String, List<GtTileLayerSpec>>> {
        val t = vectorTileTemplate(env) ?: return emptyList()
        return listOf(VEC_SLOT to listOf(GtTileLayerSpec("overlay", t, "pbf", VEC_SLOT, 0)))
    }

    // ---- elevation ------------------------------------------------------

    /** 2.7e ElevationTiles.urlTemplate. The map's online fill for a tile that is not stored. */
    fun elevationUrlTemplate(env: GtMapSpecEnv): String? =
        specOf(env, "elevation_sources")
            ?.let { GtOrgJson.optString(it, "url_template") }?.takeIf { it.isNotBlank() }

    /** 2.7e ElevationTiles.downloadSources. ⛔ Role "overlay" = stored AS RECEIVED: the pixel
     *  colours ARE the heights, so re-encoding would put spikes and pits in the 3D terrain. */
    fun elevationDownloadSources(env: GtMapSpecEnv): List<Pair<String, List<GtTileLayerSpec>>> {
        val t = elevationUrlTemplate(env) ?: return emptyList()
        return listOf(DEM_SLOT to listOf(GtTileLayerSpec("overlay", t, "png", DEM_SLOT, 0)))
    }

    // ---- the recipe's old-tile count ------------------------------------

    /**
     * 2.7e VectorTiles.oldTilesOf. The recipe's tile total over the OLD map slots — every slot but
     * VEC and DEM — which is what "your old maps: N tiles" reports.
     * ⚠ Takes the recipe's TEXT, not a File (see the seam note above). 2.7e swallows every failure
     * and answers 0; so does this.
     */
    fun oldTilesOf(recipeText: String): Long = try {
        val slots = (GtOrgJson.parse(recipeText) as? JsonObject)?.get("slots") as? JsonArray
        var t = 0L
        if (slots != null) for (i in 0 until slots.size) {
            val o = slots.getOrNull(i) as? JsonObject ?: continue   // 2.7e: optJSONObject(i) ?: continue
            val s = GtOrgJson.optString(o, "slot")
            if (s != VEC_SLOT && s != DEM_SLOT) t += GtOrgJson.optLong(o, "tiles")
        }
        t
    } catch (e: Exception) { 0L }
}

/**
 * The G1c seam. Two methods, both already-established shapes — readAsset as in GtImportEnv:81 and
 * GtHelpAssets, httpGet as in GtHttp.get (GtImportEnv:68).
 */
interface GtMapSpecEnv {
    /** The APK asset's text. On the iPad, a bundle resource. Throws if missing — 2.7e's spec()
     *  catches the throw and returns null, so the catch stays in GtMapSpec, not here. */
    fun readAsset(name: String): String
    /** GET returning the body, or null on any failure (non-success status included). 2.7e logs the
     *  HTTP code and returns null; the code itself is an observation, never a compared decision. */
    fun httpGet(url: String, timeoutMs: Int): String?
}
