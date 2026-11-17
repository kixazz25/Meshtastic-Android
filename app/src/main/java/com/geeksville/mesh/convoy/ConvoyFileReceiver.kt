// SEAM2PATHS-2026-11-16: Context.cacheDir/filesDir -> the shared storage socket
package com.geeksville.mesh.convoy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.json.JSONObject
import java.io.File
import com.grouptrack.shared.log.gtLogger
import com.geeksville.mesh.grouptrack.GtPathsAndroid

/**
 * ConvoyFileReceiver
 *
 * GPXOPEN-2026-10-09: ALSO the door for emailed GPX/KML files (Gmail sends application/octet-stream, which only this
 * activity accepts) -- they go to the one GPX import panel via [GpxOpen]. Nothing is ever dropped silently.
 *
 * Standalone Activity registered exclusively for mime type application/x-convoy-ride.
 * Completely isolated from MainActivity and all Meshtastic code.
 *
 * Receives .convoy file from email or any other source.
 * Reads convoyDocType field and routes to the correct import directory.
 * Finishes immediately — no UI.
 *
 * Import directories (all under filesDir):
 *   convoy_import/       — convoy_ride documents
 *   convoy_map_import/   — convoy_map_region documents (V3)
 *   convoy_route_import/ — convoy_route documents (V3)
 *   convoy_master_import/— convoy_master documents (future)
 *
 * Each directory is scanned by the appropriate handler on next app open.
 */
class ConvoyFileReceiver : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val uri = intent?.data
            if (uri == null) {
                gtLogger(TAG).w { "No URI in intent — ignoring" }
                finish()
                return
            }

            gtLogger(TAG).i { "Received convoy file URI: $uri" }

            // HEADREAD-2026-11-17: route on the HEAD, then read the rest only if something recognised
            // it. Before this the whole file was decoded into a String first -- 293 MB of .db in the
            // real Download folder, to decide it was not ours. See GtFileOpen.HEAD_CHARS.
            var family: GtFileOpen.Family? = null
            val content: String? = contentResolver.openInputStream(uri)?.use { input ->
                val reader = input.bufferedReader()
                val buf = CharArray(GtFileOpen.HEAD_CHARS)
                var n = 0
                // ⚠ a Reader may return a SHORT read: loop until the buffer is full or the stream ends,
                // or a file delivered in small chunks would be judged on a few hundred bytes.
                while (n < GtFileOpen.HEAD_CHARS) {
                    val got = reader.read(buf, n, GtFileOpen.HEAD_CHARS - n)
                    if (got < 0) break
                    n += got
                }
                val head = if (n <= 0) "" else String(buf, 0, n)
                family = GtFileOpen.familyOf(head)
                when {
                    head.isBlank() -> null
                    family == null -> head          // nothing recognised it: the head is all we need
                    n < GtFileOpen.HEAD_CHARS -> head              // the whole file fitted
                    else -> head + reader.readText()               // recognised and larger: read the rest
                }
            }

            if (content.isNullOrBlank()) {
                gtLogger(TAG).e { "Empty or unreadable file from URI: $uri" }
                finish()
                return
            }
            if (family == null) {
                // HEADREAD-2026-11-17: the same message the dispatch gives, without reading the file.
                gtLogger(TAG).w { "Not a convoy file — ignoring (head only, ${content.length} chars read)" }
                android.widget.Toast.makeText(this,
                    "This file isn't a GroupTrack OG ride, map recipe, route recipe or a GPX / KML track.",
                    android.widget.Toast.LENGTH_LONG).show()
                return
            }

            // ONEOPEN2-2026-11-17: THE one dispatch, now in GtFileOpen so that a file tapped from
            // outside, a file tapped in our own screen and a DIRECTORY tapped in our own screen all run
            // the same routing (Fred 11-17). The branch bodies moved there verbatim; this activity keeps
            // only what is activity-shaped -- the URI, the Toast and finish().
            // NULLNAME-2026-11-17: asked ONCE. displayName queries the content resolver, and this was
            // calling it twice -- once to route and once to archive.
            val arrivalName = GpxOpen.displayName(this, uri)
            val outcome = GtFileOpen.receive(this, arrivalName, content, bringForward = true)

            // ARCHIVE-2026-11-17 (Fred rule 2): "anything processed from downloads should be archived
            // to the owning sub directory." outcome.family came from the dispatch that recognised the
            // content, so this is the one place that knows where it goes.
            // ⚠ ARCHIVED ON RECOGNITION, NOT ON SUCCESS. If the import itself failed, a copy of what
            // ARRIVED is exactly what a recovery wants -- and the rider already has the failure message.
            // ⚠ The arrival name is kept as given, even when Gmail supplies something unhelpful: it is
            // what the rider saw in the email, and renaming it would make the archive harder to
            // recognise, not easier. When the sender gave NO name at all, a timestamped one is
            // synthesised -- the same fallback GtRecipes.receive already uses for that case, so an
            // unnamed arrival is still archived rather than dropped.
            outcome.family?.let { fam ->
                GtFileHome.save(this, fam, arrivalName ?: "received_${System.currentTimeMillis()}", content)
            }
            val message = outcome.message
            if (message.isNotEmpty()) {
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            }

        } catch (e: Exception) {
            gtLogger(TAG).e { "ConvoyFileReceiver failed: ${e.message}" }
            // GPXOPEN-2026-10-09: never silent.
            android.widget.Toast.makeText(this, "Could not open the file: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        } finally {
            if (!isFinishing) finish()   // GPXOPEN-2026-10-09: once ("Duplicate finish request" in the log)
        }
    }

    companion object {
        private const val TAG = "ConvoyFileReceiver"
    }
}


