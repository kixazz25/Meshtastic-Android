package com.geeksville.mesh.grouptrack

import android.content.Context
import android.content.Intent
import com.geeksville.mesh.convoy.GroupTrackStorage
import java.io.File

/**
 * VECSWITCH-2026-10-30 (2.7e step 4.2, Fred 11:36 / 13:37): THE MAP ENGINE -- SAT (Leaflet, convoy_map.html) or VEC (MapLibre,
 * vecmap_main.html, offline vector tiles). Kept in GroupTrack/map_engine.txt in the rider's own storage: it survives a reinstall,
 * app preferences do not. Absent or unreadable = SAT, so a tablet that never chose is unchanged.
 * ⚠ READ ONCE PER PROCESS. The map page is built once (and the web view is kept across screens), so the engine is decided at startup
 * and changes ONLY through switchTo, which ends and restarts GroupTrack.
 */
object GtMapEngine {
    private const val TAG = "MapEngine"
    private const val FILE = "map_engine.txt"
    @Volatile private var cached: Boolean? = null

    private fun file(ctx: Context) = File(GroupTrackStorage.rootReady(ctx), FILE)

    /** true = the vector engine (MapLibre). */
    // VECONE-2026-11-01: VECTOR IS THE ONLY MAP -- always true (a fresh install with no map_engine.txt fell back to Leaflet).
    // The Satellite / Leaflet path is unreachable; its code is deleted later (living master: delete dead Satellite/Leaflet code).
    fun isVector(ctx: Context): Boolean = cached ?: true.also { cached = it; android.util.Log.i(TAG, "VECONE-2026-11-01 engine this run: VEC (MapLibre) -- the only map") }

    /** VECPORTS-2026-10-31: the engine this run without a context (read once when the map page loads); false before that. */
    fun isVectorCached(): Boolean = true   // VECONE-2026-11-01: the only map

    /** VECCONVERT-2026-10-31: the conversion -- the vector map from this start on, NO restart (called before the map page loads). */
    fun useVector(ctx: Context) {
        try { file(ctx).writeText("VEC") } catch (e: Exception) { android.util.Log.e(TAG, "VECCONVERT engine write: ${e.message}") }
        cached = true
        android.util.Log.i(TAG, "VECCONVERT-2026-10-31 engine set to VEC (MapLibre) by the conversion")
    }

    /** Save the choice, then END and RESTART GroupTrack so the map is built fresh in the chosen engine. */
    fun switchTo(ctx: Context, vector: Boolean) {
        try { file(ctx).writeText(if (vector) "VEC" else "SAT") }
        catch (e: Exception) { android.util.Log.e(TAG, "VECSWITCH-2026-10-30 write: ${e.message}"); return }
        android.util.Log.i(TAG, "VECSWITCH-2026-10-30 switching to " + (if (vector) "VEC" else "SAT") + " -- restarting")
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        ctx.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }
}

/** VIEWHOUSE-2026-10-30 (Fred 14:02): the two view buttons -- 3D (tilt, terrain once the elevation source exists) and map orientation
 *  (north up / my direction of travel). Session state; the page does the drawing. */
object GtMapView {
    val tilt = androidx.compose.runtime.mutableStateOf(false)
    val headingUp = androidx.compose.runtime.mutableStateOf(false)
    val bearing = androidx.compose.runtime.mutableStateOf(0f)   // VECCOMPASS-2026-10-30: the map's bearing (degrees, from the page)
}
