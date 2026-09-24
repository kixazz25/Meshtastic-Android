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

            // RIDEIMPORT2-2026-09-24 (Fred): a GroupTrack ride file (format 3). First, STORE it unchanged as
            // rides/<rideId>.json -- Apply Ride and Send read rides from there. Then STAGE its GPX where the import
            // panel stages picked files and open that panel with it ticked (no picker): route, trailhead, recipe
            // and narrative come in, the maps download, and the panel deletes the staged file as always.
            // Checked FIRST, before the old-format test below.
            if (content.contains("\"grouptrack.ride\"")) {
                val rideJson = try { JSONObject(content) } catch (e: Exception) { null }
                val rideObj = rideJson?.optJSONObject("ride")
                val rideId = rideObj?.optString("rideId", "")?.takeIf { it.isNotBlank() && it != "null" }
                val rideName = rideObj?.optString("name", "")?.takeIf { it.isNotBlank() && it != "null" } ?: "Ride"
                val message = if (rideJson == null || rideJson.optString("kind") != "grouptrack.ride" || rideId == null) {
                    Log.w(TAG, "RIDEIMPORT2-2026-09-24: not a readable GroupTrack ride file")
                    "Not a readable GroupTrack ride file."
                } else try {
                    val dir = GroupTrackStorage.dir("rides", this)
                    val out = java.io.File(dir, rideId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")
                    val tmp = java.io.File(dir, out.name + ".tmp")
                    tmp.writeText(content)
                    if (out.exists()) out.delete()
                    if (!tmp.renameTo(out)) throw IllegalStateException("rename failed")
                    Log.i(TAG, "RIDEIMPORT2-2026-09-24: stored ${out.name} (${out.length()} bytes)")
                    val gpx = rideJson.optJSONObject("rideData")?.optString("gpx", "")
                        ?.takeIf { it.isNotBlank() && it != "null" }
                    if (gpx == null) {
                        "Ride \"$rideName\" imported (it has no route yet)."
                    } else {
                        // The panel's own policy: staging holds only the current selection.
                        val stage = java.io.File(filesDir, "gpx_staging")
                        if (stage.exists()) stage.listFiles()?.forEach { it.delete() }
                        stage.mkdirs()
                        val g = java.io.File(stage, rideName.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifBlank { "ride" } + ".gpx")
                        g.writeText(gpx)
                        RideImportLauncher.offer(g)
                        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            startActivity(launch)
                        }
                        "Ride \"$rideName\" imported \u2014 choose maps and tap IMPORT to add its route."
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "RIDEIMPORT2-2026-09-24: store failed: ${e.message}")
                    "Could not store the ride: ${e.message}"
                }
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                finish()
                return
            }

            // Validate this is actually a convoy file before processing
            if (!content.contains("convoyDocType")) {
                Log.w(TAG, "Not a convoy file — ignoring")
                finish()
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
        } finally {
            finish()
        }
    }

    companion object {
        private const val TAG = "ConvoyFileReceiver"
    }
}
