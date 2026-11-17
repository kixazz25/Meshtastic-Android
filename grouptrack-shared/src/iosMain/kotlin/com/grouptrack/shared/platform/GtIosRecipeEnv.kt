package com.grouptrack.shared.platform

import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.maprecipe.GtRecipeEnv
import com.grouptrack.shared.maprecipe.GtRecipeResult
import com.grouptrack.shared.mapsource.GtMapSpec
import com.grouptrack.shared.queue.GtQueueStatus

/**
 * IOSRECIPE-2026-11-17: GtRecipeEnv on the iPad. FOUR of six members answered, TWO refused by name.
 *
 * ⭐ NOTHING HERE IS NEWLY WRITTEN THAT THE TREE ALREADY HAD, and that took a reference check to see
 * (Fred 11-17: "remember to check function ref for existing methods"). [storeExists] was about to be
 * written a second time: IosTileEnv -- a GtTileStoreEnv, so grepping the interface name GtMBTilesEnv
 * found nothing -- already answers it, already certified. METHOD RULE 1 again: verify by DECLARED
 * NAME, never by the name you expected.
 *
 * ⚠ WHY TWO ARE REFUSED RATHER THAN FAKED, and why these two specifically:
 *
 *  slotSources -- Android reads MapSourceManager's registry (defaultSlots joined to sources from
 *  map_sources.json). PORTABLE: IosMapSpecEnv.readAsset already reads bundle assets, so the asset
 *  mechanism exists -- but the registry itself is ~400 lines of Android object and porting it is its
 *  own change with its own certification, not a line in this file.
 *  ⛔ AND THE INTERFACE COMMENT IS WRONG. It says "(slot, short label, source id)"; Android returns
 *  Triple(slot.legacyKey, source.shortLabel, source.baseUrl) and GtMapRecipe writes that third value
 *  as "source" in the recipe JSON. Anyone porting this must copy the BEHAVIOUR (a baseUrl), not the
 *  comment, or the recipes will differ by one field and still look right.
 *
 *  deriveRecipe -- the quadtree, ConvoyDownloadQueue.kt:885. ConvoyMapRecipe's own header says why it
 *  was never ported: "deriveRecipe runs the quadtree -- hundreds of COUNT queries per slot against
 *  MBTilesStore -- so porting it HERE would have made the recipe's certification depend on the tile
 *  store's. It is certified with pass 3 instead." That reasoning did not expire when the iPad arrived.
 *  Porting it is a DECISION about coupling two certifications, not a mechanical port, so it waits for
 *  one rather than being smuggled in here.
 */
