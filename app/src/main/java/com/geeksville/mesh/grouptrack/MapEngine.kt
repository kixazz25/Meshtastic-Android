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
    fun isVector(ctx: Context): Boolean = cached ?: (try {
        file(ctx).takeIf { it.exists() }?.readText()?.trim() == "VEC"
    } catch (e: Exception) {
        android.util.Log.e(TAG, "VECSWITCH-2026-10-30 read: ${e.message}"); false
    }).also { cached = it; android.util.Log.i(TAG, "VECSWITCH-2026-10-30 engine this run: " + if (it) "VEC (MapLibre)" else "SAT (Leaflet)") }

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
