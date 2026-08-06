package com.geeksville.mesh.convoy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// CORRIDORPICKER-2026-08-05
// Palette duplicated from ConvoyDownloadConfirm.kt rather than un-privating it
// there. Seven lines against a cross-file coupling that would surprise someone
// later; ConvoyDownloadConfirm is the MODEL for this file and stays untouched.
private val mono = FontFamily.Monospace
private val bg = Color(0xFF0D1117)
private val cardBg = Color(0xFF161B22)
private val green = Color(0xFF1CF0A0)
private val txtB = Color(0xFFE8EEF5)
private val txtD = Color(0xFF7A8DA0)

/**
 * One selectable track. geomHash is the key enqueueCorridor works from -- the id is
 * carried for logging and future detail lookups only.
 */
data class TrackPickInfo(
    val id: String,
    val name: String?,
    val geomHash: String
)

/**
 * Build picker rows from SpatialDbManager.queryAllTracksForCorridor().
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
 * Names are NOT unique (SpatialDbManager:413) -- a row is a name-occurrence keyed
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
 * CORRIDORPICKER-2026-08-05 -- select tracks to download as corridors.
 *
 * Returns the CHECKED geom hashes. The caller loops enqueueCorridor over them, once
 * per selected map source, so the job count is tracks x sources.
 *
 * ALL is an ordinary checkbox that ticks every row. There is no select-all mode:
 * the callback shape is identical either way, which is what keeps refresh able to
 * reuse this without a second path.
 */