class IosRecipeEnv(
    private val paths: GtImportPaths = IosImportPaths(),
    private val tiles: IosTileEnv = IosTileEnv(paths),
    /**
     * IOSRECIPE-2026-11-17: the queue runtime, injected and nullable, for exactly the reason given on
     * IosQueuePlatform (CODE RULE 1): a runtime exists and is certified, nothing in the app constructs
     * one yet because that needs a real URLSession fetch. See [vectorBusyCount] for why a missing
     * runtime must throw here rather than answer.
     */
    private val queue: IosDownloadQueue? = null,
) : GtRecipeEnv {

    private val log = gtLogger("GtMapRecipe")   // 2.7e's tag

    /** 2.7e MBTilesStore.storeExists(slot) -- already answered by IosTileEnv, and already certified. */
    override fun storeExists(slot: String): Boolean = tiles.storeExists(slot)

    override val vecSlot: String = GtMapSpec.VEC_SLOT
    override val demSlot: String = GtMapSpec.DEM_SLOT

    /**
     * 2.7e: init(context), THEN count QUEUED/DOWNLOADING/PAUSED jobs whose refreshSlot is VEC or DEM.
     * The load is part of the check, not setup -- so it happens here, not in a constructor, exactly as
     * Android calls DownloadQueueManager.init(context) inside the member. ⚠ BUSYREAD-2026-11-17: it
     * LOADS and does not SCHEDULE; see IosDownloadQueue.ensureLoaded for why that difference is a
     * defect on iOS and not on Android.
     *
     * ⛔ A MISSING RUNTIME THROWS AND MUST NOT ANSWER 0. GtMapRecipe does
     * `if (env.vectorBusyCount() > 0) return null` -- so 0 does not mean "unknown", it means GO AHEAD.
     * Answering 0 because there is no queue to ask would green-light a recipe build while a vector or
     * elevation refresh might be running: the recipe would be derived against a store being written
     * underneath it, report success, and be wrong in a way nothing logs. Silent degradation again, and
     * the inverted sense of the return value is what makes it easy to get wrong.
     */
    override fun vectorBusyCount(): Int {
        val q = queue ?: run {
            log.e { "IOSRECIPE-2026-11-17: vectorBusyCount refused -- no download-queue runtime wired" }
            throw IllegalStateException(
                "IOSRECIPE-2026-11-17: GtRecipeEnv.vectorBusyCount was called with no download-queue " +
                "runtime wired, and it cannot answer 0: GtMapRecipe reads 0 as 'no refresh is running, " +
                "go ahead', so a guess would build a recipe against a store that may be being written. " +
                "IosDownloadQueue exists and is certified (IOSPLATQ-2026-11-17); nothing constructs one " +
                "yet because that needs a real URLSession fetch. Same blocker as GtQueuePlatform.append."
            )
        }
        // BUSYREAD-2026-11-17: ensureLoaded, NOT initAndResume. 2.7e's init(context) also schedules,
        // but it schedules ASYNC workers so its count is unaffected; a synchronous fetch here would run
        // the queue dry inside this read and answer 0 instead of 3. A reader must not schedule.
        q.ensureLoaded()
        return q.queue().count {
            (it.status == GtQueueStatus.QUEUED || it.status == GtQueueStatus.DOWNLOADING ||
                it.status == GtQueueStatus.PAUSED) &&
                (it.refreshSlot == vecSlot || it.refreshSlot == demSlot)
        }
    }

    /** ⛔ NOT IMPLEMENTED -- the map-source registry is not ported. See the class note. */
    override fun slotSources(): List<Triple<String, String, String>> {
        log.e { "IOSRECIPE-2026-11-17: slotSources refused -- MapSourceManager's registry is not ported" }
        throw IllegalStateException(
            "IOSRECIPE-2026-11-17: GtRecipeEnv.slotSources was called, but the map-source registry " +
            "(MapSourceManager.defaultSlots joined to sources from map_sources.json) is not ported to " +
            "shared. It is portable -- IosMapSpecEnv.readAsset already reads bundle assets -- but it is " +
            "its own change with its own certification. ⛔ When porting, copy the BEHAVIOUR: Android " +
            "returns Triple(legacyKey, shortLabel, baseUrl) and GtMapRecipe writes the third as " +
            "\"source\"; the interface comment saying \"source id\" is wrong. Returning an empty list " +
            "here would make GtMapRecipe emit a recipe with no slots and call it a success."
        )
    }

    /** ⛔ NOT IMPLEMENTED -- the quadtree is not ported, on purpose. See the class note. */
    override fun deriveRecipe(slot: String): GtRecipeResult? {
        log.e { "IOSRECIPE-2026-11-17: deriveRecipe($slot) refused -- the quadtree is not ported" }
        throw IllegalStateException(
            "IOSRECIPE-2026-11-17: GtRecipeEnv.deriveRecipe was called for slot " + slot + ", but the " +
            "quadtree (ConvoyDownloadQueue.deriveRecipe, app/.../ConvoyDownloadQueue.kt:885) is not " +
            "ported. ConvoyMapRecipe's header states the reason: it runs hundreds of COUNT queries per " +
            "slot against the tile store, so porting it would couple the recipe's certification to the " +
            "tile store's -- it is certified by pass 3 instead. Porting it is a decision about that " +
            "coupling, not a mechanical port. ⛔ It is a throw and not a null: GtMapRecipe treats null " +
            "as 'this slot has no recipe' and CONTINUES, so a null here would silently emit a recipe " +
            "missing every slot."
        )
    }
}
