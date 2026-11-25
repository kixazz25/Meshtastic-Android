package com.geeksville.mesh.convoy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.geeksville.mesh.grouptrack.Gt
import com.geeksville.mesh.grouptrack.GtCheck
import com.geeksville.mesh.grouptrack.GtPanel
import com.geeksville.mesh.grouptrack.LocalGtPanelChrome

/*
 * DLTRACKS-2026-11-25 (2.8b, map.dl_tracks): the download-along-tracks picker, MOVED from Android's ConvoyCorridorPicker.kt
 * (CORRIDORPICKER-2026-08-05, PICKERSOURCES-2026-08-05, DLDEFAULTS-2026-10-24) and GtDownloadTracksScreen's frame
 * (MapScreens.kt, GTTRACKS-2026-10-23 / GTFIX1 / GTFIX3). Fred 14:58: moved as is; consolidating with the SELECT list
 * (ArtifactListPanel) waits until after certification. The one change: the legacy MAP SOURCES list is gone (one map).
 */

private val mono = FontFamily.Monospace

data class TrackPickInfo(
    val id: String,
    val name: String?,
    val geomHash: String
)

/**
 * Build picker rows from queryAllTracksForCorridor().
 * Rows with a missing geom_hash are dropped: enqueueCorridor cannot key on them,
 * so offering them would queue a job that can never derive.
 */
fun trackPickRowsFrom(rows: List<Map<String, String?>>): List<TrackPickInfo> =
    rows.mapNotNull { r ->
        val h = r["geom_hash"]
        val i = r["id"]
        if (h.isNullOrBlank() || i.isNullOrBlank()) null
        else TrackPickInfo(id = i, name = r["name"], geomHash = h)
    }

/**
 * Display labels, with repeats numbered off the query's stable ordering.
 * Names are NOT unique -- a row is a name-occurrence keyed
 * by geom_hash, so "Pine Valley" twice becomes "Pine Valley (1)" / "Pine Valley (2)".
 * A single occurrence is left unnumbered. Blank / null / 'Not Named' -> "(unnamed)".
 */
fun trackPickLabels(tracks: List<TrackPickInfo>): Map<String, String> {
    val base = tracks.associate { t ->
        val n = t.name?.trim()
        t.geomHash to if (n.isNullOrEmpty() || n == "Not Named") "(unnamed)" else n
    }
    val counts = HashMap<String, Int>()
    base.values.forEach { counts[it.lowercase()] = (counts[it.lowercase()] ?: 0) + 1 }
    val seen = HashMap<String, Int>()
    val out = LinkedHashMap<String, String>()
    tracks.forEach { t ->
        val label = base[t.geomHash] ?: "(unnamed)"
        val k = label.lowercase()
        if ((counts[k] ?: 0) > 1) {
            val n = (seen[k] ?: 0) + 1
            seen[k] = n
            out[t.geomHash] = "$label ($n)"
        } else {
            out[t.geomHash] = label
        }
    }
    return out
}

/**
 * Download maps along tracks -- the panel (GtDownloadTracksScreen's frame) on the chrome standard, with the
 * "Search tracks by name" box (GTFIX1-2026-10-23) that narrows the list the picker is given.
 */
@Composable
fun GtDownloadTracksPanel(
    tracks: List<TrackPickInfo>,
    replace: Boolean,
    onProceed: (selectedHashes: List<String>, replaceExisting: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val chrome = LocalGtPanelChrome.current
    var find by remember { mutableStateOf("") }
    val list = if (find.isBlank()) tracks else tracks.filter { (it.name ?: "").contains(find.trim(), ignoreCase = true) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            GtPanel(
                title = "Download maps along tracks",
                helpId = "map.dl_tracks",
                onHelp = { id -> chrome.openHelp(id) },
                onDone = onCancel,
                stays = false,
                width = if (maxWidth < 360.dp) maxWidth else 360.dp,
                maxHeight = maxHeight,
            ) {
                OutlinedTextField(
                    value = find, onValueChange = { find = it }, singleLine = true,
                    label = { Text("Search tracks by name") },
                    modifier = Modifier.fillMaxWidth()
                )
                GtCorridorPicker(tracks = list, replace = replace, onProceed = onProceed, onCancel = onCancel)
            }
        }
    }
}

/**
 * CORRIDORPICKER-2026-08-05 -- select tracks to download as corridors. Returns the CHECKED geom hashes.
 * ALL is an ordinary checkbox that ticks every row. There is no select-all mode.
 */
