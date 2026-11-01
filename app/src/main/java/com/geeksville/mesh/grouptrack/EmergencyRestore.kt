package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.convoy.ConvoyConfig
import com.geeksville.mesh.convoy.ConvoyMapRecipe
import com.geeksville.mesh.convoy.DownloadQueueManager
import com.geeksville.mesh.convoy.ElevationTiles
import com.geeksville.mesh.convoy.GroupTrackStorage
import com.geeksville.mesh.convoy.MBTilesStore
import com.geeksville.mesh.convoy.QueueStatus
import com.geeksville.mesh.convoy.VectorTiles
import java.io.File
import kotlinx.coroutines.launch

/**
 * VECEMERGENCY-2026-10-31 (Fred 20:02): Maps > MAP RECOVERY > "Emergency download -- maps as of the conversion date": a SCREEN (it was a
 * one-tap toast). Downloads the maps of the conversion record (recipes/grouptrack_conversion.gtmaps), vector + elevation, replace off.
 * OPTION: "Delete my vector maps and elevation first" -- the stores VEC and DEM are removed, then everything is downloaded again
 * (a clean rebuild; also how a true download time is measured). Refused while vector / elevation jobs are still in the queue
 * (deleting a store under a running download). Same warning and estimate as the conversion screen. Trails, tracks, routes, waypoints
 * are never touched.
 */
object GtEmergency {
    private const val TAG = "MapConversion"
    private const val TILES_PER_MIN = 3200.0   // VECRATE-2026-10-31: as GtMapConversion -- Droid 1 clean rebuild, Wi-Fi, 53,768 tiles in 16 min 40 s
    fun record() = File(GroupTrackStorage.dir("recipes"), ConvoyMapRecipe.CONVERSION)

    fun squares(): Set<Long> {
        val sq = HashSet<Long>()
        val f = record(); if (!f.exists()) return sq
        for (b in VectorTiles.boxesOf(f)) VectorTiles.squaresInBox(b[0], b[1], b[2], b[3], sq)
        return sq
    }

    /** Exact tiles for the squares, vector z0-14 + elevation z0-12 (as GtMapConversion). */
    fun tiles(sq: Set<Long>): Long {
        fun at(zMax: Int): Long { val n = 4096L; var t = 0L
            for (z in 0..zMax) t += if (z >= 12) sq.size.toLong() * (1L shl (2 * (z - 12)))
                else { val s = 12 - z; sq.map { ((it / n) shr s) * n + ((it % n) shr s) }.toSet().size.toLong() }
            return t }
        return at(VectorTiles.Z_MAX) + at(ElevationTiles.Z_MAX)
    }

    fun timeText(tiles: Long): String {
        val mins = Math.max(1, Math.ceil(tiles / TILES_PER_MIN).toInt())
        return if (mins < 90) "$mins minute${if (mins == 1) "" else "s"}" else "${"%.1f".format(mins / 60.0)} hours"
    }

    fun storeMb(): Long = listOf(VectorTiles.SLOT, ElevationTiles.SLOT).sumOf { t ->
        listOf("", "-wal", "-shm").sumOf { File(ConvoyConfig.TILE_DIR, "$t.mbtiles$it").let { f -> if (f.exists()) f.length() else 0L } }
    } / 1_048_576L

    /** Run it. [clean] = delete VEC + DEM first. One line for the screen. */
    fun run(context: android.content.Context, clean: Boolean): String {
        val sq = squares()
        if (sq.isEmpty()) return "No conversion record on this phone — nothing to download."
        if (clean) {
            DownloadQueueManager.init(context)
            val busy = DownloadQueueManager.queue.value.count { (it.status == QueueStatus.QUEUED || it.status == QueueStatus.DOWNLOADING ||
                it.status == QueueStatus.PAUSED) && (it.refreshSlot == VectorTiles.SLOT || it.refreshSlot == ElevationTiles.SLOT) }
            if (busy > 0) return "$busy vector / elevation download(s) are still in the queue. Let them finish, or cancel them in MAP DOWNLOADS, " +
                "before deleting the maps."
            MBTilesStore.deleteSource(VectorTiles.SLOT); MBTilesStore.deleteSource(ElevationTiles.SLOT)
            android.util.Log.i(TAG, "VECEMERGENCY deleted the vector maps and elevation (VEC, DEM) -- clean rebuild")
        }
        val r = VectorTiles.queueSquares(context.applicationContext, sq, false, "emergency download")
        android.util.Log.i(TAG, "VECEMERGENCY ${if (clean) "clean" else "fill gaps"}: ${r.first} job(s), ~${r.second} tiles queued at ${System.currentTimeMillis()}")
        return if (r.first == 0) "Nothing could be queued." else
            "Queued ${r.first} download jobs" + (if (clean) " — everything downloads again." else " — only what is missing downloads.") +
            " Watch them in Map \u203a MAP DOWNLOADS."
    }
}

