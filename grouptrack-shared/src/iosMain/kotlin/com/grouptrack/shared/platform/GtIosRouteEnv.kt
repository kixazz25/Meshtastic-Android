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
        writeDraft = { name, json -> writeRouteDraft(name, json) },
    )
    routeEnvLog.i { "IOSROUTEENV-2026-11-23: GtRoute.env installed" }
}

/** RouteDraftStore.fileFor (:625-630), exactly -- the caller never spells a draft file name. */
private fun routeDraftFileName(name: String): String {
    val bad = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
    val safe = name.trim().map { c -> if (c in bad) '_' else c }.joinToString("").ifBlank { "Not Named" }
    return safe + ".json"
}

/** RouteDraftStore.writeRawDraft (:743-751), exactly: create the folder, write, true; on a throw log and false. */
private fun writeRouteDraft(name: String, json: String): Boolean = try {
    val dir = IosImportPaths().dir(ROUTE_DRAFTS_DIR)
    FileSystem.SYSTEM.createDirectories(dir)
    FileSystem.SYSTEM.write(dir.resolve(routeDraftFileName(name))) { writeUtf8(json) }
    routeEnvLog.i { "IOSROUTEENV-2026-11-23: wrote draft " + name }
    true
} catch (e: Exception) {
    routeEnvLog.e { "IOSROUTEENV-2026-11-23: writeRawDraft failed for " + name + ": " + e.message }
    false
}

/** RouteDraftStore.DIR_NAME (:49) -- do not rename: live rider data. */
private const val ROUTE_DRAFTS_DIR: String = "route_drafts"
