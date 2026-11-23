package com.grouptrack.shared.platform

import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.route.GtRoute
import com.grouptrack.shared.text.GtTime
import okio.FileSystem

private val routeEnvLog = gtLogger("IosRouteEnv")

/**
 * IOSROUTEENV-2026-11-23: the iPad's half of [GtRoute.Env] -- the two things the shared route
 * builder takes from its platform. Same family as [IosRideFiles], [IosSettings] and [IosClock],
 * and installed the same way as [installIosRideEnv].
 *
 * WHY IT IS NEEDED: [GtRoute.env] is `lateinit`, and its own comment says why -- "a platform that
 * forgot to set it fails on the first draft with UninitializedPropertyAccessException instead of
 * silently writing nothing". Android sets it in `RouteExplorer`'s object initialiser
 * (convoy/RouteExplorer.kt:26). Nothing set it on the iPad, so the shared route builder aborted the
 * process at its first draft write.
 *
 * ANDROID IS THE SPECIFICATION:
 *   nowIso     java.time.Instant.now().toString()
 *   writeDraft RouteDraftStore.writeRawDraft(name, JSONObject(json)) (:743-751) --
 *              draftDir(); fileFor(name).writeText(doc.toString()); true. On a throw: log, false,
 *              and 2.7e does not look at the result either.
 *   fileFor    (:625-630) trim; replace the characters a file name cannot hold with '_';
 *              ifBlank "Not Named"; + ".json". In route_drafts/ (DIR_NAME, :49).
 *
 * \u26a0 ONE STATED DIFFERENCE: Android's nowIso carries fractional seconds, this carries whole
 * seconds -- the identical choice [installIosRideEnv] already made for rides. Drafts hold it in
 * createdAt / updatedAt only, and nothing compares the two platforms' strings.
 */
fun installIosRouteEnv() {
    GtRoute.env = GtRoute.Env(
        nowIso = { GtTime.formatUtcSeconds(IosClock.epochMs()) },
        jsonCreate = { path -> jsonCreate(path) },
        jsonRead = { path -> jsonRead(path) },
        jsonWrite = { path, text -> jsonWrite(path, text) },
        jsonDelete = { path -> jsonDelete(path) },
        jsonList = { jsonList() },
    )
    routeEnvLog.i { "IOSROUTEENV-2026-11-23: GtRoute.env installed" }
}

/**
 * ROUTEJSONPRIM-2026-11-23: a path under route_drafts/, resolved as RouteDraftStore.jsonFile resolves it -- folders on the
 * way made, the last segment sanitised as RouteDraftStore.fileFor sanitises a draft name (:625-630).
 */
private fun jsonPath(path: String): okio.Path {
    val parts = path.split('/').filter { it.isNotEmpty() }
    var dir = IosImportPaths().dir(ROUTE_DRAFTS_DIR)
    for (p in parts.dropLast(1)) dir = dir.resolve(p)
    val bad = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
    val safe = parts.lastOrNull().orEmpty().trim()
        .map { c -> if (c in bad) '_' else c }.joinToString("").ifBlank { "Not Named" }
    return dir.resolve(safe)
}

/** CREATE: the file exists and is empty if it was not there. */
private fun jsonCreate(path: String): Boolean = try {
    val f = jsonPath(path)
    f.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
    if (!FileSystem.SYSTEM.exists(f)) FileSystem.SYSTEM.write(f) { }
    FileSystem.SYSTEM.exists(f)
} catch (e: Exception) {
    routeEnvLog.w { "ROUTEJSONPRIM: create '" + path + "' failed: " + e.message }
    false
}

/** READ: the file's text, or null when there is no such file. */
private fun jsonRead(path: String): String? = try {
    val f = jsonPath(path)
    if (FileSystem.SYSTEM.exists(f)) FileSystem.SYSTEM.read(f) { readUtf8() } else null
} catch (e: Exception) {
    routeEnvLog.w { "ROUTEJSONPRIM: read '" + path + "' failed: " + e.message }
    null
}

/** WRITE: replace the file's text. RouteDraftStore.writeRawDraft (:743-751) is this plus the folder. */
private fun jsonWrite(path: String, text: String): Boolean = try {
    val f = jsonPath(path)
    f.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
    FileSystem.SYSTEM.write(f) { writeUtf8(text) }
    true
} catch (e: Exception) {
    routeEnvLog.e { "ROUTEJSONPRIM: write '" + path + "' failed: " + e.message }
    false
}

/** DELETE: remove the file. True when it is gone, which includes "was not there". */
private fun jsonDelete(path: String): Boolean = try {
    val f = jsonPath(path)
    if (FileSystem.SYSTEM.exists(f)) FileSystem.SYSTEM.delete(f)
    true
} catch (e: Exception) {
    routeEnvLog.w { "ROUTEJSONPRIM: delete '" + path + "' failed: " + e.message }
    false
}


/** LIST: the route json files directly under the root, by file name. RouteDraftStore.jsonList's filter. */
private fun jsonList(): List<String> = try {
    val dir = IosImportPaths().dir(ROUTE_DRAFTS_DIR)
    if (!FileSystem.SYSTEM.exists(dir)) emptyList()
    else FileSystem.SYSTEM.list(dir)
        .filter { FileSystem.SYSTEM.metadataOrNull(it)?.isRegularFile == true }
        .map { it.name }
        .filter { it.endsWith(".json") }
} catch (e: Exception) {
    routeEnvLog.w { "ROUTEJSONPRIM: list failed: " + e.message }
    emptyList()
}

/** RouteDraftStore.DIR_NAME (:49) -- the route json root; do not rename: live rider data.
 *  The batch lives at "batch/open_batch.json" and the last search at "batch/last_ai_search.json" UNDER this root
 *  (BATCH_DIR :95, BATCH_FILE :94, LAST_SEARCH_FILE :170). Those are PATHS THE PROCESS SPELLS, not names this file holds.
 *  NO MIGRATION HERE, deliberately: Android moves a batch written by an older build out of route_drafts/, once. No iPad
 *  build has ever written one, so there is nothing to move. */
private const val ROUTE_DRAFTS_DIR: String = "route_drafts"
