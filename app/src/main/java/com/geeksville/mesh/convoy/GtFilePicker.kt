package com.geeksville.mesh.convoy

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.grouptrack.Gt
import com.geeksville.mesh.grouptrack.GtButton

/**
 * ONEPICKER-2026-11-17 -- GroupTrack's OWN file picker, for the four directories the app owns.
 *
 * Fred 11-17 12:56: "you can create one file picker and change section and title based on directory."
 * Fred 11-17 12:57: "use the same picker for all imports rides, maps, dr (grouptrac), and fetaures."
 * Fred 11-17: "we wouldn't;t use the file picker for the directories owned bu the app. we would write our own."
 *
 * ⭐ WHY THIS EXISTS AT ALL. The system picker (OpenMultipleDocuments) cannot be told "show me only
 * GroupTrack's folders", so every import made the rider navigate the whole device to find a file the app
 * itself had written. This lists one owning directory and nothing else.
 *
 * ⚠ THIS IS A RESTORATION, NOT A NEW SCREEN. ConvoyTrackImportScreen WAS an in-app browser over Downloads
 * until scoped storage gutted it (IMPORTPICKER-2026-09-13, STORAGECTX-2026-09-13: "AND THE PICKER OPENS
 * ITSELF"). What makes it possible again is that these rows are the app's OWN MediaStore entries, which it
 * can list without any grant. That is also the limit: it lists what GroupTrack wrote, never the rest of
 * Downloads. An arrival from elsewhere still comes in as an attachment through ConvoyFileReceiver.
 *
 * THREE OPTIONS, per Fred 11-17: "two option for internal picker, import, or email" and then "is dad two
 * topics for files. s/b be 3. we need a delete also."
 *
 * ⛔ THE FRAME IS THE CALLER'S. Every launch point already wraps its content in GtScreenFrame, which draws
 * the title, HELP, ISSUE and close (STD1D / ISSUE-2026-10-24), and carries that launch point's OWN help id
 * -- so the picker needs no new help id and no new manual fold. This body therefore draws NO header of its
 * own beyond the section line. A launch point that has no frame yet wraps this in one, using [title].
 */
object GtFilePicker {

    /** ONEPICKER-2026-11-17: the title for a launch point that has no frame of its own yet. */
    fun title(family: GtFileOpen.Family): String = when (family) {
        GtFileOpen.Family.MAP_FEATURES -> "Import map features"
        GtFileOpen.Family.RIDES -> "Import a ride"
        GtFileOpen.Family.MAPS -> "Import a map recipe"
        GtFileOpen.Family.ROOT -> "Import from recovery"
    }

    /**
     * ONEPICKER-2026-11-17: what the rider is looking at -- the "section", changed by directory.
     * Fred 11-17 12:56: "change section and title based on directory."
     */
    fun section(family: GtFileOpen.Family): String = family.dirName

    /**
     * ONEPICKER-2026-11-17: the extensions each directory offers.
     * Fred 11-17: "it is kms. gpx, kms, and geo-json files are allowed."  ("kms" = KML/KMZ.)
     * ⚠ ROOT is the DR directory and holds every artifact the recovery job writes, so it filters nothing:
     * an empty list means "offer everything in the folder".
     */
    fun exts(family: GtFileOpen.Family): List<String> = when (family) {
        GtFileOpen.Family.MAP_FEATURES -> listOf(".gpx", ".kml", ".kmz", ".geojson", ".json")
        GtFileOpen.Family.RIDES -> listOf(".convoy", ".json")
        GtFileOpen.Family.MAPS -> listOf(".gtmaps", ".json")
        GtFileOpen.Family.ROOT -> emptyList()
    }
}

/**
 * ONEPICKER-2026-11-17: the one picker body. Drop it inside the caller's GtScreenFrame.
 *
 * @param family which owning directory to list.
 * @param onImport given the rows the rider chose. ⛔ THE CALLER MUST NOT DELETE THESE SOURCES -- they are
 *   the recovery copies (rule 2: "Anything processed form downloads should be archived to the owning sub
 *   directory"). Importing an archived file must never consume the archive.
 */