/**
 * RIDEIMPORT3-2026-09-25 (Fred): THE ride import -- one path for an emailed ride (ConvoyFileReceiver) and for
 * Work with Rides -> Import a ride (a file picked from Downloads). Moved here UNCHANGED from the receiver's
 * RIDEIMPORT2 block: store the ride file as rides/<rideId>.json (tmp + rename), stage its GPX in gpx_staging,
 * load the real map sources, and offer it to the import panel. [bringForward] = true only from outside the app
 * (the email path), which must bring GroupTrack to the front; from the menu the app is already open.
 * Returns the message to show the rider.
 * STEP14-2026-11-08 (2.8): the parse / check / store / stage now live in shared code (com.grouptrack.shared.ride.GtRideImport, a
 * line-for-line port). What stays here is the platform's part, called by the shared code at 2.7e's points, in 2.7e's order.
 */
object RideImport {
    fun importRide(context: android.content.Context, content: String, bringForward: Boolean): String =
        com.grouptrack.shared.ride.GtRideImport.importRide(GtRidesAndroid.Files(context), content, bringForward,
            object : com.grouptrack.shared.ride.RideImportPlatform {
                override fun initMapSources() {
                    MapSourceManager.init(context.applicationContext)   // MAPINIT-2026-09-24: real sources, even from cold
                }
                override fun offer(gpxPath: String) {
                    RideImportLauncher.offer(java.io.File(gpxPath))
                }
                override fun bringForward() {
                    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        context.startActivity(launch)
                    }
                }
            })
}


/**
 * GPXOPEN-2026-10-09 (Fred: "the import is the same process for any GPX type -- route, track or waypoint").
 * A GPX/KML opened from OUTSIDE the app (email, Files, another app) goes to THE one GPX import: it is staged where the
 * import panel stages picked files and the panel opens with it ticked -- the same path a ride's GPX takes
 * ([RideImport]). The rider chooses maps and taps IMPORT; the panel's recap says what came in (tracks, routes,
 * waypoints). Used by ConvoyFileReceiver (Gmail: octet-stream) and MainActivity (apps sending the GPX/KML types).
 */
object GpxOpen {
    private const val TAG = "GpxOpen"

    /** XML whose root is GPX or KML (checked on the first few KB -- the root element is near the top). */
    fun looksLikeGpxOrKml(text: String): Boolean {
        val head = text.take(4000).lowercase()
        return head.contains("<gpx") || head.contains("<kml")
    }

    /** The file name the sending app gives (e.g. "onx-markups-2026-07-23.gpx"). CODE RULE 1: null = the sender gave none. */
    fun displayName(context: android.content.Context, uri: android.net.Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        }
    } catch (e: Exception) { null }

    /** Stage the file and open the import panel with it. Returns the message to show the rider. */
    fun stageAndOffer(context: android.content.Context, displayName: String?, content: String, bringForward: Boolean): String {
        val ext = if (content.take(4000).lowercase().contains("<kml")) ".kml" else ".gpx"
        val base = (displayName ?: "import_${System.currentTimeMillis()}").substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifBlank { "import" }
        return try {
            // The panel's own policy: staging holds only the current selection.
            val stage = java.io.File(GtPathsAndroid.files(context), "gpx_staging")
            if (stage.exists()) stage.listFiles()?.forEach { it.delete() }
            stage.mkdirs()
            val f = java.io.File(stage, base + ext)
            f.writeText(content)
            MapSourceManager.init(context.applicationContext)   // real map sources, even from cold (as RideImport)
            RideImportLauncher.offer(f)
            if (bringForward) {
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                    launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    context.startActivity(launch)
                }
            }
            gtLogger(TAG).i { "GPXOPEN-2026-10-09: staged ${f.name} (${f.length()} bytes) -> import panel" }
            "Opening ${f.name} \u2014 choose maps and tap IMPORT."
        } catch (e: Exception) {
            gtLogger(TAG).e { "GPXOPEN-2026-10-09: staging failed: ${e.message}" }
            "Could not open the file: ${e.message}"
        }
    }

    /** MainActivity's path: read the shared file off the main thread, then stage it. */
    suspend fun fromUri(context: android.content.Context, uri: android.net.Uri): String {
        val read = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val name = displayName(context, uri)
            val text = try { context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } }
                       catch (e: Exception) { null }
            name to text
        }
        val text = read.second
        if (text.isNullOrBlank()) return "Could not read ${read.first ?: "the file"}."
        if (!looksLikeGpxOrKml(text)) return "${read.first ?: "This file"} isn't a GPX or KML track."
        // ARCHIVE-2026-11-17: MainActivity's door, and it has to archive here too. The manifest splits
        // the two entry points by MIME -- ConvoyFileReceiver owns octet-stream/json/.convoy, MainActivity
        // owns gpx+xml/kml/xml -- so without this, whether a tapped GPX is archived would depend on
        // which MIME the file manager happened to supply. Same family, since this branch has already
        // established the content is GPX or KML.
        // ⚠ Tapping a file that is ALREADY in Map Features re-writes it with identical content and the
        // same name, which GtFileHome replaces in place. A harmless no-op, not a duplicate.
        GtFileHome.save(context, GtFileOpen.Family.MAP_FEATURES,
            read.first ?: "received_${System.currentTimeMillis()}.gpx", text)
        return stageAndOffer(context, read.first, text, bringForward = false)
    }
}