@Composable
fun ConvoyCorridorPicker(
    tracks: List<TrackPickInfo>,
    // PICKERSOURCES-2026-08-05: was `sourceCount: Int`. The picker now collects the
    // SOURCE selection itself, because ConvoyDownloadConfirm only renders with a
    // valid bbox (ConvoyMapViewerScreen:1928) and a corridor has none. Reuses
    // SlotDisplayInfo from ConvoyDownloadConfirm.kt -- same package, and the screen
    // already builds that list for the area path.
    slots: List<SlotDisplayInfo>,
    onProceed: (selectedHashes: List<String>, selectedSlots: List<String>, replaceExisting: Boolean) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
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

    // PICKERSOURCES-2026-08-05: source selection, same saver pattern as the tracks.
    val selectedSlots = rememberSaveable(
        saver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) { mutableStateListOf<String>() }
    // Preselect once from SlotDisplayInfo, matching how ConvoyDownloadConfirm seeds
    // its own selection. Keyed on the slot list so a changed catalogue reseeds.
    LaunchedEffect(slots) {
        if (selectedSlots.isEmpty()) {
            selectedSlots.addAll(slots.filter { it.preSelected }.map { it.slotName })
        }
    }
    var replaceExisting by rememberSaveable { mutableStateOf(false) }

    val labels = remember(tracks) { trackPickLabels(tracks) }
    val allChecked = tracks.isNotEmpty() && selected.size == tracks.size
    // The gate reads the ACTUAL source selection, not a hint. Without it ALL looks
    // free -- which is how 10 tracks became 215 jobs.
    val jobs = selected.size * selectedSlots.size
    val canProceed = selected.isNotEmpty() && selectedSlots.isNotEmpty()

    Surface(
        modifier = modifier.width(300.dp),
        shape = RoundedCornerShape(12.dp),
        color = bg,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("SELECT TRACKS", color = green, fontSize = 12.sp,
                fontFamily = mono, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("corridor download \u00b7 ${tracks.size} available", color = txtD,
                fontSize = 9.sp, fontFamily = mono)
            Spacer(Modifier.height(10.dp))

            if (tracks.isEmpty()) {
                // An empty list is a real state, not an error: a rider with no
                // recorded or imported tracks has nothing to build a corridor from.
                Text("No tracks available.", color = txtD, fontSize = 10.sp, fontFamily = mono)
                Spacer(Modifier.height(4.dp))
                Text("Record or import a track first \u2014 a corridor is derived from track geometry.",
                    color = txtD, fontSize = 8.sp, fontFamily = mono)
                Spacer(Modifier.height(12.dp))
            } else {
                // ALL -- an ordinary checkbox that ticks every box.
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .clickable {
                            if (allChecked) selected.clear()
                            else {
                                selected.clear()
                                selected.addAll(tracks.map { it.geomHash })
                            }
                        },
                    shape = RoundedCornerShape(6.dp),
                    color = if (allChecked) Color(0xFF1A3050) else cardBg
                ) {
                    Row(modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = allChecked,
                            onCheckedChange = { on ->
                                selected.clear()
                                if (on) selected.addAll(tracks.map { it.geomHash })
                            },
                            modifier = Modifier.size(18.dp),
                            colors = CheckboxDefaults.colors(
                                checkedColor = green, checkmarkColor = Color.Black)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("ALL", color = txtB, fontSize = 11.sp,
                            fontFamily = mono, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(6.dp))

                // LazyColumn, NOT Column + verticalScroll -- see the 07-25 lesson in
                // the patch header. Bounded viewport, rows composed on demand.
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(tracks, key = { it.geomHash }) { t ->
                        val checked = t.geomHash in selected
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                .clickable {
                                    if (checked) selected.remove(t.geomHash)
                                    else selected.add(t.geomHash)
                                },
                            shape = RoundedCornerShape(6.dp),
                            color = if (checked) Color(0xFF1A3050) else cardBg
                        ) {
                            Row(modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { on ->
                                        if (on) selected.add(t.geomHash)
                                        else selected.remove(t.geomHash)
                                    },
                                    modifier = Modifier.size(18.dp),
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = green, checkmarkColor = Color.Black)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(labels[t.geomHash] ?: "(unnamed)", color = txtB,
                                    fontSize = 11.sp, fontFamily = mono)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // PICKERSOURCES-2026-08-05: MAP SOURCES, same idiom as the track rows
                // above and as ConvoyDownloadConfirm's slot list.
                Text("MAP SOURCES", color = txtD, fontSize = 9.sp,
                    fontFamily = mono, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                slots.forEach { slot ->
                    val on = slot.slotName in selectedSlots
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .clickable {
                                if (on) selectedSlots.remove(slot.slotName)
                                else selectedSlots.add(slot.slotName)
                            },
                        shape = RoundedCornerShape(6.dp),
                        color = if (on) Color(0xFF1A3050) else cardBg
                    ) {
                        Row(modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = on,
                                onCheckedChange = { c ->
                                    if (c) selectedSlots.add(slot.slotName)
                                    else selectedSlots.remove(slot.slotName)
                                },
                                modifier = Modifier.size(18.dp),
                                colors = CheckboxDefaults.colors(
                                    checkedColor = green, checkmarkColor = Color.Black)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(slot.slotName, color = txtB, fontSize = 11.sp,
                                    fontFamily = mono, fontWeight = FontWeight.Bold)
                                Text(slot.sourceName, color = txtD, fontSize = 9.sp,
                                    fontFamily = mono)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ⚠ On a store that was just CLEARED this must be FALSE: there is
                // nothing to replace, and true would re-download every tile shared
                // between overlapping corridors. The clear/replace interlock lands
                // with Clear Tile Source (stubbed V2.7).
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { replaceExisting = !replaceExisting }) {
                    Checkbox(
                        checked = replaceExisting,
                        onCheckedChange = { replaceExisting = it },
                        modifier = Modifier.size(18.dp),
                        colors = CheckboxDefaults.colors(
                            checkedColor = green, checkmarkColor = Color.Black)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Replace existing tiles", color = txtD, fontSize = 10.sp,
                        fontFamily = mono)
                }

                Spacer(Modifier.height(8.dp))

                // The estimate gate lives HERE, once, because both callers (download
                // now, refresh later) pass through this picker.
                Text(
                    if (!canProceed) "select at least one track and one source"
                    else "${selected.size} track(s) \u00d7 ${selectedSlots.size} source(s) = $jobs job(s)",
                    color = if (!canProceed) txtD else green,
                    fontSize = 10.sp, fontFamily = mono, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
            }

            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Surface(
                    modifier = Modifier.weight(1f).padding(end = 4.dp).clickable { onCancel() },
                    shape = RoundedCornerShape(6.dp), color = cardBg
                ) {
                    Text("CANCEL", color = txtD, fontSize = 11.sp, fontFamily = mono,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 10.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Surface(
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                        // PICKERSOURCES-2026-08-05: BOTH a track and a source are
                        // required -- either alone queues nothing.
                        .clickable(enabled = canProceed) {
                            onProceed(selected.toList(), selectedSlots.toList(), replaceExisting)
                        },
                    shape = RoundedCornerShape(6.dp),
                    color = if (!canProceed) cardBg else Color(0xFF1A3050)
                ) {
                    Text("CONTINUE", color = if (!canProceed) txtD else green,
                        fontSize = 11.sp, fontFamily = mono, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 10.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}
