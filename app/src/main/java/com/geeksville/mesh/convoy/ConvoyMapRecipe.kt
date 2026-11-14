package com.geeksville.mesh.convoy

// SWITCHMAPRECIPE-2026-11-14: the certified shared port and what it needs
import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.maprecipe.GtMapRecipe
import com.grouptrack.shared.maprecipe.GtRecipeEnv
import com.grouptrack.shared.maprecipe.GtRecipeResult
import com.grouptrack.shared.text.GtLocalTime
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath

import android.content.Context
import java.io.File

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
    /** VECCONVERT-2026-10-31: the record made when the old maps were converted -- Restart map conversion replays it. */
    /** SWITCHMAPRECIPE-2026-11-14: from the shared companion, so the two cannot drift. */
    const val CONVERSION = GtMapRecipe.CONVERSION

    // ---------------------------------------------------------------- SWITCHMAPRECIPE-2026-11-14
    /**
     * \u26d4 SWITCHED to the shared GtMapRecipe (certified 11-14: Droid 175 SAME / 0 DIFF /
     * 0 EXPECTED, iPad 175 SAME / 0 DIFF cross-device, two clean runs).
     *
     * THE IDIOM IS DELEGATION. Only three members are used outside this file -- CONVERSION,
     * writeDailyAsync and writeFresh -- and all three keep their signatures. Every comment in the
     * header above still governs; none of it is restated here.
     *
     * \u26a0 writeDailyAsync STAYS HERE. It is a Thread, i.e. platform, and was deliberately not
     * ported; it now wraps the delegating writeDaily.
     *
     * \u26a0 THREE SOCKETS, and why they are sockets and not ports: 2.7e reached
     * MapSourceManager.getSlotSources, MBTilesStore.storeExists and
     * DownloadQueueManager.deriveRecipe/queue directly. deriveRecipe runs the quadtree -- hundreds
     * of COUNT queries per slot against MBTilesStore -- so porting it HERE would have made the
     * recipe's certification depend on the tile store's. It is certified with pass 3 instead.
     */
    private fun env(context: Context): GtRecipeEnv = object : GtRecipeEnv {
        override fun slotSources(): List<Triple<String, String, String>> = MapSourceManager.getSlotSources()
        override fun storeExists(slot: String): Boolean = MBTilesStore.storeExists(slot)
        override fun deriveRecipe(slot: String): GtRecipeResult? =
            // RECIPETIGHT-2026-10-30: tight = true, boxes follow your maps
            DownloadQueueManager.deriveRecipe(context, slot, tight = true)?.let {
                // \u26a0 NAMED ARGUMENTS: GtRecipeResult mirrors RecipeResult field for field, and
                // refZ / dropped are both Int -- a positional swap would COMPILE.
                GtRecipeResult(
                    slot = it.slot, refZ = it.refZ, levels = it.levels,
                    boxes = it.boxes, dropped = it.dropped, totalTiles = it.totalTiles)
            }
        override fun vectorBusyCount(): Int {
            // 2.7e: init(context) THEN count -- the init is part of the check, not setup
            DownloadQueueManager.init(context)
            return DownloadQueueManager.queue.value.count {
                (it.status == QueueStatus.QUEUED || it.status == QueueStatus.DOWNLOADING ||
                    it.status == QueueStatus.PAUSED) &&
                    (it.refreshSlot == VectorTiles.SLOT || it.refreshSlot == ElevationTiles.SLOT)
            }
        }
        override val vecSlot: String = VectorTiles.SLOT
        override val demSlot: String = ElevationTiles.SLOT
    }

    private fun shared(context: Context): GtMapRecipe {
        val files = object : GtFiles {
            override val fs: FileSystem = FileSystem.SYSTEM
            override fun setLastModified(path: Path, epochMs: Long): Boolean = File(path.toString()).setLastModified(epochMs)
            override fun lastModified(path: Path): Long = File(path.toString()).lastModified()
            override fun list(dir: Path): List<Path> = File(dir.toString()).listFiles()?.map { it.toOkioPath() } ?: emptyList()
        }
        val paths = object : GtImportPaths {
            override fun groupTrackRoot(): Path = GroupTrackStorage.root().toOkioPath()
            override fun dir(name: String): Path = GroupTrackStorage.dir(name).toOkioPath()   // STORAGEROOT-2026-09-11
            override fun tracksDir(): Path = throw UnsupportedOperationException("ConvoyMapRecipe: recipes folder only")
            override fun cacheDir(): Path = throw UnsupportedOperationException("ConvoyMapRecipe: recipes folder only")
            override fun filesDir(): Path = throw UnsupportedOperationException("ConvoyMapRecipe: recipes folder only")
        }
        val clock = object : GtClock {
            override fun nowInstant(): String = java.time.Instant.now().toString()
            override fun localNow(): GtLocalTime {
                val t = java.time.LocalDateTime.now()   // DEVICE-LOCAL, as 2.7e's SimpleDateFormat
                return GtLocalTime(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)
            }
            override fun epochMs(): Long = System.currentTimeMillis()
        }
        return GtMapRecipe(files, paths, clock, env(context))
    }

    /** Background, best effort. Never throws into the caller. \u26a0 Platform -- not ported. */
    fun writeDailyAsync(context: Context) {
        val app = context.applicationContext
        Thread {
            try { writeDaily(app) } catch (e: Exception) {
                android.util.Log.e(TAG, "RECIPE-2026-08-12L daily recipe failed: ${e.message}")
            }
        }.start()
    }

    fun writeDaily(context: Context): File? =
        shared(context.applicationContext).writeDaily()?.let { File(it.toString()) }

    /** VECCONVERT-2026-10-31: the conversion's first step -- today's recipe rewritten from the tiles now. */
    fun writeFresh(context: Context): File? =
        shared(context.applicationContext).writeFresh()?.let { File(it.toString()) }
}
