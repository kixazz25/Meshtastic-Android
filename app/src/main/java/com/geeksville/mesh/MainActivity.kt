/*
 * Copyright (c) 2025-2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.geeksville.mesh

import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Intent
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import com.geeksville.mesh.model.UIViewModel
import com.geeksville.mesh.ui.MainScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import no.nordicsemi.kotlin.ble.core.android.AndroidEnvironment
import no.nordicsemi.kotlin.ble.environment.android.compose.LocalEnvironmentOwner
import org.meshtastic.core.model.util.dispatchMeshtasticUri
import org.meshtastic.core.navigation.DEEP_LINK_BASE_URI
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.channel_invalid
import org.meshtastic.core.ui.theme.AppTheme
import org.meshtastic.core.ui.theme.MODE_DYNAMIC
import org.meshtastic.core.ui.util.showToast
import org.meshtastic.feature.intro.AppIntroductionScreen
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: UIViewModel by viewModels()

    /**
     * Activity-lifecycle-aware client that binds to the mesh service. Note: This is used implicitly as it registers
     * itself as a LifecycleObserver in its init block.
     */
    @Inject internal lateinit var meshServiceClient: MeshServiceClient

    @Inject internal lateinit var androidEnvironment: AndroidEnvironment
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        // Storage permission deferred — requested from UI, not during startup

        // Explicitly set the cutout mode to ALWAYS for Android 15+ to satisfy Play Console recommendations.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        // Ensure the navigation bar remains seamless on modern Android versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            val theme by model.theme.collectAsStateWithLifecycle()
            val dynamic = theme == MODE_DYNAMIC
            val dark =
                when (theme) {
                    AppCompatDelegate.MODE_NIGHT_YES -> true
                    AppCompatDelegate.MODE_NIGHT_NO -> false
                    else -> isSystemInDarkTheme()
                }

            // Update system bar style when theme changes
            androidx.compose.runtime.SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
            }

            @Suppress("SpreadOperator")
            CompositionLocalProvider(*(LocalEnvironmentOwner provides androidEnvironment)) {
                AppTheme(dynamicColor = dynamic, darkTheme = dark) {
                    val appIntroCompleted by model.appIntroCompleted.collectAsStateWithLifecycle()

                    // Signal to the system that the initial UI is "fully drawn"
                    // once we've decided whether to show the intro or the main screen.
                    ReportDrawnWhen { true }

                    if (appIntroCompleted) {
                        MainScreen(uIViewModel = model)
                    } else {
                        AppIntroductionScreen(onDone = { model.onAppIntroCompleted() })
                    }
                }
            }
        }

        // Listen for new intents (e.g. deep links, NFC) without overriding onNewIntent
        addOnNewIntentListener { intent -> handleIntent(intent) }

        handleIntent(intent)
    }

    @Suppress("NestedBlockDepth")
    private fun handleIntent(intent: Intent) {
        android.util.Log.d("TrackImport", "handleIntent action=${intent.action} data=${intent.data} type=${intent.type}")
        val appLinkAction = intent.action
        val appLinkData: Uri? = intent.data

        when (appLinkAction) {
            Intent.ACTION_VIEW -> {
                val mimeType = intent.type ?: contentResolver.getType(appLinkData ?: android.net.Uri.EMPTY)
                val isConvoyFile = mimeType == "application/x-convoy-ride" ||
                    appLinkData?.path?.endsWith(".convoy") == true
                if (isConvoyFile && appLinkData != null) {
                    handleConvoyRideImport(appLinkData)
                } else if (isTrackFile(appLinkData, mimeType)) {
                    appLinkData?.let { handleTrackFileImport(it) }
                } else {
                    appLinkData?.let { handleMeshtasticUri(it) }
                }
            }

            NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                val rawMessages =
                    IntentCompat.getParcelableArrayExtra(
                        intent,
                        NfcAdapter.EXTRA_NDEF_MESSAGES,
                        NdefMessage::class.java,
                    )
                if (rawMessages != null) {
                    for (rawMsg in rawMessages) {
                        val msg = rawMsg as NdefMessage
                        for (record in msg.records) {
                            record.toUri()?.let { handleMeshtasticUri(it) }
                        }
                    }
                }
            }

            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Logger.d { "USB device attached" }
                showSettingsPage()
            }

            Intent.ACTION_MAIN -> {}

            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (text != null) {
                    createShareIntent(text).send()
                }
            }

            else -> {
                Logger.w { "Unexpected action $appLinkAction" }
            }
        }
    }

    private fun handleConvoyRideImport(uri: Uri) {
        try {
            val importDir = java.io.File(filesDir, "convoy_import").also { it.mkdirs() }
            val fileName  = "ride_${System.currentTimeMillis()}.convoy"
            val destFile  = java.io.File(importDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            // Peek at convoyDocType to confirm valid convoy file
            val json = org.json.JSONObject(destFile.readText())
            val docType = json.optString("convoyDocType", "unknown")
            android.util.Log.i("ConvoyImport", "Convoy file received — type=$docType file=$fileName")
        } catch (e: Exception) {
            android.util.Log.e("ConvoyImport", "Failed to copy convoy file: ${e.message}")
        }
    }

    private fun isTrackFile(uri: Uri?, mimeType: String?): Boolean {
        if (uri == null) return false
        val path = uri.path?.lowercase() ?: ""
        if (path.endsWith(".kml") || path.endsWith(".gpx")) return true
        if (mimeType == "application/vnd.google-earth.kml+xml") return true
        if (mimeType == "application/gpx+xml") return true
        // Check display name for extension
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIdx >= 0) {
                    val name = cursor.getString(nameIdx)?.lowercase() ?: ""
                    if (name.endsWith(".kml") || name.endsWith(".gpx")) return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private fun handleTrackFileImport(uri: Uri) {
        try {
            // Get filename
            var name = "imported_track.kml"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIdx >= 0) {
                    name = cursor.getString(nameIdx) ?: name
                }
            }

            // Read file content
            val text = contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: return

            // Ensure my_tracks directory exists
            val dir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS
                ), "my_tracks"
            )
            if (!dir.exists()) dir.mkdirs()

            val ext = name.substringAfterLast('.', "").lowercase()
            val isGpx = ext == "gpx"

            // Check for multi-track GPX (multiple <trk> blocks from onX, Gaia, etc.)
            if (isGpx && text.contains("<trk>")) {
                val trkPattern = Regex("""<trk>([\s\S]*?)</trk>""")
                val tracks = trkPattern.findAll(text).toList()
                if (tracks.size > 1) {
                    // Multi-track file — split into individual files
                    val namePattern = Regex("<name>([^<]*)</name>")
                    var imported = 0
                    for (trk in tracks) {
                        val trkContent = trk.groupValues[1]
                        val trkName = namePattern.find(trkContent)?.groupValues?.get(1)
                            ?.replace("/", "_")?.replace("\\", "_")?.trim()
                            ?: "track_${imported + 1}"
                        val safeName = trkName.replace(Regex("""[^a-zA-Z0-9_\- ]"""), "") + ".gpx"
                        val dest = java.io.File(dir, safeName)
                        if (!dest.exists()) {
                            val singleGpx = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                "<gpx version=\"1.1\" creator=\"GroupTrack\">\n" +
                                "<trk>${trkContent}</trk>\n</gpx>"
                            dest.writeText(singleGpx)
                            imported++
                        }
                    }
                    android.util.Log.i("TrackImport", "Split $name into $imported tracks")
                    android.widget.Toast.makeText(this, "Imported $imported tracks from $name", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
            }

            // Check for multi-track KML (multiple <Placemark> with <LineString>)
            if (!isGpx && text.contains("<Placemark>")) {
                val pmPattern = Regex("""<Placemark>([\s\S]*?)</Placemark>""")
                val placemarks = pmPattern.findAll(text).filter { it.groupValues[1].contains("<LineString>") }.toList()
                if (placemarks.size > 1) {
                    val namePattern = Regex("<name>([^<]*)</name>")
                    var imported = 0
                    for (pm in placemarks) {
                        val pmContent = pm.groupValues[0]
                        val pmName = namePattern.find(pm.groupValues[1])?.groupValues?.get(1)
                            ?.replace("/", "_")?.replace("\\", "_")?.trim()
                            ?: "track_${imported + 1}"
                        val safeName = pmName.replace(Regex("""[^a-zA-Z0-9_\- ]"""), "") + ".kml"
                        val dest = java.io.File(dir, safeName)
                        if (!dest.exists()) {
                            val singleKml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                "<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n" +
                                "<name>$pmName</name>\n$pmContent\n</Document>\n</kml>"
                            dest.writeText(singleKml)
                            imported++
                        }
                    }
                    android.util.Log.i("TrackImport", "Split $name into $imported tracks")
                    android.widget.Toast.makeText(this, "Imported $imported tracks from $name", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
            }

            // Single track file — copy directly
            val dest = java.io.File(dir, name)
            if (!dest.exists()) {
                dest.writeText(text)
                android.util.Log.i("TrackImport", "Imported: $name")
                android.widget.Toast.makeText(this, "Track imported: $name", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(this, "Track exists: $name", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("TrackImport", "Import error: ${e.message}")
            android.widget.Toast.makeText(this, "Import failed", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleMeshtasticUri(uri: Uri) {
        Logger.d { "Handling Meshtastic URI: $uri" }
        if (uri.toString().startsWith(DEEP_LINK_BASE_URI)) {
            model.handleNavigationDeepLink(uri)
            return
        }

        uri.dispatchMeshtasticUri(
            onChannel = { model.setRequestChannelSet(it) },
            onContact = { model.setSharedContactRequested(it) },
            onInvalid = { lifecycleScope.launch { showToast(Res.string.channel_invalid) } },
        )
    }

    private fun createShareIntent(message: String): PendingIntent {
        val deepLink = "$DEEP_LINK_BASE_URI/share?message=$message"
        val startActivityIntent =
            Intent(Intent.ACTION_VIEW, deepLink.toUri(), this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        val resultPendingIntent: PendingIntent? =
            TaskStackBuilder.create(this).run {
                addNextIntentWithParentStack(startActivityIntent)
                getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE)
            }
        return resultPendingIntent!!
    }

    private fun createSettingsIntent(): PendingIntent {
        val deepLink = "$DEEP_LINK_BASE_URI/connections"
        val startActivityIntent =
            Intent(Intent.ACTION_VIEW, deepLink.toUri(), this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        val resultPendingIntent: PendingIntent? =
            TaskStackBuilder.create(this).run {
                addNextIntentWithParentStack(startActivityIntent)
                getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE)
            }
        return resultPendingIntent!!
    }

    private fun showSettingsPage() {
        createSettingsIntent().send()
    }
}
