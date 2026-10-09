package com.geeksville.mesh.convoy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import org.json.JSONObject
import java.io.File

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
                Log.w(TAG, "No URI in intent — ignoring")
                finish()
                return
            }

            Log.i(TAG, "Received convoy file URI: $uri")

            // Read file content
            val content = contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            }

            if (content.isNullOrBlank()) {
                Log.e(TAG, "Empty or unreadable file from URI: $uri")
                finish()
                return
            }

            // GPXOPEN-2026-10-09 (Fred): Gmail hands EVERY attachment over as application/octet-stream and only this
            // receiver accepts that type (ONEOPEN-2026-09-26 took it off MainActivity), so emailed GPX/KML files land
            // HERE -- and were dropped silently ("Not a convoy file -- ignoring"; proven 10-08 with two onX files).
            // An XML file that is GPX or KML goes to THE one GPX import: staged and opened in the import panel
            // (tracks, routes and waypoints alike), exactly like a ride's GPX. Ride files are JSON, so XML is checked
            // first and can never be mistaken for a ride.
            if (content.trimStart().startsWith("<") && GpxOpen.looksLikeGpxOrKml(content)) {
                val message = GpxOpen.stageAndOffer(this, GpxOpen.displayName(this, uri), content, bringForward = true)
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                return
            }

            // RIDEIMPORT2-2026-09-24 (Fred): a GroupTrack ride file (format 3). First, STORE it unchanged as
            // rides/<rideId>.json -- Apply Ride and Send read rides from there. Then STAGE its GPX where the import
            // panel stages picked files and open that panel with it ticked (no picker): route, trailhead, recipe
            // and narrative come in, the maps download, and the panel deletes the staged file as always.
            // Checked FIRST, before the old-format test below.
            if (content.contains("\"grouptrack.ride\"")) {
                // RIDEIMPORT3-2026-09-25: the shared ride import (also used by Work with Rides -> Import a ride).
                val message = RideImport.importRide(this, content, bringForward = true)
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                finish()
                return
            }

            // Validate this is actually a convoy file before processing
            if (!content.contains("convoyDocType")) {
                Log.w(TAG, "Not a convoy file — ignoring")
                // GPXOPEN-2026-10-09: never silent -- the rider is told what happened.
                android.widget.Toast.makeText(this, "This file isn't a GroupTrack ride or a GPX / KML track.",
                    android.widget.Toast.LENGTH_LONG).show()
                return
            }

            // Parse JSON and read convoyDocType
            val json = try {
                JSONObject(content)
            } catch (e: Exception) {
                Log.e(TAG, "Not valid JSON — ignoring file: ${e.message}")
                finish()
                return
            }

            val docType = json.optString("convoyDocType", "unknown")
            Log.i(TAG, "convoy file received — docType=$docType")

            // Route to correct import directory based on docType
            val importDirName = when (docType) {
                "convoy_ride"        -> "convoy_import"
                "convoy_map_region"  -> "convoy_map_import"
                "convoy_route"       -> "convoy_route_import"
                "convoy_master"      -> "convoy_master_import"
                else -> {
                    Log.w(TAG, "Unknown convoyDocType: $docType — discarding")
                    finish()
                    return
                }
            }

            // Write to import directory
            val importDir = File(filesDir, importDirName).also { it.mkdirs() }
            val fileName = "convoy_${System.currentTimeMillis()}.convoy"
            val destFile = File(importDir, fileName)
            destFile.writeText(content)

            Log.i(TAG, "Saved $docType to $importDirName/$fileName")

            // File saved — user will see splash next time they open the convoy menu
            Log.i(TAG, "Convoy file saved successfully — $docType ready for import")

        } catch (e: Exception) {
            Log.e(TAG, "ConvoyFileReceiver failed: ${e.message}")
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
 */
object RideImport {
    private const val TAG = "RideImport"

    fun importRide(context: android.content.Context, content: String, bringForward: Boolean): String {
        val rideJson = try { JSONObject(content) } catch (e: Exception) { null }
        val rideObj = rideJson?.optJSONObject("ride")
        val rideId = rideObj?.optString("rideId", "")?.takeIf { it.isNotBlank() && it != "null" }
        val rideName = rideObj?.optString("name", "")?.takeIf { it.isNotBlank() && it != "null" } ?: "Ride"
        return if (rideJson == null || rideJson.optString("kind") != "grouptrack.ride" || rideId == null) {
            Log.w(TAG, "RIDEIMPORT3-2026-09-25: not a readable GroupTrack ride file")
            "That isn't a GroupTrack ride file."
        } else try {
            val dir = GroupTrackStorage.dir("rides", context)
            dir.mkdirs()   // RIDEMKDIR-2026-09-24: a tablet that never saved a ride has no rides folder yet
            val out = java.io.File(dir, rideId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")
            val tmp = java.io.File(dir, out.name + ".tmp")
            tmp.writeText(content)
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) throw IllegalStateException("rename failed")
            Log.i(TAG, "RIDEIMPORT3-2026-09-25: stored ${out.name} (${out.length()} bytes)")
            val gpx = rideJson.optJSONObject("rideData")?.optString("gpx", "")
                ?.takeIf { it.isNotBlank() && it != "null" }
            if (gpx == null) {
                "Ride \"$rideName\" imported (it has no route yet)."
            } else {
                // The panel's own policy: staging holds only the current selection.
                val stage = java.io.File(context.filesDir, "gpx_staging")
                if (stage.exists()) stage.listFiles()?.forEach { it.delete() }
                stage.mkdirs()
                val g = java.io.File(stage, rideName.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifBlank { "ride" } + ".gpx")
                g.writeText(gpx)
                MapSourceManager.init(context.applicationContext)   // MAPINIT-2026-09-24: real sources, even from cold
                RideImportLauncher.offer(g)
                if (bringForward) {
                    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        context.startActivity(launch)
                    }
                }
                "Ride \"$rideName\" imported \u2014 choose maps and tap IMPORT to add its route."
            }
        } catch (e: Exception) {
            Log.e(TAG, "RIDEIMPORT3-2026-09-25: store failed: ${e.message}")
            "Could not store the ride: ${e.message}"
        }
    }
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
            val stage = java.io.File(context.filesDir, "gpx_staging")
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
            Log.i(TAG, "GPXOPEN-2026-10-09: staged ${f.name} (${f.length()} bytes) -> import panel")
            "Opening ${f.name} \u2014 choose maps and tap IMPORT."
        } catch (e: Exception) {
            Log.e(TAG, "GPXOPEN-2026-10-09: staging failed: ${e.message}")
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
        return stageAndOffer(context, read.first, text, bringForward = false)
    }
}
