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