/**
 * SAVEDFEAT-2026-11-18: [showImport] -- CODE RULE 1, so here is why a caller omits it. The manage
 * row's job is DELETE and EMAIL (Fred 07:12: "work with exported map features to delete form saves or
 * send a prior item v[ia] email"). IMPORT already has its own row, and offering it here would be the
 * duplicate function this whole day has been removing.
 * ⚠ The extension filter is NOT a parameter: the folder's own list is correct for both callers.
 * Fred 07:14: "this is only importable and only map features."
 */
@Composable
fun GtFilePickerBody(
    family: GtFileOpen.Family,
    showImport: Boolean = true,
    onImport: (List<Uri>) -> Unit = {},
) {
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf<List<GtFileHome.Row>>(emptyList()) }
    var chosen by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(family, reload) {
        rows = GtFileHome.listFiles(ctx, family, GtFilePicker.exts(family))
        chosen = emptySet()
        status = if (rows.isEmpty()) "Nothing in this folder yet." else ""
    }

    val picked = rows.filter { chosen.contains(it.id) }

    Column(modifier = Modifier.fillMaxWidth().background(Gt.panel).verticalScroll(rememberScrollState())) {

        // the SECTION -- changes by directory (Fred 11-17 12:56)
        Text(GtFilePicker.section(family), color = Gt.text, fontSize = 15.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
        Text(
            if (GtFilePicker.exts(family).isEmpty()) "Everything GroupTrack saved here"
            else GtFilePicker.exts(family).joinToString(" ") { it.removePrefix(".").uppercase() },
            color = Gt.dim, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
        )

        if (status.isNotEmpty()) {
            Text(status, color = Gt.dim, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        }

        for (r in rows) {
            val on = chosen.contains(r.id)
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { chosen = if (on) chosen - r.id else chosen + r.id }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (on) "☑" else "☐", color = Gt.text, fontSize = 17.sp,
                    modifier = Modifier.padding(end = 10.dp))
                Text(r.name, color = Gt.text, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
            }
        }

        if (rows.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly) {
                if (showImport) GtButton("IMPORT", { if (picked.isNotEmpty()) onImport(picked.map { it.uri }) },
                    enabled = picked.isNotEmpty())
                GtButton("EMAIL", {
                    // EMAILOPT-2026-11-17: Fred 11-17 -- "any explicit share option and they all should be
                    // come emails". Same Gmail-targeted shape as GtBackup.send(), with its chooser fallback.
                    val uris = ArrayList<Uri>(picked.map { it.uri })
                    val mail = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        // EMAILHOUSE-2026-11-17: "*/*" so the chooser reads as mail apps.
                        // CONVERTFIX-2026-10-30 is about the MediaStore insert mime, not Intent.type.
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        putExtra(Intent.EXTRA_SUBJECT, "GroupTrack — " + GtFilePicker.section(family))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    // EMAILHOUSE-2026-11-17: no setPackage -- the manifest cannot see Gmail to
                    // resolve it, so that branch never fired. The chooser is the mechanism.
                    runCatching {
                        ctx.startActivity(Intent.createChooser(mail, "Email " + picked.size + " file(s)")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure {
                        android.widget.Toast.makeText(ctx,
                            "No email app on this tablet.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }, enabled = picked.isNotEmpty())
                GtButton("DELETE", { if (picked.isNotEmpty()) confirmDelete = true },
                    emphasis = Gt.bad, enabled = picked.isNotEmpty())
            }
        }
    }

    if (confirmDelete) {
        // ⛔ A delete here is PERMANENT and it removes a RECOVERY copy. It is never silent and never implied
        // by an import: the rider names the files and confirms, every time.
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete " + picked.size + " file(s)?") },
            text = {
                Text("These are GroupTrack's recovery copies in " + GtFilePicker.section(family) +
                    ". Deleting cannot be undone.\n\n" + picked.joinToString("\n") { it.name })
            },
            confirmButton = {
                TextButton(onClick = {
                    var gone = 0
                    for (r in picked) {
                        try { if (ctx.contentResolver.delete(r.uri, null, null) > 0) gone++ }
                        catch (e: Exception) {
                            android.util.Log.w("GTPICKER", "ONEPICKER delete failed " + r.name + ": " + e.message)
                        }
                    }
                    confirmDelete = false
                    status = "Deleted " + gone + " file(s)."
                    reload++
                }) { Text("DELETE", color = Gt.bad, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("CANCEL") } },
        )
    }
}
