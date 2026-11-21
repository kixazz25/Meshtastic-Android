/*
 * ARTIFACTSVC-2026-11-19 (2.8b) -- SHARE and EXPORT as a service, not a lambda in a panel.
 *
 * Fred 15:42: "share and export should be created as a service we can reuse." That is his 13:00
 * rule applied here -- "agreed 100% area selected becomes a service. here we are defining that
 * service" -- so the work lives in ONE place that both platforms call, rather than Android's
 * ConvoyArtifactOps.share/export and a second iOS copy that drifts from it.
 *
 * WHAT IS ALREADY SHARED, and why this is thinner than it looks:
 *   GtGpx.waypoint / .route / .trail   the GPX TEXT itself, 2.7e byte for byte (GTBATCH2A-2026-11-06)
 *   GtRead                             every row these builders read
 * Android's four builders (SpatialDbManager.kt:1675-1822) are a query plus a GtGpx call. The only
 * genuinely platform-bound parts are WHERE a track's .gpx file lives and HOW the finished file is
 * delivered -- and those are the two seams below, nothing else.
 *
 * THE SHAPE, following Fred's 11-17 decision (ConvoyArtifactOps.kt:289): "artifacts show share and
 * export. export should go to owning directories share should become email", and "the iPad has no
 * Android share sheet, so email is the one mechanism both platforms have."
 *     export  = build the GPX, write it into GroupTrack Map Features. Nothing else.
 *     share   = export, then hand that saved file to the platform's mail path.
 * Share is therefore export plus one step, which is why it cannot drift from it.
 */
package com.grouptrack.shared.artifact

import com.grouptrack.shared.gpx.GtGpx
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.spatial.GtRead

/** Where a finished file goes, and how it leaves. The ONLY platform-bound part of share/export. */
interface GtArtifactSink {
    /**
     * Write [text] into the GroupTrack Map Features family as [fileName].
     * Returns a platform handle for the written file (a uri on Android, a path on iOS), or null.
     * EXPORTFAM-2026-11-17: Map Features is the owning directory for all four export families.
     */
    fun saveToMapFeatures(fileName: String, text: String): String?

    /**
     * Hand an already-saved file to the platform's mail path. Android: ACTION_SEND + createChooser
     * with EXTRA_EMAIL and an asterisk-slash-asterisk type (EMAILHOUSE-2026-11-17 -- NOT octet-stream, which reads as a
     * generic share sheet). iOS: the mail composer.
     * Returns false when nothing took it, which the caller reports -- it is not an error.
     */
    fun email(handle: String, fileName: String, subject: String, body: String): Boolean

    /**
     * EMAILALL-2026-11-20: the same hand-off with SEVERAL already-saved files.
     *
     * A generic service: it takes a list of handles and delivers them together. It knows nothing
     * about what the files are or which function produced them.
     *
     * [handles] are platform handles for files already written -- a uri on Android, a path on iOS.
     *
     * ⚠ ORDER IS THE CALLER'S AND IS PRESERVED. Both platforms present the list as given, so a sink
     * must not sort, group or de-duplicate it: a caller that puts one file first means it.
     *
     * An EMPTY list is a caller error, not a delivery failure -- it returns false having presented
     * nothing, and says so in the log.
     *
     * Returns false when nothing took it, exactly as [email] does. The caller reports that; it is
     * not an error.
     */
    fun emailAll(handles: List<String>, subject: String, body: String): Boolean
}

/** A track's .gpx on disk, by geom_hash. Android: ConvoyTrackOps.tracksDir(); iOS: the storage root. */
fun interface GtTrackFileReader {
    fun readTrackGpx(geomHash: String): String?
}

sealed class GtArtifactResult {
    data class Ok(val fileName: String, val handle: String) : GtArtifactResult()
    data class Mailed(val fileName: String, val handle: String) : GtArtifactResult()
    data class NoMailApp(val fileName: String, val handle: String) : GtArtifactResult()
    data class Failed(val why: String) : GtArtifactResult()
}

