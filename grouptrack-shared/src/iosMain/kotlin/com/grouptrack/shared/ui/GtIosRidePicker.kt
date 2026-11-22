package com.grouptrack.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.grouptrack.Gt
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.platform.IosRideFiles
import com.grouptrack.shared.platform.IosRideImportPlatform
import com.grouptrack.shared.ride.GtRideImport

/**
 * IOSRIDEPICK-2026-11-22: Rides > Import a ride, on the iPad.
 *
 * Fred 14:03: "forget the four components[,] just launch the import when clicked from email or
 * file picker from group tracks file folder." This is that picker: the ride files in
 * GroupTrack's own rides folder, and a tap that launches the shared import.
 *
 * ⛔ NOT an OS document picker (Fred 13:47) and NOT MediaStore. Android's GtFilePicker needs
 * MediaStore because its files live in the PUBLIC Download folder; the iPad's live in the app's
 * own Documents, where [com.grouptrack.shared.ride.RideFiles.list] is already the service.
 *
 * ⛔ IT ADDS NO ENGINE. [GtRideImport.importRide] (GtRides.kt:1446, RIDEIMPORT3-2026-09-25) is
 * the same shared path the emailed ride uses, and the four processes Fred named -- ride, radio
 * config, gpx, mapping -- all run off the one JSON it stores.
 *
 * ⚠ WHAT DOES NOT HAPPEN YET, reported on screen rather than left to be discovered: importRide
 * ends in platform.offer(stagedGpx), and IosRideImportPlatform.offer() logs a WARN because no
 * iPad screen consumes IosRideImportLauncher.pending. So the ride is STORED and its GPX is
 * STAGED, and the route is NOT on the map until the staged-GPX surface is ported.
 */
@Composable
fun GtIosRidePicker(
    onClose: () -> Unit,
    onMessage: (String, String) -> Unit,
) {
    val log = remember { gtLogger("GtIosRidePicker") }
    val fs = remember { IosRideFiles() }
    val platform = remember { IosRideImportPlatform() }
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }

    LaunchedEffect(reload) {
        val dir = fs.ridesDir()
        // CODE RULE 1: list() returns null for "not a folder / not readable" -- exactly
        // listFiles' null. A tablet that never saved a ride has no rides folder yet
        // (RIDEMKDIR-2026-09-24), and that is not an error.
        val all = fs.list(dir)
        files = all?.filter { it.endsWith(".json", ignoreCase = true) }?.sorted() ?: emptyList()
        status = when {
            all == null -> "No rides folder yet \u2014 a ride arrives by email, or lands here when imported."
            files.isEmpty() -> "No ride files in GroupTrack's rides folder."
            else -> ""
        }
        log.i { "IOSRIDEPICK-2026-11-22: " + files.size + " ride file(s) in " + dir }
    }

    Box(modifier = Modifier.fillMaxSize().background(Gt.panel), contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(14.dp), color = Gt.panel) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Import a ride", color = Gt.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("\u2715", color = Gt.accent, fontSize = 18.sp) }
                }
                Text("GroupTrack rides folder", color = Gt.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)

                if (status.isNotBlank()) Text(status, color = Gt.warn, fontSize = 13.sp)

                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    files.forEach { name ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = busy.isBlank()) {
                                busy = name
                                status = ""
                                val path = fs.child(fs.ridesDir(), name)
                                val said = try {
                                    val content = fs.readText(path)
                                    // bringForward = false: the app is already open (2.7e's rule --
                                    // true only from the email path, which must foreground the app).
                                    GtRideImport.importRide(fs, content, bringForward = false, platform = platform)
                                } catch (e: Exception) {
                                    log.e { "IOSRIDEPICK-2026-11-22: could not read " + name + ": " + e.message }
                                    "Could not read " + name + "."
                                }
                                busy = ""
                                reload += 1
                                // importRide returns the rider's message. The staged-GPX gap is added
                                // because offer() only HOLDS the file -- see this file's header.
                                status = said
                                onMessage("rides.import", said)
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(name, color = Gt.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    if (busy == name) "importing\u2026" else "tap to import",
                                    color = Gt.dim, fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }

                // ⚠ Said on the glass, not only in a log: the route is not on the map yet.
                Text(
                    "A ride is stored with its radio configuration when imported. Its route is staged \u2014 " +
                        "adding it to the map needs the track import screen, which is not on the iPad yet.",
                    color = Gt.dim, fontSize = 12.sp,
                )
            }
        }
    }
}
