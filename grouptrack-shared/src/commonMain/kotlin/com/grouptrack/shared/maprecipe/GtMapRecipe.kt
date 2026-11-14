package com.grouptrack.shared.maprecipe

import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.text.GtJava
import com.grouptrack.shared.text.GtMath
import com.grouptrack.shared.text.GtTime
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import okio.Path
import kotlin.math.PI

/**
 * GTMAPRECIPE-2026-11-14 (2.8 step 17, Phase 2 pass 2): 2.7e ConvoyMapRecipe, ported AS IS.
 *
 * THE DAILY MAP RECIPE: a few KB describing what would otherwise be 15 GB to rebuild by hand.
 * Every comment in 2.7e's header still governs and is not repeated here -- in particular that it is
 * BEST EFFORT THROUGHOUT (every failure logged and swallowed; a DR convenience must never stop the
 * app starting) and that HISTORY is what makes it safe (60 days kept, so no queue / migration /
 * health guard beyond the date).
 *
 * THREE SOCKETS, AND WHY (the reason this pass does not wait for pass 3):
 * 2.7e's write() reaches MBTilesStore.storeExists and DownloadQueueManager.deriveRecipe/queue
 * directly. Both are unported -- deriveRecipe runs the quadtree, hundreds of COUNT queries per slot
 * against MBTilesStore. Porting them HERE would make pass 2 depend on pass 3 and would certify the
 * quadtree as a side effect of certifying the recipe. They are injected instead ([GtRecipeEnv]), so
 * this port owns exactly what the recipe decides: which slots, the coordinate conversion, the
 * document shape, the date/skip logic and the prune cutoff. The quadtree is certified with pass 3.
 *
 * ⛔ latOf KEEPS 2.7e's EXPRESSION AND MUST NOT BE "SHARED" WITH GtVectorQueue.
 * GtVectorQueue.latOf computes  PI - 2.0 * PI * y / n.
 * 2.7e ConvoyMapRecipe computes PI * (1.0 - 2.0 * y / n).
 * Algebraically identical; NOT identical in floating point. Measured 11-14 over z0-z18: they differ
 * on 59 of the k values tested, worst latitude difference 2.1e-14 degrees -- about 2 nanometres,
 * physically nothing, but the certification compares SERIALIZED VALUES, so it is a DIFF. Replacing
 * this with GtVectorQueue's form would be an unfaithful port that looks like a free refactor.
 * GtMath.sinh / atan and GtJava.toDegrees are the certified step-16a routings (fdlibm on the iPad,
 * because Apple's libm differs from the Droid).
 *
 * ⚠ writeDailyAsync IS NOT PORTED. It is a Thread, i.e. platform. Android keeps its own
 * writeDailyAsync and calls [writeDaily] inside it.
 */