class GtArtifactFiles(
    private val read: GtRead,
    private val tracks: GtTrackFileReader,
    private val sink: GtArtifactSink,
) {
    private val log = gtLogger("GtArtifactFiles")

    /**
     * ConvoyTrackOps.safeGpxName: a file name a file system will take, ending .gpx.
     * ⚠ Transcribed deliberately rather than imported -- the app's copy is Android-side. When the
     * app switches to this service its copy retires; until then they must agree, so any change goes
     * in both. Kept conservative: letters, digits, space, dash, underscore and dot survive.
     */
    fun safeGpxName(displayName: String): String {
        val cleaned = displayName.trim().map { ch ->
            if (ch.isLetterOrDigit() || ch == ' ' || ch == '-' || ch == '_' || ch == '.') ch else '_'
        }.joinToString("")
        val base = cleaned.ifBlank { "artifact" }
        return if (base.endsWith(".gpx", ignoreCase = true)) base else "$base.gpx"
    }

    /**
     * SpatialDbManager.buildWaypointGpxById / buildRouteGpxById / buildTrailGpxById /
     * buildTrackGpxById (:1675, :1698, :1804, :1736), one function, same four branches.
     * Returns (displayName, gpxText) or null.
     */
    fun gpxFor(artifactType: String, artifactId: String): Pair<String, String>? = try {
        when (artifactType) {
            "Waypoints" -> read.waypointRow(artifactId)?.let { (name, type, geom) ->
                GtGpx.waypoint(name, type, geom, "")?.let { name to it }
            }
            "Routes" -> read.routeRow(artifactId)?.let { (name, geom) ->
                // CLOSEPLANNER-2026-08-29: <desc> gets the HEADLINE, not the JSON -- Gaia and onX put
                // human prose there. The structured block goes to <extensions>, which others ignore.
                val notes = read.routeNarrative(artifactId)
                val headline = try {
                    (notes?.get("narrative") as? kotlinx.serialization.json.JsonObject)
                        ?.get("headline")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: ""
                } catch (e: Exception) { "" }
                // ⚠ Android hands GtGpx the notes as org.json writes them (notes?.toString()).
                // kotlinx writes the same VALUES with its own spacing and key order, so the
                // <extensions> block can differ byte for byte from Android's while carrying
                // identical content. Named here rather than claimed equal.
                name to GtGpx.route(name, geom, headline, notes?.toString())
            }
            "Trails" -> read.trailRow(artifactId)?.let { (name, geom) -> name to GtGpx.trail(name, geom) }
            "Tracks" -> read.trackNameAndHash(artifactId)?.let { (name, hash) ->
                // A track's GPX is NOT rebuilt -- the file written at record time IS the export
                // (MYTRACKSDB-2026-09-12). Rebuilding it would re-round every coordinate.
                tracks.readTrackGpx(hash)?.let { name to it }
                    ?: run { log.e { "ARTIFACTSVC-2026-11-19: no track file for geom_hash $hash" }; null }
            }
            else -> null
        }
    } catch (e: Exception) {
        log.e { "ARTIFACTSVC-2026-11-19: gpxFor($artifactType, $artifactId) failed: ${e.message}" }
        null
    }

    /** EXPORT: build and write into Map Features. No delivery. */
    fun export(artifactType: String, artifactId: String): GtArtifactResult {
        val gpx = gpxFor(artifactType, artifactId)
            ?: return GtArtifactResult.Failed("nothing to export for $artifactType $artifactId")
        val fileName = safeGpxName(gpx.first)
        val handle = sink.saveToMapFeatures(fileName, gpx.second)
            ?: return GtArtifactResult.Failed("could not write $fileName to Map Features")
        log.i { "ARTIFACTSVC-2026-11-19: exported $fileName" }
        return GtArtifactResult.Ok(fileName, handle)
    }

    /** SHARE: export, then mail the file that was written. The attachment IS the archive. */
    fun share(artifactType: String, artifactId: String): GtArtifactResult {
        val done = export(artifactType, artifactId)
        if (done !is GtArtifactResult.Ok) return done
        val subject = "GroupTrack " + artifactType.lowercase() + ": " + done.fileName
        val body = "Attached: " + done.fileName + ", exported from GroupTrack OG.\n\n" +
            "Open the attachment on a tablet running GroupTrack to bring it in."
        return if (sink.email(done.handle, done.fileName, subject, body))
            GtArtifactResult.Mailed(done.fileName, done.handle)
        else GtArtifactResult.NoMailApp(done.fileName, done.handle)
    }
}
