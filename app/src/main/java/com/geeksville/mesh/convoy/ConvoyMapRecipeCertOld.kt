package com.geeksville.mesh.convoy

import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.maprecipe.GtRecipeEnv
import okio.Path
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MAPRECIPECERTOLD-2026-11-14: the OLD side of the ConvoyMapRecipe certification (Cert17maprecipe).
 * 2.7e ConvoyMapRecipe's LOGIC, copied as is.
 *
 * WHAT STAYS 2.7e ON PURPOSE -- these are the differences the cert exists to measure:
 *   - org.json JSONObject / JSONArray for BUILDING and for READING BACK
 *   - java.lang.Math.toDegrees / atan / sinh / PI for the inverse mercator
 *   - java.text.SimpleDateFormat + java.util.Date for both date strings, in the DEVICE'S
 *     DEFAULT TIME ZONE exactly as 2.7e (no explicit TimeZone is set by 2.7e either)
 *
 * SUBSTITUTION MANIFEST -- counted, and these are the ONLY changes from 2.7e:
 *   1. object -> class(files, paths, clock, env)                                            x1
 *   2. GroupTrackStorage.dir("recipes")          -> paths.dir("recipes")                    x1
 *   3. MapSourceManager.getSlotSources()         -> env.slotSources()                       x2
 *   4. MBTilesStore.storeExists(slot)            -> env.storeExists(slot)                   x1
 *   5. DownloadQueueManager.init + queue.count   -> env.vectorBusyCount()                   x1
 *   6. DownloadQueueManager.deriveRecipe(ctx, slot, tight = true) -> env.deriveRecipe(slot) x1
 *   7. VectorTiles.SLOT / ElevationTiles.SLOT    -> env.vecSlot / env.demSlot               x2
 *   8. java.io.File + exists/delete/mkdirs/writeText/listFiles/lastModified -> okio via
 *      files.fs and files.list / files.lastModified                                         x9
 *   9. android.util.Log lines dropped (a cert reads return values, not logcat)              x7
 *  10. readBack is NOT here: 2.7e never reads a recipe back (the restore was never built), so a
 *      read-back function would be cert scaffolding inside a file whose whole claim is that it is
 *      a counted copy of 2.7e. The harness does the org.json read.
 *  12. SimpleDateFormat is pinned to UTC (2.7e leaves the device default zone)            x2
 *      \u26a0 THE ONE DELIBERATE DEVIATION FROM 2.7e IN THIS FILE, AND WHY: the Droid and the iPad
 *      simulator can sit in different zones, so a zone-dependent date would DIFF cross-device for a
 *      reason that is not the port. The ZONE is an INPUT, resolved by clock.localNow() / epochMs()
 *      -- sockets already certified in G2. What this cert measures is the FORMATTING, SimpleDateFormat
 *      against GtTime.local, on one instant. \u26d4 RESIDUAL GAP, RECORDED: the device-default-zone
 *      behaviour of 2.7e's own SimpleDateFormat call is therefore NOT exercised here.
 *  11. writeDailyAsync NOT copied -- it is a Thread, i.e. platform, and the cert drives
 *      writeDaily directly                                                                  x1
 *   TOTAL: 26 substitutions (10 and 11 are omissions, not edits). Nothing else changes -- not a default, not a branch, not an order.
 *
 * ⚠ WHY 3/4/5/6/7 ARE SUBSTITUTIONS AND NOT A REWRITE: 2.7e reaches those four objects
 * directly, so an OLD side that kept them could not be driven by a fixture at all -- it would read
 * the device's real stores and real queue, and the two sides would be compared on different inputs.
 * Both sides take the SAME env, so they see identical slots, stores, recipes and busy counts.
 */
class ConvoyMapRecipeCertOld(
    private val files: GtFiles,
    private val paths: GtImportPaths,
    private val clock: GtClock,
    private val env: GtRecipeEnv,
) {
    private val EXT = ".gtmaps"
    private val PREFIX = "grouptrack_maps_"
    private val KEEP_DAYS = 60
    private val CONVERSION = "grouptrack_conversion.gtmaps"

    private fun dir(): Path = paths.dir("recipes")                                  // substitution 2

    /** 2.7e: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) -- device default zone. */
    /** CERTOLDUTC-2026-11-14: UTC, not the device default -- see substitution 12. */
    private fun sdf(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    private fun today(): String = sdf("yyyy-MM-dd").format(Date(clock.epochMs()))

    fun writeDaily(): Path? = write(fresh = false)
    fun writeFresh(): Path? = write(fresh = true)

    private fun write(fresh: Boolean): Path? {
        val target = dir() / (PREFIX + today() + EXT)
        if (fresh) {
            try { files.fs.delete(target) } catch (e: Exception) { }                // substitution 8
        } else if (files.fs.exists(dir() / CONVERSION)) {                           // substitution 8
            val busy = env.vectorBusyCount()                                        // substitution 5
            if (busy > 0) return null
        }
        if (files.fs.exists(target)) {                                              // substitution 8
            prune()
            return target
        }
        val slots = JSONArray()
        var grand = 0L
        for (slot in (env.slotSources().map { it.first } +                           // substitution 3
                listOf(env.vecSlot, env.demSlot)).distinct()) {                      // substitution 7
            if (!env.storeExists(slot)) continue                                     // substitution 4
            val r = env.deriveRecipe(slot) ?: continue                               // substitution 6
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
                put("source", env.slotSources()                                      // substitution 3
                    .find { it.first == r.slot }?.third ?: "")
                put("zMin", r.levels.min())
                put("zMax", r.levels.max())
                put("tiles", r.totalTiles)
                put("boxes", boxes)
            })
        }
        if (slots.length() == 0) return null
        val doc = JSONObject().apply {
            put("version", 1)
            put("created", sdf("yyyy-MM-dd'T'HH:mm:ss").format(Date(clock.epochMs())))
            put("slots", slots)
        }
        return try {
            files.fs.createDirectories(dir())                                        // substitution 8
            files.fs.write(target) { writeUtf8(doc.toString()) }                     // substitution 8
            prune()
            target
        } catch (e: Exception) {
            null
        }
    }

    private fun prune() {
        try {
            val cutoff = clock.epochMs() - KEEP_DAYS * 24L * 60L * 60L * 1000L
            for (f in files.list(dir())) {                                           // substitution 8
                val name = f.name
                if (name.startsWith(PREFIX) && name.endsWith(EXT) &&
                    files.lastModified(f) < cutoff) {                                // substitution 8
                    try { files.fs.delete(f) } catch (e: Exception) { }              // substitution 8
                }
            }
        } catch (e: Exception) {
        }
    }

}