class GtMapRecipe(
    private val files: GtFiles,
    private val paths: GtImportPaths,
    private val clock: GtClock,
    private val env: GtRecipeEnv,
) {

    companion object {
        const val EXT = ".gtmaps"
        const val PREFIX = "grouptrack_maps_"
        const val KEEP_DAYS = 60
        /** VECCONVERT-2026-10-31: the record made when the old maps were converted. */
        const val CONVERSION = "grouptrack_conversion.gtmaps"

        /** 2.7e: x.toDouble() / n * 360.0 - 180.0 */
        fun lonOf(x: Long, n: Long): Double = x.toDouble() / n * 360.0 - 180.0

        /** 2.7e: Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / n)))) -- see the class note. */
        fun latOf(y: Long, n: Long): Double =
            GtJava.toDegrees(GtMath.atan(GtMath.sinh(PI * (1.0 - 2.0 * y.toDouble() / n))))
    }

    private fun dir(): Path = paths.dir("recipes")   // 2.7e GroupTrackStorage.dir("recipes")

    /** 2.7e SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) -- DEVICE-LOCAL, not UTC. */
    private fun today(): String = GtTime.local(clock.localNow(), "yyyy-MM-dd")

    fun writeDaily(): Path? = write(fresh = false)

    /** VECCONVERT-2026-10-31: the conversion's first step -- today's recipe rewritten from the tiles now. */
    fun writeFresh(): Path? = write(fresh = true)

    // CODE RULE 1: fresh is required -- writeDaily (false) and writeFresh (true) each state it.
    private fun write(fresh: Boolean): Path? {
        val target = dir() / (PREFIX + today() + EXT)
        if (fresh) {
            try { files.fs.delete(target) } catch (e: Exception) { }   // 2.7e target.delete(), result ignored
        } else if (files.fs.exists(dir() / CONVERSION)) {
            // 2.7e: not while the conversion's downloads run -- yesterday's file stays the newest.
            // The socket does the init + the count, because 2.7e's check is init-then-count.
            if (env.vectorBusyCount() > 0) return null
        }
        if (files.fs.exists(target)) {
            prune()                     // 2.7e prunes on the already-written path too
            return target
        }
        var any = false
        val doc = try {
            val slotsJson = buildJsonArray {
                // 2.7e: (getSlotSources().map { it.first } + listOf(VEC, DEM)).distinct() -- ORDER IS PRESERVED
                for (slot in (env.slotSources().map { it.first } + listOf(env.vecSlot, env.demSlot)).distinct()) {
                    if (!env.storeExists(slot)) continue
                    val r = env.deriveRecipe(slot) ?: continue
                    val n = 1L shl r.refZ
                    add(buildJsonObject {
                        put("slot", r.slot)
                        put("source", env.slotSources().find { it.first == r.slot }?.third ?: "")
                        put("zMin", r.levels.min())
                        put("zMax", r.levels.max())
                        put("tiles", r.totalTiles)
                        put("boxes", buildJsonArray {
                            for (b in r.boxes) add(buildJsonObject {
                                put("n", latOf(b[2], n)); put("s", latOf(b[3] + 1, n))
                                put("w", lonOf(b[0], n)); put("e", lonOf(b[1] + 1, n))
                                put("tiles", b[4])
                            })
                        })
                    })
                    any = true
                }
            }
            if (!any) return null        // 2.7e: "nothing stored in any slot - no recipe"
            buildJsonObject {
                put("version", 1)
                put("created", GtTime.local(clock.localNow(), "yyyy-MM-dd'T'HH:mm:ss"))
                put("slots", slotsJson)
            }
        } catch (e: Exception) {
            return null                  // BEST EFFORT: 2.7e swallows and returns null
        }
        return try {
            files.fs.createDirectories(dir())                 // 2.7e dir().mkdirs()
            files.fs.write(target) { writeUtf8(doc.toString()) }
            prune()
            target
        } catch (e: Exception) {
            null                         // 2.7e: write failed -> null
        }
    }

    /** 2.7e: anything older than KEEP_DAYS goes. Best effort; a failure is swallowed. */
    private fun prune() {
        try {
            val cutoff = clock.epochMs() - KEEP_DAYS * 24L * 60L * 60L * 1000L
            for (f in files.list(dir())) {
                val name = f.name
                if (name.startsWith(PREFIX) && name.endsWith(EXT) && files.lastModified(f) < cutoff) {
                    try { files.fs.delete(f) } catch (e: Exception) { }
                }
            }
        } catch (e: Exception) {
            // 2.7e logs and swallows
        }
    }
}

/** 2.7e ConvoyDownloadQueue.RecipeResult, mirrored field for field. */
data class GtRecipeResult(
    val slot: String,
    val refZ: Int,
    val levels: List<Int>,
    val boxes: List<LongArray>,
    val dropped: Int,
    val totalTiles: Long,
)

/**
 * What the recipe needs from the rest of the app. See the class note for why these are sockets and
 * not ports: each one is a DIFFERENT service, certified with its own object.
 */
interface GtRecipeEnv {
    /** 2.7e MapSourceManager.getSlotSources(): (slot, short label, source id). */
    fun slotSources(): List<Triple<String, String, String>>
    /** 2.7e MBTilesStore.storeExists(slot). */
    fun storeExists(slot: String): Boolean
    /** 2.7e DownloadQueueManager.deriveRecipe(context, slot, tight = true). */
    fun deriveRecipe(slot: String): GtRecipeResult?
    /** 2.7e: init(context), then count QUEUED/DOWNLOADING/PAUSED jobs whose refreshSlot is VEC or DEM. */
    fun vectorBusyCount(): Int
    val vecSlot: String
    val demSlot: String
}
