package com.grouptrack.shared.route

import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.storage.GtStorage
import okio.Path

/**
 * ROUTEJSON-2026-11-23 (Fred 10:25): the ONE service that says WHICH route json files exist.
 *
 * [stated] Fred: "route clear delete any route in a specific directory starting with json and
 * ten and json files existing underneath. it is all chewed in code now. the service to id the
 * json's exist and that should be the shared services."
 *
 * So this service IDENTIFIES. It does not read a draft, parse one, write one or delete one --
 * those stay in process, which is where they are today, and the recipe/batch existence LOGIC
 * (walking the batch's routes[] and healing a batch whose drafts are gone) stays with it.
 *
 * ANDROID IS THE SPECIFICATION (ConvoyRouteDraftStore / RouteDraftStore.kt):
 *   route_drafts/                      the drafts, one .json each
 *   route_drafts/batch/                BATCHDIR-2026-08-27: its own subdirectory, so the batch
 *                                      file can never appear in the WIP list as a bogus draft
 *   route_drafts/batch/open_batch.json the batch route json
 * [listDrafts] is `dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }`, so this returns
 * the same set: files only, directly in route_drafts/, ending .json. The `batch` subdirectory is
 * therefore excluded by being a directory, exactly as on Android -- not by a name check.
 *
 * ⚠ ANDROID IS NOT CHANGED BY THIS SERVICE. RouteDraftStore keeps its own correct copy of these
 * names and delegates here when consumers are counted at the end of the loop (Fred 11-21 07:29:
 * "build the second service, flag the services to be evaluated when we have completed this loop
 * ... not before"), the same decision GtRadioDoc records. FLAGGED, not forgotten.
 *
 * ⛔ NEITHER MEMBER THROWS. A directory that is not there is not an error -- Android's listFiles
 * returns null there and the store returns emptyList(), so this answers empty / false and says so
 * in the log.
 */
object GtRouteFiles {

    private val log = gtLogger("GtRouteFiles")

    /** RouteDraftStore.DIR_NAME -- do not rename: live rider data. */
    const val DIR: String = "route_drafts"

    /** RouteDraftStore.BATCH_DIR (BATCHDIR-2026-08-27) -- the json files underneath. */
    const val BATCH_DIR: String = "batch"

    /** RouteDraftStore.BATCH_FILE -- the batch route json. */
    const val BATCH_FILE: String = "open_batch.json"

    /** route_drafts/ -- NOT created here; a caller that writes creates it, as the store does. */
    fun dir(storage: GtStorage): Path = storage.dir(DIR)

    /** route_drafts/batch/ */
    fun batchDir(storage: GtStorage): Path = storage.dir(DIR).resolve(BATCH_DIR)

    /** route_drafts/batch/open_batch.json */
    fun batchFile(storage: GtStorage): Path = batchDir(storage).resolve(BATCH_FILE)

    /**
     * Every draft json directly in route_drafts/ -- FILE NAMES, newest-first ordering is the
     * caller's (the store sorts by the json's own updatedAt, which means reading it, which is
     * not this service's job).
     */
    fun routeJsons(storage: GtStorage, files: GtFiles): List<String> = try {
        files.list(dir(storage))
            .filter { p -> p.name.endsWith(".json") && files.fs.metadataOrNull(p)?.isRegularFile == true }
            .map { it.name }
    } catch (e: Exception) {
        log.w { MARKER + ": route_drafts not listed: " + e.message }
        emptyList()
    }

    /** Does one named draft json exist? RouteDraftStore.fileFor(name).exists(). */
    fun exists(storage: GtStorage, files: GtFiles, fileName: String): Boolean = try {
        files.fs.metadataOrNull(dir(storage).resolve(fileName))?.isRegularFile == true
    } catch (e: Exception) {
        log.w { MARKER + ": " + fileName + " not checked: " + e.message }
        false
    }

    /**
     * Is the batch route json there at all? This is the FILE question only -- whether the batch
     * still names a surviving draft is the caller's walk, as it is on Android.
     */
    fun hasBatchJson(storage: GtStorage, files: GtFiles): Boolean = try {
        files.fs.metadataOrNull(batchFile(storage))?.isRegularFile == true
    } catch (e: Exception) {
        log.w { MARKER + ": " + BATCH_FILE + " not checked: " + e.message }
        false
    }

    private const val MARKER = "ROUTEJSON-2026-11-23"
}
