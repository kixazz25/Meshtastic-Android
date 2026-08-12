package com.geeksville.mesh.convoy

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RECIPE-2026-08-12L: THE DAILY MAP RECIPE.
 *
 * A few KB describing what would otherwise be 15 GB to rebuild by hand.
 *
 * WHY: 08-11 established that a fresh install destroys map data in a way that
 * cannot be prevented or recovered from. This does not save the tiles - it
 * makes losing them a DOWNLOAD instead of a DISASTER.
 *
 * WHAT IT IS: the quadtree boxes from DownloadQueueManager.deriveRecipe, per
 * slot, as lat/lon. ⭐ Derived by the SAME code a real rebuild uses, so a recipe
 * cannot describe something a rebuild would not queue.
 *
 * CADENCE: startup, once per DAY - today's file existing is the skip condition.
 * The quadtree runs a COUNT per quadrant per level, a few hundred queries per
 * slot, so once per launch would be wasteful.
 *
 * ⭐ HISTORY IS WHAT MAKES IT SAFE. Sixty days are kept. If today's run happens
 * mid-rebuild and captures half the coverage, yesterday's file is still there -
 * so there is NO queue check, NO migration check, NO "is the store healthy"
 * guard and NO skip logic beyond the date. Just write it.
 *
 * ⚠ The DATE IN THE FILENAME IS THE INDEX. No prefs, no manifest-of-manifests,
 * nothing to keep in sync - and prefs are wiped by exactly the events this
 * protects against.
 *
 * ⚠ ALL SLOTS IN ONE FILE. A DR file capturing only SAT would be a bad recipe
 * on the day it is needed.
 *
 * ⚠ BEST EFFORT THROUGHOUT. Every failure is logged and swallowed: a DR
 * convenience must never be able to stop the app starting.
 *
 * ⚠ THE RESTORE IS NOT BUILT YET, DELIBERATELY. Fred: ship the writer first,
 * because recipes have to exist before anyone needs one.
 * ⛔ AND WHEN IT IS BUILT, THE REPLAY MUST BE DownloadType.AREA WITH
 * DownloadPriority.REFRESH - separate fields, deliberately mismatched. AREA
 * because the worker filters MAP_SOURCE_REFRESH jobs to hasTile, so a
 * refresh-typed replay onto a rebuilt store downloads NOTHING and every job
 * completes at zero LOOKING SUCCESSFUL.
 */
object ConvoyMapRecipe {
    private const val TAG = "MapRecipe"
    private const val EXT = ".gtmaps"
    private const val PREFIX = "grouptrack_maps_"
    private const val KEEP_DAYS = 60

    /**
     * The app's OWN folder, beside the stores it describes - not Downloads.
     * Downloads is the INBOX: a recipe mailed by someone else lands there, and
     * the panel lists the two separately so "came from outside" and "made here"
     * stay distinguishable.
     */
    private fun dir(): File =
        File(Environment.getExternalStorageDirectory(), "Documents/GroupTrack/recipes")

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Background, best effort. Never throws into the caller. */
    fun writeDailyAsync(context: Context) {
        val app = context.applicationContext
        Thread {
            try { writeDaily(app) } catch (e: Exception) {
                android.util.Log.e(TAG, "RECIPE-2026-08-12L daily recipe failed: ${e.message}")
            }
        }.start()
    }

    fun writeDaily(context: Context): File? {
        val target = File(dir(), PREFIX + today() + EXT)
        if (target.exists()) {
            android.util.Log.i(TAG, "RECIPE-2026-08-12L today's recipe already written")
            prune()
            return target
        }
        val slots = JSONArray()
        var grand = 0L
        for (slot in MapSourceManager.getSlotSources().map { it.first }) {
            val r = DownloadQueueManager.deriveRecipe(context, slot) ?: continue
            val boxes = JSONArray()
            val n = 1L shl r.refZ
            fun lonOf(x: Long) = x.toDouble() / n * 360.0 - 180.0
            fun latOf(y: Long) = Math.toDegrees(
                Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y.toDouble() / n))))
            for (b in r.boxes) {
                boxes.put(JSONObject().apply {
                    put("n", latOf(b[2])); put("s", latOf(b[3] + 1))
                    put("w", lonOf(b[0])); put("e", lonOf(b[1] + 1))
                    put("tiles", b[4])
                })
            }
            grand += r.totalTiles
            slots.put(JSONObject().apply {
                put("slot", r.slot)
                put("source", MapSourceManager.getSlotSources()
                    .find { it.first == r.slot }?.third ?: "")
                put("zMin", r.levels.min())
                put("zMax", r.levels.max())
                put("tiles", r.totalTiles)
                put("boxes", boxes)
            })
        }
        if (slots.length() == 0) {
            android.util.Log.i(TAG, "RECIPE-2026-08-12L nothing stored in any slot - no recipe")
            return null
        }
        val doc = JSONObject().apply {
            put("version", 1)
            put("created", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            put("slots", slots)
        }
        return try {
            dir().mkdirs()
            target.writeText(doc.toString())
            android.util.Log.i(TAG,
                "RECIPE-2026-08-12L wrote ${target.name}: ${slots.length()} slot(s), " +
                "$grand tile(s), ${target.length()} bytes")
            prune()
            target
        } catch (e: Exception) {
            android.util.Log.e(TAG, "RECIPE-2026-08-12L write failed: ${e.message}")
            null
        }
    }

    /** Anything older than KEEP_DAYS goes. Sixty files of a few KB is nothing. */
    private fun prune() {
        try {
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 60L * 60L * 1000L
            dir().listFiles()?.forEach { f ->
                if (f.name.startsWith(PREFIX) && f.name.endsWith(EXT) &&
                    f.lastModified() < cutoff) {
                    if (f.delete()) android.util.Log.i(TAG, "RECIPE-2026-08-12L pruned ${f.name}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "RECIPE-2026-08-12L prune failed: ${e.message}")
        }
    }
}
