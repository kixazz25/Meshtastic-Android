package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * EXPORTONE-2026-11-18 (Fred 06:45): four Map rows become ONE. "we have four option[s] to save all
 * feature types. simpler to have one option and the[n] sel[ect] the type(s) we want to save."
 *
 * Tick the types, SELECT ALL for everything, and tick the email box if the files are to be sent.
 * Fred 07:12: "export map features contain a box to send an email with these features."
 *
 * The email is declared BEFORE the export, not asked afterwards. That is what keeps it to one
 * solicitation (Fred 07:04): the rider says what they want once, and the share sheet is the only panel
 * that appears. Unticked, EXPORT writes the files and nothing opens.
 *
 * Emailing or deleting something saved EARLIER is the other row -- "work with exported map features"
 * (Fred 07:12) -- which lists the folder with DELETE and EMAIL.
 */
@Composable
fun GtExportFeaturesScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf(setOf<String>()) }
    var alsoEmail by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!busy) onDone() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        GtScreenFrame("Export map features", "map.export_all", onDone,
            note = if (busy) "Exporting — leave this open" else "") {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Each type you tick is written as ONE GPX file into your GroupTrack Map Features " +
                    "folder, named with today's date.", color = Gt.text, fontSize = 13.sp)
                GtExportAll.TYPES.forEach { (key, label) ->
                    ExportTick(label, key in picked, enabled = !busy) { on ->
                        picked = if (on) picked + key else picked - key
                    }
                }
                ExportTick("Send these features as an email", alsoEmail, enabled = !busy) { alsoEmail = it }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GtButton("SELECT ALL",
                        { picked = GtExportAll.TYPES.map { it.first }.toSet() },
                        enabled = !busy && picked.size < GtExportAll.TYPES.size)
                    GtButton("EXPORT", {
                        busy = true
                        scope.launch {
                            val files = withContext(Dispatchers.IO) { GtExportAll.exportMany(ctx, picked) }
                            busy = false
                            // DRROW-2026-11-18 (Fred 07:45): "export feature should end processing when
                            // export is selected" -- the job finishes and the screen goes. The outcome is a
                            // toast, not a result left on screen for the rider to dismiss.
                            android.widget.Toast.makeText(ctx,
                                if (files.isEmpty())
                                    "Nothing to export — none of the ticked types have anything on this tablet yet."
                                else "Saved " + files.size + " file(s) to GroupTrack Map Features.",
                                android.widget.Toast.LENGTH_LONG).show()
                            // the box was ticked before the run, so the share sheet is the only panel
                            if (alsoEmail && files.isNotEmpty()) GtExportAll.emailWritten(ctx, files)
                            onDone()
                        }
                    }, enabled = !busy && picked.isNotEmpty())
                }
            }
        }
    }
}

/**
 * SAVEDFEAT-2026-11-18 (Fred 07:12): "The second export map features is work with exported map features to
 * delete form saves or send a prior item v[ia] email." A new row in the EXPORT MAP FEATURES group
 * (Fred 07:13).
 *
 * The Map Features folder only, and only the files the app can read back -- Fred 07:14: "this is only
 * importable and only map features." Select, then EMAIL or DELETE. No IMPORT: that is the "Import
 * files" row's job.
 *
 * \u2b50 This is the re-send function that did not exist. Until now the only chooser lived at the moment
 * of creation, so emailing last week's export meant exporting it again; and the only way to delete one
 * was the system Files app, outside GroupTrack entirely.
 */
@Composable
fun GtSavedFeaturesScreen(onDone: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDone,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        GtScreenFrame("Work with exported map features", "map.features", onDone) {
            com.geeksville.mesh.convoy.GtFilePickerBody(
                family = com.geeksville.mesh.convoy.GtFileOpen.Family.MAP_FEATURES,
                showImport = false)
        }
    }
}

/** EXPORTONE-2026-11-18: one tick row. Copied from TrailCleanup's CleanTick so the two read alike. */
@Composable
private fun ExportTick(label: String, on: Boolean, enabled: Boolean, set: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { set(!on) }.padding(vertical = 4.dp)) {
        Checkbox(checked = on, onCheckedChange = set, enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = Gt.accent, uncheckedColor = Gt.text,
                checkmarkColor = Gt.panel))
        Text(label, color = Gt.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}
