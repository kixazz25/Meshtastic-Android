package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.convoy.DownloadBbox
import com.geeksville.mesh.convoy.RiderTrailWriter
import com.geeksville.mesh.convoy.SpatialDbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TRAILCLEAN-2026-11-04 (Fred 08:42-08:54). Two ways to take trails (and tracks) back out:
 *  - Delete trails by source: every source of trails on this tablet; an import is "import · <source name> · <date>"
 *    (trail_properties.agency_id -- an import has no agency). A trail another source also has is KEPT and only loses this source.
 *    Rider trails ("rides") are not offered: they are made from tracks and come back while the tracks are there.
 *  - Remove tracks and trails by area: a drawn box; tracks and rider trails ticked, other trails (state sources, imports) not.
 *    Only things lying wholly inside the box. Waypoints, routes and rides are never touched.
 */
object GtTrailCleanup {
    private const val TAG = "TrailClean"
    class Counts(val tracks: Int, val rider: Int, val other: Int)
    class Src(val source: String, val batch: String, val trails: Int) {
        val label: String get() = when {
            source == "import" && batch.isNotEmpty() -> "import · $batch"
            source == "import" -> "import (before 11-04 — no source name)"
            else -> source
        }
    }

    private fun inside(b: DownloadBbox) = "min_lat>=${b.south} AND max_lat<=${b.north} AND min_lon>=${b.west} AND max_lon<=${b.east}"
    private const val RIDER = "(carto_code='rider' OR carto_code_source='rider')"
    private fun count(sql: String): Int = try {
        SpatialDbManager.getSpatialDb()?.rawQuery(sql, null)?.use { if (it.moveToFirst()) it.getInt(0) else 0 } ?: 0
    } catch (e: Exception) { android.util.Log.w(TAG, "count: ${e.message}"); 0 }
    private fun ids(db: android.database.sqlite.SQLiteDatabase?, sql: String, args: Array<String> = emptyArray()): List<String> {
        val out = ArrayList<String>()
        try { db?.rawQuery(sql, args)?.use { while (it.moveToNext()) if (!it.isNull(0)) out.add(it.getString(0)) } }
        catch (e: Exception) { android.util.Log.w(TAG, "ids: ${e.message}") }
        return out
    }

    fun counts(b: DownloadBbox): Counts = Counts(
        count("SELECT COUNT(*) FROM tracks WHERE " + inside(b)),
        count("SELECT COUNT(*) FROM trails WHERE $RIDER AND " + inside(b)),
        count("SELECT COUNT(*) FROM trails WHERE NOT $RIDER AND " + inside(b)))

    /** Deletes what is ticked inside the box -- each track with its file, each trail from all three stores. Returns "n tracks, m trails". */
    fun removeArea(b: DownloadBbox, tracks: Boolean, rider: Boolean, other: Boolean): String {
        val sp = SpatialDbManager.getSpatialDb()
        var nt = 0; var nr = 0
        if (tracks) for (id in ids(sp, "SELECT track_id FROM tracks WHERE " + inside(b))) { SpatialDbManager.deleteTrackFromDb(id); nt++ }
        val where = when { rider && other -> "1=1"; rider -> RIDER; other -> "NOT $RIDER"; else -> "" }
        if (where.isNotEmpty()) for (id in ids(sp, "SELECT trail_id FROM trails WHERE $where AND " + inside(b))) { SpatialDbManager.deleteTrailFromDb(id); nr++ }
        if (nr > 0 || nt > 0) RiderTrailWriter.trailsChanged.value = System.currentTimeMillis()   // the maps reload
        android.util.Log.i(TAG, "TRAILCLEAN area N${b.north} S${b.south} E${b.east} W${b.west}: $nt tracks, $nr trails removed (tracks=$tracks rider=$rider other=$other)")
        return "$nt tracks, $nr trails removed"
    }

    fun sources(): List<Src> {
        val out = ArrayList<Src>()
        try {
            SpatialDbManager.getExtensionDb()?.rawQuery(
                "SELECT source_id, CASE WHEN source_id='import' THEN COALESCE(agency_id,'') ELSE '' END b, COUNT(DISTINCT trail_id) " +
                    "FROM trail_properties WHERE source_id='import' GROUP BY 1,2 ORDER BY 3 DESC", null)?.use {   // TRAILSRCONLY-2026-11-04: only our imports
                while (it.moveToNext()) out.add(Src(it.getString(0), it.getString(1) ?: "", it.getInt(2)))
            }
        } catch (e: Exception) { android.util.Log.w(TAG, "sources: ${e.message}") }
        return out
    }

