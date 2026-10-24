package com.geeksville.mesh.grouptrack

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.convoy.HomeStateImportController
import com.geeksville.mesh.convoy.SpatialDbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * AREASKIP-2026-10-24 (Fred 12:39): Map > IMPORT MAP FEATURES > Display trail, waypoint counts by state.
 * ON THIS TABLET NOW: live counts. BY STATE: every completed import JSON -- whole state or area, its date, the trails each source
 * added and the points added. Trails and points carry no state on the tablet, so the import records are where "by state" lives.
 * A trail on a state line is in the import that brought it first (the hash dedup); later imports count it as a duplicate.
 */
object GtStateCounts {
    /** [points] -1 = not recorded (imports before 10-24). */
    class Source(val name: String, val trails: Int, val points: Int)
    class Import(val whole: Boolean, val date: String, val states: List<String>, val sources: List<Source>)
    class StateRow(val name: String, val imports: List<Import>)
    /** -1 = could not be read. */
    class Totals(val trails: Int, val privateTrails: Int, val waypoints: Int, val points: Int)

    fun totals(): Totals {
        val s = SpatialDbManager.getSpatialDb()
        fun q(sql: String): Int = if (s == null) -1 else
            runCatching { s.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } }.getOrDefault(-1)
        return Totals(q("SELECT COUNT(*) FROM trails"), q("SELECT COUNT(*) FROM trails WHERE land_status='PRIVATE'"),
            q("SELECT COUNT(*) FROM waypoints"), q("SELECT COUNT(*) FROM reference_points"))
    }

    private fun names(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    fun byState(ctx: Context): List<StateRow> {
        val map = LinkedHashMap<String, MutableList<Import>>()
        for (m in HomeStateImportController.importRecords(ctx).sortedByDescending { it.optString("started_at", "") }) {
            if (m.optString("process_state") != "completed") continue
            val area = m.optJSONObject("area") ?: continue
            val whole = area.optString("type") == "state"
            val srcs = m.optJSONArray("sources") ?: continue
            val osm = LinkedHashMap<String, Source>()   // state name -> its Open Source Maps row
            val shared = ArrayList<Source>()             // catalog sources: the whole box
            for (i in 0 until srcs.length()) {
                val o = srcs.optJSONObject(i) ?: continue
                if (o.optString("status") != "completed" || o.optString("id").startsWith("_")) continue   // stages: _classify, _ridertrails
                val adds = o.optInt("adds", -1).let { if (it >= 0) it else o.optInt("imported", 0) }
                val src = Source(o.optString("name"), adds, o.optInt("points", -1))
                if (o.has("slug")) osm[o.optString("name").removeSuffix(" Open Source Maps")] = src else shared.add(src)
            }
            val states = if (whole) listOf(area.optString("state")) else osm.keys.toList().ifEmpty { names(area.optJSONArray("states")) }
            val date = HomeStateImportController.manifestDate(m)
            for (n in states) map.getOrPut(n) { ArrayList() }.add(Import(whole, date, states, listOfNotNull(osm[n]) + shared))
        }
        return map.entries.sortedBy { it.key }.map { StateRow(it.key, it.value) }
    }

    fun n(v: Int): String = if (v < 0) "\u2014" else "%,d".format(v)
}

@Composable
fun GtStateCountsScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var loaded by remember { mutableStateOf(false) }
    var totals by remember { mutableStateOf(GtStateCounts.Totals(-1, -1, -1, -1)) }
    var rows by remember { mutableStateOf(emptyList<GtStateCounts.StateRow>()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { totals = GtStateCounts.totals(); rows = GtStateCounts.byState(ctx) }
        loaded = true
    }
    GtScreenFrame("Display trail, waypoint counts by state", "map.imported", onClose) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!loaded) { Text("Reading\u2026", color = Gt.text, fontSize = 14.sp); return@Column }
            Text("ON THIS TABLET NOW", color = Gt.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Trails: " + GtStateCounts.n(totals.trails) + "  (" + GtStateCounts.n(totals.privateTrails) + " on private land)", color = Gt.text, fontSize = 14.sp)
            Text("Your waypoints: " + GtStateCounts.n(totals.waypoints), color = Gt.text, fontSize = 14.sp)
            Text("Map points (towns, peaks, places): " + GtStateCounts.n(totals.points), color = Gt.text, fontSize = 14.sp)
            Text("BY STATE \u2014 what each import added", color = Gt.text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp))
            Text("A trail on a state line is counted once, in the import that brought it first.", color = Gt.text, fontSize = 12.sp)
            if (rows.isEmpty()) Text("No completed imports on this tablet yet.", color = Gt.text, fontSize = 14.sp)
            rows.forEach { r ->
                Text(r.name, color = Gt.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                r.imports.forEach { im ->
                    val trails = im.sources.sumOf { maxOf(it.trails, 0) }
                    val pts = im.sources.filter { it.points >= 0 }
                    Text((if (im.whole) "Whole state" else "Area" + (if (im.states.size > 1) " (" + im.states.joinToString(", ") + ")" else "")) +
                        "  \u00b7  " + im.date + "  \u00b7  " + GtStateCounts.n(trails) + " trails" +
                        (if (pts.isNotEmpty()) "  \u00b7  " + GtStateCounts.n(pts.sumOf { it.points }) + " points" else ""),
                        color = Gt.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    im.sources.forEach { s ->
                        Text("    " + s.name + ": " + GtStateCounts.n(s.trails) + " trails" +
                            (if (s.points >= 0) ", " + GtStateCounts.n(s.points) + " points" else ""), color = Gt.text, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
