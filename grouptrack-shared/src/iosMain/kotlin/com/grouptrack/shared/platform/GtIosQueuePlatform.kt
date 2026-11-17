package com.grouptrack.shared.platform

import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.queue.GtQueueEntry
import com.grouptrack.shared.queue.GtQueuePlatform
import com.grouptrack.shared.log.gtLogger
import okio.Path

/**
 * IOSQUEUE-2026-11-17: the iPad's GtQueuePlatform. THREE members answer; [append] THROWS, on purpose.
 *
 * WHAT THIS SEAM IS. GtVectorQueue (commonMain) holds the save-by-area ARITHMETIC -- boxesOf,
 * recipeBoxes, squaresInBox, the zoom maths -- and hands the finished entries to the platform through
 * this interface. 2.7e's platform side (DownloadQueueManager, ConvoyDownloadQueue.kt:298) does three
 * things in one locked read-change-write (QUEUELOCK-2026-11-12): adds the entries to _queue, calls
 * saveQueue() to persist download_queue.json, then startNextIfAvailable() to run workers.
 *
 * ⛔ WHY [append] THROWS INSTEAD OF QUEUEING. The queue's ARITHMETIC is shared; its RUNTIME is not.
 * Nothing in grouptrack-shared holds queue state, writes download_queue.json, or starts a worker --
 * measured 11-17: those exist only in app/. The tempting half-measure is to keep the entries and
 * persist the json without running anything. That is WORSE than throwing, because it SUCCEEDS: the
 * rider asks to save an area, the call returns cleanly, the json grows, and not one tile is ever
 * fetched. Silent degradation is the failure mode this project keeps paying for -- GtHelpLinks and
 * GtMapSpec both catch-and-fall-back, and the vecmap server answers a miss with an empty HTTP 200, so
 * a mis-bundled build draws nothing and logs no cause. A named throw is the opposite: it says exactly
 * what is missing, at the moment something needs it.
 *
 * This is the same treatment, for the same reason, as IosImportPlatform.enqueueCorridor
 * (CERTPATHS-2026-11-16). Both wait on ONE piece of real work -- the iOS download-queue runtime --
 * which also gates GtRecipeEnv.deriveRecipe and GtRecipeEnv.vectorBusyCount. Four members, one build.
 *
 * ⚠ THE DIVERGENCE IS DELIBERATE AND MUST BE CARRIED INTO CERTIFICATION: on Android append() queues;
 * here it throws. That is a known, dated behaviour difference, not a defect to be rediscovered.
 */
class IosQueuePlatform(
    private val paths: GtImportPaths = IosImportPaths(),
    /**
     * IOSQWIRE-2026-11-17: the runtime, INJECTED and nullable.
     * ⭐ WHY NULLABLE, against CODE RULE 1 (no nullable as a shortcut). This is not a shortcut, it is
     * the distinction the rider feels. [IosDownloadQueue] now exists and is certified in the harness,
     * but the APP does not yet construct one, because constructing one means supplying a real `fetch`
     * -- URLSession tile downloading, which is not written. Two different states:
     *   queue != null -> a runtime is wired; append queues, persists and schedules, as Android does.
     *   queue == null -> no runtime is wired; append THROWS, named, exactly as before.
     * Collapsing them would mean either a throw after the runtime is wired, or -- far worse -- an
     * append that succeeds into a queue nothing drains. The second is the silent degradation this
     * class's note was written to refuse. Evaluated at the point of contact (CODE RULE 2): the check
     * is inside append, where the entries actually need a runtime.
     * ⛔ When the real fetch lands, this parameter stops being nullable and this note goes with it.
     */
    private val queue: IosDownloadQueue? = null,
) : GtQueuePlatform {

    private val log = gtLogger("VectorQueue")   // 2.7e's tag, so the logs read as the Droid's

    /**
     * ⛔ NOT IMPLEMENTED -- the iOS download-queue runtime does not exist. See the class note.
     * 2.7e: _queue.value += entries; saveQueue(); startNextIfAvailable().
     */
    override fun append(entries: List<GtQueueEntry>): Unit {
        val n = entries.size
        val q = queue
        if (q != null) {
            // 2.7e, in order: entries added, file written, then the picker runs -- append() does all
            // three, and startNextIfAvailable is called AFTER the save, never before.
            q.append(entries)
            log.i { "IOSQWIRE-2026-11-17: append($n) queued; ${q.queue().size} entries in the queue" }
            return
        }
        log.e { "IOSQUEUE-2026-11-17: append($n) refused -- no iOS download queue runtime wired" }
        throw IllegalStateException(
            "IOSQUEUE-2026-11-17: GtQueuePlatform.append was called with " + n + " entries, but the iPad " +
            "has no download-queue runtime WIRED. IosDownloadQueue exists and is certified in the harness; nothing constructs one yet, because that needs a real URLSession fetch. " +
            "The save-by-area ARITHMETIC is shared and ran correctly to produce these entries; only the " +
            "RUNTIME is missing. It is deliberately a throw and not a silent no-op: queueing entries that " +
            "nothing will ever download would report success and fetch no tiles. Same blocker as " +
            "IosImportPlatform.enqueueCorridor; it also gates GtRecipeEnv.deriveRecipe and vectorBusyCount."
        )
    }

    /**
     * 2.7e: ConvoyConfig.DOWNLOAD_ZOOM -- read at each call, because 2.7e's default argument is.
     *
     * ⭐ MEASURED 11-17: `var DOWNLOAD_ZOOM = 18` (ConvoyConfig.kt:102) is NEVER ASSIGNED anywhere in the
     * tree -- its declaration is the only assignment. No prefs read, no settings writer, no UI setter,
     * and GtCert16c refuses to run unless it equals 18. So the seam Cert17g1a marks as "THE ONE SEAM"
     * is, today, a constant on both platforms.
     *
     * Kept as a `var` here rather than hardcoded, for the same reason 2.7e has one: when a rider-facing
     * setter does arrive it needs a home, and a `const` would have to be found and changed back. CODE
     * RULE 1 does not apply -- this is not a nullable shortcut, it is 2.7e's own mutability mirrored.
     */
    var downloadZoom: Int = 18

    override fun downloadZoom(): Int = downloadZoom

    /**
     * 2.7e: MapSourceManager.getDownloadSources().find { it.first == "SAT" }?.second?.size -- the SAT
     * layer count, feeding ONE SIZECALC log line on the VEC / DEM path.
     *
     * NULL, and the interface documents null as the answer: "or null when there is no SAT source."
     * Two independent reasons, either sufficient:
     *   1. MapSourceManager is Android-only (measured 11-17: app/ 1, shared/ 0), so there is no source
     *      list to read here;
     *   2. the raster path is retired (Fred 11-16: "no longer use rasters"), and 11-15 proved it on
     *      execution evidence -- slot=SAT appears ZERO times in 36,733 log lines of a real 1,670-tile
     *      download, which took the vector branch (VEC 1,536 + DEM 134).
     * ⚠ Note the 11-15 distinction, which this does NOT contradict: the SAT SOURCE ENTRY stays live on
     * Android (MapSourceManager loads it at launch and it feeds the credits screen). Only the SAT
     * DOWNLOAD branch is dead. This member reads the DOWNLOAD sources, so null is right.
     */
    override fun satLayers(): Int? = null

    /** 2.7e: GroupTrackStorage.dir("recipes") -- a named child of the root, NOT created. */
    override fun recipesDir(): Path = paths.dir("recipes")
}
