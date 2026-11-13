package com.geeksville.mesh.convoy

import com.grouptrack.shared.mapsource.GtMapSpecEnv
import org.json.JSONObject

/**
 * MapSpecCertOld — 2.7e's VectorTiles and ElevationTiles source resolution, AS IT IS, for the G1c
 * certification. STEP17G1C-CERTOLD-2026-11-13 ⚠ TEST-ONLY (CODE RULE 3: remove with GtCertRun
 * before 2.8 is released).
 *
 * ⛔ DO NOT EDIT TO MAKE A TEST PASS. This is the OLD side. If OLD and NEW differ, the port is
 * wrong, not this file.
 *
 * ⭐ IT KEEPS org.json ON PURPOSE. That is the entire point of the comparison: 2.7e parses with
 * org.json (LENIENT); the shared port parses with GtOrgJson over kotlinx (STRICT — its own header
 * says so: "NOT REPRODUCED ... comments, single quotes, unquoted keys"). The certification SCRIPTS a
 * malformed fixture so that divergence is MEASURED, not assumed. This is the GtConfigs precedent
 * from G2g, where the same difference was recorded rather than chased.
 *
 * ⭐ BOTH SIDES TAKE THE SAME [GtMapSpecEnv] — the G2f rule. One scripted asset text, one scripted
 * HTTP answer, handed to OLD and NEW alike, so a difference cannot come from the network moving or
 * the asset differing.
 *
 * ══════════════════════════════════════════════════════════════════════════════
 *  THE SUBSTITUTION MANIFEST — COUNTED
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  1. object names                                                            ×2
 *       VectorTiles / ElevationTiles  ->  this one object, members prefixed
 *     (2.7e has two objects; both are small and share the asset, so the OLD side keeps them
 *     together and names the members vector* / elevation*, matching GtMapSpec's shape. ⚠ The
 *     BODIES are unchanged — only the enclosing object and the member names.)
 *
 *  2. THE ASSET READ BECOMES A SEAM                                           ×2
 *       context.assets.open("map_sources.json").bufferedReader().use { it.readText() }
 *         ->  env.readAsset("map_sources.json")
 *     at 2.7e VectorTiles:19 and ElevationTiles:15.
 *     ⛔ WHY: AssetManager is Android-only. ⚠ NOTE the asset text itself is NOT invented by the
 *     harness — it reads the REAL map_sources.json out of the APK and hands the same string to both
 *     sides, because the shipped asset is deterministic. Only the malformed cases are synthetic.
 *
 *  3. THE TileJSON FETCH BECOMES A SEAM                                       ×1
 *       OkHttpClient.Builder().callTimeout(6, SECONDS).build().newCall(Request(url)).execute()
 *       ...  r.isSuccessful / r.body?.string()
 *         ->  env.httpGet(tj, 6_000)   (null = any failure, including a non-success status)
 *     at 2.7e VectorTiles:26-28.
 *     ⛔ WHY: the network cannot be in a certification. ⚠ 2.7e LOGS the HTTP code and returns null;
 *     the code is an observation, never a compared decision, so collapsing every failure to null
 *     loses nothing that is compared.
 *
 *  4. oldTilesOf TAKES THE TEXT, NOT A FILE                                   ×1
 *       fun oldTilesOf(f: java.io.File)  ...  JSONObject(f.readText())
 *         ->  fun oldTilesOf(recipeText: String)  ...  JSONObject(recipeText)
 *     at 2.7e VectorTiles:51-57. Reading the file is the front door's job on both sides.
 *
 *  5. resetForCert ADDED                                                      ×1
 *     2.7e has no such function: `resolved` is clearable only by restarting the process. The test
 *     must drive the cold path repeatedly, so both sides get the same hook. Production never calls it.
 *
 * ⚠ NOT PRESENT: the nine queue members (recipeBoxes, boxesOf, squaresInBox, enqueueWithElevation
 * ×2, squaresFor, queueSquares, queueSquaresNamed, queueFromRecipe). They are already front doors to
 * GtVectorQueue, CERTIFIED AT 16c — 6,399 values, Droid OLD = NEW and iPad = Droid. Re-testing them
 * here would re-test closed work and put a second uncertified copy on the OLD side.
 * ⚠ MapSourceManager.TileLayer IS NOT SUBSTITUTED: the Android side has the real type, and the
 * certification compares the FIELDS (role, url, format, cacheDir, order), never the type — exactly
 * as G1a compared TileKey to GtTileKey by value.
 */
object MapSpecCertOld {

    private const val TAG = "MapSpecCertOld"
    private const val ASSET = "map_sources.json"

    @Volatile private var resolved: String? = null

    /** Substitution 5. */
    fun resetForCert() { resolved = null }

    /** 2.7e VectorTiles:19 / ElevationTiles:15, with substitution 2. */
    private fun spec(env: GtMapSpecEnv, array: String): JSONObject? = try {
        JSONObject(env.readAsset(ASSET)).optJSONArray(array)?.optJSONObject(0)
    } catch (e: Exception) { android.util.Log.e(TAG, "$ASSET: ${e.message}"); null }

    /** 2.7e VectorTiles.tileTemplate, with substitution 3. */
    fun vectorTileTemplate(env: GtMapSpecEnv): String? {
        resolved?.let { return it }
        val tj = spec(env, "vector_sources")?.optString("tilejson")?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val body = env.httpGet(tj, 6_000)
            if (body == null) { android.util.Log.e(TAG, "TileJSON not fetched"); return null }
            val t = JSONObject(body).optJSONArray("tiles")?.optString(0)
            if (t.isNullOrBlank()) { android.util.Log.e(TAG, "TileJSON has no tiles"); null }
            else { resolved = t; android.util.Log.i(TAG, "VECSOURCE-2026-10-30 tiles: $t"); t }
        } catch (e: Exception) { android.util.Log.e(TAG, "TileJSON: ${e.message}"); null }
    }

    /** 2.7e VectorTiles.downloadSources. */
    fun vectorDownloadSources(env: GtMapSpecEnv): List<Pair<String, List<MapSourceManager.TileLayer>>> {
        val t = vectorTileTemplate(env) ?: return emptyList()
        return listOf("VEC" to listOf(MapSourceManager.TileLayer("overlay", t, "pbf", "VEC", 0)))
    }

    /** 2.7e ElevationTiles.urlTemplate. */
    fun elevationUrlTemplate(env: GtMapSpecEnv): String? =
        spec(env, "elevation_sources")?.optString("url_template")?.takeIf { it.isNotBlank() }

    /** 2.7e ElevationTiles.downloadSources. */
    fun elevationDownloadSources(env: GtMapSpecEnv): List<Pair<String, List<MapSourceManager.TileLayer>>> {
        val t = elevationUrlTemplate(env) ?: return emptyList()
        return listOf("DEM" to listOf(MapSourceManager.TileLayer("overlay", t, "png", "DEM", 0)))
    }

    /** 2.7e VectorTiles.oldTilesOf, with substitution 4. */
    fun oldTilesOf(recipeText: String): Long = try {
        val slots = JSONObject(recipeText).optJSONArray("slots"); var t = 0L
        if (slots != null) for (i in 0 until slots.length()) { val o = slots.optJSONObject(i) ?: continue
            val s = o.optString("slot"); if (s != "VEC" && s != "DEM") t += o.optLong("tiles") }
        t
    } catch (e: Exception) { 0L }
}