@Composable
fun GtEmergencyScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val info = remember { mutableStateOf<Triple<Long, Long, String>?>(null) }   // squares, tiles, record date
    val clean = remember { mutableStateOf(false) }
    val sure = remember { mutableStateOf(false) }
    val msg = remember { mutableStateOf("") }
    val busy = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        info.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val f = GtEmergency.record()
            if (!f.exists()) Triple(0L, 0L, "") else { val sq = GtEmergency.squares()
                Triple(sq.size.toLong(), GtEmergency.tiles(sq), java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(java.util.Date(f.lastModified())) +
                    "|" + VectorTiles.oldTilesOf(f)) }   // VECTILECOUNT-2026-10-31: date|old tiles
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { GtDialogTitle("Emergency download", "map.restartconv", onDone) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val i = info.value
                when {
                    i == null -> Text("Reading the conversion record…", fontSize = 14.sp)
                    i.first == 0L -> Text("No conversion record on this phone — its maps were not converted, or it started on 2.7e. " +
                        "Use Download maps by area or along tracks.", fontSize = 14.sp)
                    else -> {
                        Text("Downloads your maps as they were on the conversion date (${i.third.substringBefore("|")}): the new map and its elevation, through " +
                            "the download queues. Use it if the conversion was interrupted or downloads failed.", fontSize = 14.sp)
                        Text("BEFORE YOU START: make sure this device is connected to POWER and to the INTERNET.", fontSize = 14.sp,
                            fontWeight = FontWeight.Bold, color = Color(0xFFFFB300), modifier = Modifier.padding(top = 8.dp))
                        val old = i.third.substringAfter("|", "0").toLongOrNull() ?: 0L   // VECTILECOUNT-2026-10-31 (Fred 21:01)
                        Text((if (old > 0) "Your old maps: ${"%,d".format(old)} tiles. " else "") +
                            "The new map for the same area: ${"%,d".format(i.second)} tiles \u2014 about ${GtEmergency.timeText(i.second)} on Wi-Fi. " +
                            "Tiles already on the tablet are skipped, so it is usually much less.",
                            fontSize = 14.sp, color = Gt.accent, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.padding(top = 12.dp).clickable { clean.value = !clean.value; sure.value = false },
                            verticalAlignment = Alignment.CenterVertically) {
                            GtCheck(clean.value); Spacer(Modifier.width(10.dp))
                            Text("Delete my vector maps and elevation first, and download everything again (" + GtEmergency.storeMb() +
                                " MB now on the tablet)", fontSize = 14.sp)
                        }
                        if (clean.value) Text("Your maps are gone until the downloads bring them back (online maps meanwhile). Trails, tracks, " +
                            "routes and waypoints are not touched.", fontSize = 13.sp, color = Color(0xFFFF6B6B), modifier = Modifier.padding(top = 6.dp))
                    }
                }
                if (msg.value.isNotEmpty()) Text(msg.value, fontSize = 14.sp, color = Gt.accent, modifier = Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = {
            val i = info.value
            if (i != null && i.first > 0 && !msg.value.startsWith("Queued")) {
                if (clean.value && !sure.value) GtButton("DELETE AND DOWNLOAD AGAIN", { sure.value = true }, emphasis = Color(0xFFC62828))
                else GtButton(if (busy.value) "QUEUING…" else if (clean.value) "YES — DELETE AND START" else "START", {
                    busy.value = true
                    scope.launch {
                        msg.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { GtEmergency.run(ctx, clean.value) }
                        busy.value = false; sure.value = false
                    }
                }, enabled = !busy.value, emphasis = if (clean.value) Color(0xFFC62828) else Color.Unspecified)
            }
        },
        dismissButton = { GtButton("CLOSE", onDone) },
        containerColor = Gt.panel
    )
}
