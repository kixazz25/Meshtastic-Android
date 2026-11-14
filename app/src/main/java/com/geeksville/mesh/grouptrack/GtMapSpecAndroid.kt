package com.geeksville.mesh.grouptrack

import android.content.Context
import com.geeksville.mesh.convoy.MapSourceManager
import com.grouptrack.shared.mapsource.GtMapSpecEnv

/**
 * G1CSWITCH-2026-11-14 (2.8 step 17, G1c switch): Android's answers for the shared map-source spec
 * (com.grouptrack.shared.mapsource.GtMapSpec, certified 11-13 -- 4e9dc9db1, 64 keys, 0 DIFF on
 * Droid and iPad, twice). Same shape as GtStorageAndroid / GtSettingsAndroid / GtHkAndroid.
 *
 * The certification ran against Cert17g1c.FixtureEnv (scripted asset text + scripted HTTP bodies).
 * This is the REAL env the app uses; it must behave as that fixture's contract says:
 *   readAsset THROWS on failure -- GtMapSpec.specOf wraps it in try/catch, logs and returns null,
 *     which is exactly what 2.7e's own spec() did. Returning null here instead would skip that log.
 *   httpGet returns null for ANY failure, a non-success status included (MapSpecCertOld
 *     substitution 3: "the code is an observation, never a compared decision, so collapsing every
 *     failure to null loses nothing that is compared"). 2.7e logged the code; so does this.
 */
object GtMapSpecAndroid {
    private const val TAG = "GtMapSpecAndroid"

    /**
     * G1CDELEGATE-2026-11-14 (G1c option A): the shared layer spec mapped to MapSourceManager.TileLayer,
     * FIELD FOR FIELD -- role, urlTemplate, tileFormat, cacheDir, renderOrder, same names and order.
     * GtMapSpec deliberately mirrors that type rather than importing it, because MapSourceManager is
     * G1e (599 lines, 20 callers) and not ported yet; blocking G1c on G1e would invert the inventory
     * order for no gain. ⛔ THIS FUNCTION IS TEMPORARY: when G1e moves TileLayer into shared code,
     * delete it and let the callers take GtTileLayerSpec directly. One mapping, one place, so the
     * layer decision ("overlay" / "pbf" / slot / 0) is never written twice and cannot drift.
     */
    fun layers(
        spec: List<Pair<String, List<com.grouptrack.shared.mapsource.GtMapSpec.GtTileLayerSpec>>>
    ): List<Pair<String, List<MapSourceManager.TileLayer>>> = spec.map { (slot, ls) ->
        // LAYERSNAMED-2026-11-14: NAMED arguments, deliberately. role / urlTemplate / tileFormat / cacheDir are
        // ALL String, so a positional mapping with any two traded would COMPILE and DRAW correctly, and
        // only surface as tiles stored in the wrong cache dir or with the wrong format. Named arguments
        // make that a compile error instead of a defect a device test has to catch.
        slot to ls.map {
            MapSourceManager.TileLayer(
                role = it.role,
                urlTemplate = it.urlTemplate,
                tileFormat = it.tileFormat,
                cacheDir = it.cacheDir,
                renderOrder = it.renderOrder,
            )
        }
    }

    /** @param context any Context -- only assets are read from it, so an application context is fine. */
    class Env(private val context: Context) : GtMapSpecEnv {
        /** 2.7e: context.assets.open("map_sources.json").bufferedReader().use { it.readText() } */
        override fun readAsset(name: String): String =
            context.assets.open(name).bufferedReader().use { it.readText() }

        /** 2.7e VectorTiles:26-28 -- OkHttp with a callTimeout; [timeoutMs] is what the shared code asks for (6_000). */
        override fun httpGet(url: String, timeoutMs: Int): String? = try {
            okhttp3.OkHttpClient.Builder()
                .callTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
                .newCall(okhttp3.Request.Builder().url(url).build())
                .execute()
                .use { r ->
                    if (!r.isSuccessful) {
                        android.util.Log.e(TAG, "TileJSON HTTP ${r.code}")   // 2.7e logged the code too
                        null
                    } else r.body?.string()
                }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "TileJSON: ${e.message}")
            null
        }
    }
}