@Composable
fun GtCorridorPicker(
    tracks: List<TrackPickInfo>,
    replace: Boolean,
    onProceed: (selectedHashes: List<String>, replaceExisting: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    // Selection survives rotation. A Set is not Bundle-saveable, hence the explicit
    // listSaver over a state list. Plain `remember` is the 07-25 defect on the
    // download-confirm dialog: rotate to reach the buttons, lose the selections.
    val selected = rememberSaveable(
        saver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) { mutableStateListOf<String>() }
    // DLDEFAULTS-2026-10-24: start from the saved download settings; CHANGE alters THIS download only.
    var replaceExisting by rememberSaveable { mutableStateOf(replace) }
    var changeOpen by rememberSaveable { mutableStateOf(false) }

    val labels = remember(tracks) { trackPickLabels(tracks) }
    val allChecked = tracks.isNotEmpty() && selected.size == tracks.size
    val canProceed = selected.isNotEmpty()

    Column {
        Text("SELECT TRACKS", color = Gt.accent, fontSize = 12.sp, fontFamily = mono, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("corridor download · ${tracks.size} available", color = Gt.text, fontSize = 12.sp, fontFamily = mono)
        Spacer(Modifier.height(10.dp))

        if (tracks.isEmpty()) {
            // An empty list is a real state, not an error: a rider with no
            // recorded or imported tracks has nothing to build a corridor from.
            Text("No tracks available.", color = Gt.text, fontSize = 12.sp, fontFamily = mono)
            Spacer(Modifier.height(4.dp))
            Text("Record or import a track first — a corridor is derived from track geometry.",
                color = Gt.text, fontSize = 12.sp, fontFamily = mono)
            Spacer(Modifier.height(12.dp))
        } else {
            // ALL -- an ordinary checkbox that ticks every box.
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clickable {
                        if (allChecked) selected.clear()
                        else { selected.clear(); selected.addAll(tracks.map { it.geomHash }) }
                    },
                shape = RoundedCornerShape(6.dp),
                color = Gt.panel
            ) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GtCheck(allChecked)
                    Spacer(Modifier.width(8.dp))
                    Text("ALL", color = Gt.text, fontSize = 12.sp, fontFamily = mono, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            // LazyColumn, NOT Column + verticalScroll -- bounded viewport, rows composed on demand.
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                items(tracks, key = { it.geomHash }) { t ->
                    val checked = t.geomHash in selected
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .clickable { if (checked) selected.remove(t.geomHash) else selected.add(t.geomHash) },
                        shape = RoundedCornerShape(6.dp),
                        color = Gt.panel
                    ) {
                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            GtCheck(checked)
                            Spacer(Modifier.width(8.dp))
                            Text(labels[t.geomHash] ?: "(unnamed)", color = Gt.text, fontSize = 12.sp, fontFamily = mono)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // DLDEFAULTS-2026-10-24: the settings this download will use, in one line, and CHANGE (this run only).
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Download: the map and its elevation  ·  Replace existing tiles: " + (if (replaceExisting) "Yes" else "No"),
                    color = Gt.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(if (changeOpen) "DONE" else "CHANGE", color = Gt.text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { changeOpen = !changeOpen }.padding(horizontal = 8.dp, vertical = 6.dp))
            }
            if (changeOpen) {
                Text("For this download only — the defaults are set in Map › Download settings.", color = Gt.text, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                // ⚠ On a store that was just CLEARED this must be FALSE: there is nothing to replace, and true
                // would re-download every tile shared between overlapping corridors.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { replaceExisting = !replaceExisting }) {
                    GtCheck(replaceExisting)
                    Spacer(Modifier.width(8.dp))
                    Text("Replace existing tiles", color = Gt.text, fontSize = 12.sp, fontFamily = mono)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (!canProceed) "select at least one track" else "${selected.size} track(s) selected",
                color = if (!canProceed) Gt.text else Gt.accent,
                fontSize = 12.sp, fontFamily = mono, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Surface(
                modifier = Modifier.weight(1f).padding(end = 4.dp).clickable { onCancel() },
                shape = RoundedCornerShape(6.dp), color = Gt.panel
            ) {
                Text("CANCEL", color = Gt.text, fontSize = 12.sp, fontFamily = mono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 10.dp), textAlign = TextAlign.Center)
            }
            Surface(
                modifier = Modifier.weight(1f).padding(start = 4.dp)
                    .clickable(enabled = canProceed) { onProceed(selected.toList(), replaceExisting) },
                shape = RoundedCornerShape(6.dp),
                color = if (!canProceed) Gt.panel else Gt.text.copy(alpha = 0.18f)
            ) {
                Text("CONTINUE", color = if (!canProceed) Gt.text else Gt.accent,
                    fontSize = 12.sp, fontFamily = mono, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 10.dp), textAlign = TextAlign.Center)
            }
        }
    }
}