    /** Deletes one source's trails. A trail another source also has keeps the trail and loses only this source's row. */
    fun deleteSource(s: Src): String {
        val ex = SpatialDbManager.getExtensionDb() ?: return "No trail database"
        val cond = if (s.source == "import") "source_id='import' AND COALESCE(agency_id,'')=?" else "source_id=?"
        val arg = if (s.source == "import") s.batch else s.source
        val list = ids(ex, "SELECT DISTINCT trail_id FROM trail_properties WHERE $cond", arrayOf(arg))
        var gone = 0; var kept = 0
        val sp = SpatialDbManager.getSpatialDb()
        try { ex.beginTransaction(); sp?.beginTransaction()
            for (id in list) {
                val others = ex.rawQuery("SELECT COUNT(*) FROM trail_properties WHERE trail_id=? AND NOT ($cond)", arrayOf(id, arg)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
                if (others > 0) { ex.execSQL("DELETE FROM trail_properties WHERE trail_id=? AND $cond", arrayOf<Any>(id, arg)); kept++ }
                else { SpatialDbManager.deleteTrailFromDb(id); gone++ }
            }
            ex.setTransactionSuccessful(); sp?.setTransactionSuccessful()
        } catch (e: Exception) { android.util.Log.e(TAG, "deleteSource: ${e.message}") }
        finally { try { ex.endTransaction() } catch (_: Exception) {}; try { sp?.endTransaction() } catch (_: Exception) {} }
        if (gone > 0) RiderTrailWriter.trailsChanged.value = System.currentTimeMillis()
        android.util.Log.i(TAG, "TRAILCLEAN source '${s.label}': $gone trails deleted, $kept kept (another source has them)")
        return "$gone trails deleted" + if (kept > 0) ", $kept kept (another source also has them)" else ""
    }
}

@Composable
private fun CleanTick(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { set(!on) }) {
        Checkbox(checked = on, onCheckedChange = set, colors = CheckboxDefaults.colors(checkedColor = Gt.accent, uncheckedColor = Gt.text, checkmarkColor = Gt.panel))
        Text(label, color = Gt.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

/** TRAILCLEAN-2026-11-04: Remove tracks and trails by area -- the box is drawn first (GtArea purpose "rmfeat"). */
@Composable
fun GtRemoveFeaturesScreen(bbox: DownloadBbox, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var c by remember { mutableStateOf<GtTrailCleanup.Counts?>(null) }
    var tracks by remember { mutableStateOf(true) }
    var rider by remember { mutableStateOf(true) }
    var other by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(bbox) { c = withContext(Dispatchers.IO) { GtTrailCleanup.counts(bbox) } }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { GtDialogTitle("Remove tracks and trails?", "map.rm_feat", onDone) },
        text = {
            Column {
                val n = c
                if (n == null) { Text("Counting…", color = Gt.text, fontSize = 13.sp); return@Column }
                Text("Everything lying wholly inside the box. Waypoints, routes and rides are never removed. This cannot be undone.",
                    color = Gt.text, fontSize = 12.sp)
                CleanTick("Tracks (and their files): ${n.tracks}", tracks) { tracks = it }
                CleanTick("Rider trails (made from tracks): ${n.rider}", rider) { rider = it }
                CleanTick("Other trails (state sources, imports): ${n.other}", other) { other = it }
                if (busy) Text("Removing…", color = Gt.accent, fontSize = 13.sp)
            }
        },
        confirmButton = {
            val n = c
            val total = if (n == null) 0 else (if (tracks) n.tracks else 0) + (if (rider) n.rider else 0) + (if (other) n.other else 0)
            Text("REMOVE", color = if (total > 0 && !busy) Gt.bad else Gt.dim, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(enabled = total > 0 && !busy) {
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { GtTrailCleanup.removeArea(bbox, tracks, rider, other) }
                        android.widget.Toast.makeText(ctx, r, android.widget.Toast.LENGTH_LONG).show()
                        busy = false; onDone()
                    }
                }.padding(12.dp))
        },
        dismissButton = {
            Text("CANCEL", color = Gt.dim, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable(enabled = !busy) { onDone() }.padding(12.dp))
        },
        containerColor = Gt.panel
    )
}

/** TRAILCLEAN-2026-11-04: Delete trails by source -- pick a source, confirm. */
@Composable
fun GtTrailSourcesScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<GtTrailCleanup.Src>?>(null) }
    var pick by remember { mutableStateOf<GtTrailCleanup.Src?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { list = withContext(Dispatchers.IO) { GtTrailCleanup.sources() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!busy) onDone() },   // TRAILSRCDIALOG-2026-11-04: above the map's buttons
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
    GtScreenFrame("Delete imported trails by source", "map.trailsrc", onDone) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Every trail import on this tablet, by the source name given at import. A trail's source shows on its details. " +   // TRAILSRCONLY-2026-11-04
                "Trails from state imports (Forest Service, BLM, OpenStreetMap ...) and rider trails are not listed.", color = Gt.text, fontSize = 13.sp)
            val l = list
            if (l == null) Text("Reading…", color = Gt.text, fontSize = 14.sp)
            else if (l.isEmpty()) Text("No imported trails on this tablet.", color = Gt.text, fontSize = 14.sp)
            else l.forEach { s ->
                val on = pick === s
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { pick = s }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.label, color = if (on) Gt.accent else Gt.text, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    Text("%,d".format(s.trails), color = if (on) Gt.accent else Gt.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                }
            }
            if (busy) Text("Deleting…", color = Gt.accent, fontSize = 13.sp)
        }
    }
    }   // TRAILSRCDIALOG-2026-11-04: end of the Dialog
    val p = pick
    if (p != null && !busy) AlertDialog(
        onDismissRequest = { pick = null },
        title = { GtDialogTitle("Delete ${"%,d".format(p.trails)} trails?", "map.trailsrc", { pick = null }) },
        text = { Text("Source: ${p.label}\n\nA trail another source also has is kept. This cannot be undone.", color = Gt.text, fontSize = 13.sp) },
        confirmButton = {
            Text("DELETE", color = Gt.bad, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { GtTrailCleanup.deleteSource(p) }
                    android.widget.Toast.makeText(ctx, r, android.widget.Toast.LENGTH_LONG).show()
                    list = withContext(Dispatchers.IO) { GtTrailCleanup.sources() }
                    pick = null; busy = false
                }
            }.padding(12.dp))
        },
        dismissButton = { Text("CANCEL", color = Gt.dim, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable { pick = null }.padding(12.dp)) },
        containerColor = Gt.panel
    )
}
