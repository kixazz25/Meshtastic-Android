package com.geeksville.mesh.grouptrack

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.convoy.ConvoyConfig
import com.geeksville.mesh.convoy.ConvoyMapRecipe
import com.geeksville.mesh.convoy.ElevationTiles
import com.geeksville.mesh.convoy.GroupTrackStorage
import com.geeksville.mesh.convoy.MBTilesStore
import com.geeksville.mesh.convoy.VectorTiles
import java.io.File

/**
 * VECCONVERT-2026-10-31 (2.7e build step 2; design Fred 10-30 15:17-15:55) + VECWELCOME-2026-10-31c (Fred 19:13-19:40):
 * THE CONVERSION TO ONE MAP, and the 2.7e welcome screen that runs it.
 *
 * Every start, the phone decides -- CODE RULE 3: no "done" marker, no version check; this file leaves the code with the next release.
 *   - No old maps (no .mbtiles with tiles in maps/tiles but VEC / DEM) = no recipe = no conversion: a new install. Empty leftovers
 *     (old-kind stores holding no tiles, empty folders) are removed; move on.
 *   - Old maps: the new map cannot draw them, so the conversion is not optional (Fred 19:33). The map is vector from this start.
 *       1 PREPARING -- the recipe from the tiles on the phone, written fresh; a copy kept as recipes/grouptrack_conversion.gtmaps
 *         (the FIRST one kept). No recipe -> nothing converted, nothing deleted, logged.
 *       2 ASK (d, Fred 19:51-19:52: shown only once the recipe is built; warning first; red CANCEL ends GroupTrack) -- the estimate from the recipe (tiles to download, exact count from its z12 squares; time at ~6,000 tiles/min, Droid 2's
 *         measured Wi-Fi rate): "Download of your maps is estimated to take about N hours. Please be prepared for your device to be
 *         running, connected to power and the internet, before proceeding." PROCEED NOW, or LATER -- GroupTrack closes; the next start
 *         asks again (old maps untouched until PROCEED).
 *       3 RUNNING -- every square queued vector + elevation (replace off), THEN the old stores deleted (queue-then-delete); the online
 *         map meanwhile; the queue is the restart. Learn the release while it runs: What's new, Quick Start, the manual, downloads.
 *   - Updaters with no maps see the same screen once (INFO) in place of the release notes (MeshNavFold.docToShow).
 * Untouched: trails, tracks, routes, waypoints, rides. Maps > MAP RECOVERY > "Emergency download" requeues from the conversion record.
 */
object GtMapConversion {
    private const val TAG = "MapConversion"
    /** VECRATE-2026-10-31: Droid 1 clean rebuild, Wi-Fi, every tile fetched: ~53 tiles/s (20:07:47-20:24:27, 53,768 tiles; Droid 2's ~100/s was mostly skipped tiles). */
    private const val TILES_PER_MIN = 3200.0

    /** The welcome screen: "" closed, "info" (an update, nothing to convert), "ask" (only once the recipe is built), "running". */
    val stage = androidx.compose.runtime.mutableStateOf("")
    /** The line for the stage: the estimate (ask) or what was queued (running) or why nothing was (an error). */
    val line = androidx.compose.runtime.mutableStateOf("")
    @Volatile private var pending: List<String> = emptyList()
    /** VECEMERSCREEN-2026-11-01 (Fred 05:51): the Emergency download opens this same screen -- "" = the conversion; "keep" / "clean"
     *  = an emergency run (clean = delete the vector maps and elevation first). Reset when the screen closes. */
    @Volatile var emergency: String = ""

    /** The estimate line for a recipe file -- old vs new tiles and the time (the conversion and the emergency run say the same). */
    private fun estimateLine(f: File): String {
        val sq = squaresOf(f)
        val tiles = tilesFor(sq, VectorTiles.Z_MAX) + tilesFor(sq, ElevationTiles.Z_MAX)
        val mins = Math.max(1, Math.ceil(tiles / TILES_PER_MIN).toInt())
        val time = if (mins < 90) "$mins minute${if (mins == 1) "" else "s"}" else "${"%.1f".format(mins / 60.0)} hours"
        android.util.Log.i(TAG, "VECCONVERT estimate: ${sq.size} z12 squares, $tiles tiles, ~$mins min (${f.name})")
        val old = VectorTiles.oldTilesOf(f)
        return (if (old > 0) "Your old maps: ${"%,d".format(old)} tiles. " else "") +
            "The new map for the same area: ${"%,d".format(tiles)} tiles \u2014 about $time on Wi-Fi."
    }

    /** Emergency download -> this screen at ASK, from the conversion record. */
    fun emergencyAsk(context: Context, clean: Boolean) {
        val f = recipeFile(); if (!f.exists()) return
        emergency = if (clean) "clean" else "keep"
        Thread { try { line.value = estimateLine(f); stage.value = "ask" } catch (e: Exception) { android.util.Log.e(TAG, "VECEMERSCREEN ${e.message}"); emergency = "" } }.start()
    }

    private fun recipeFile() = File(GroupTrackStorage.dir("recipes"), ConvoyMapRecipe.CONVERSION)

    /** The old map stores: every .mbtiles in maps/tiles holding tiles, except the vector map and the elevation. File size, not a
     *  query: this runs on the main thread before the map page loads (an 18 GB store answers a query slowly). */
    fun oldStores(): List<String> = (ConvoyConfig.TILE_DIR.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.endsWith(".mbtiles") && it.length() > 100_000L }   // an empty store (schema only) is not a map
        .map { it.name.removeSuffix(".mbtiles") }
        .filter { it != VectorTiles.SLOT && it != ElevationTiles.SLOT }
        .sorted()

    /** A new install: old-kind stores holding no tiles and empty folders in maps/tiles are removed. Never VEC / DEM. */
    private fun clearEmpties() {
        try {
            for (f in ConvoyConfig.TILE_DIR.listFiles() ?: emptyArray()) {
                val t = f.name.removeSuffix(".mbtiles")
                if (f.isFile && f.name.endsWith(".mbtiles") && f.length() <= 100_000L && t != VectorTiles.SLOT && t != ElevationTiles.SLOT) {
                    MBTilesStore.deleteSource(t); android.util.Log.i(TAG, "VECCONVERT removed empty store ${f.name}")
                } else if (f.isDirectory) f.walkBottomUp().forEach { d ->
                    if (d.isDirectory && (d.listFiles()?.isEmpty() == true) && d.delete()) android.util.Log.i(TAG, "VECCONVERT removed empty folder ${d.path}")
                }
            }
        } catch (e: Exception) { android.util.Log.e(TAG, "VECCONVERT clearing empties: ${e.message}") }
    }

    /** The startup entry point (MainActivity). Best effort: nothing here can stop GroupTrack starting. */
    fun startup(context: Context) {
        val app = context.applicationContext
        val stores = try { oldStores() } catch (e: Exception) { android.util.Log.e(TAG, "VECCONVERT check failed: ${e.message}"); emptyList() }
        if (stores.isEmpty()) {
            android.util.Log.i(TAG, "VECCONVERT no old maps -- new install or already converted")
            clearEmpties(); ConvoyMapRecipe.writeDailyAsync(app); return
        }
        android.util.Log.i(TAG, "VECCONVERT old maps $stores -- the conversion")
        GtMapEngine.useVector(app)   // the new map cannot draw the old ones
        pending = stores
        // VECONE-2026-11-01 (agreed 10-31 20:35): "Checking your maps..." while the recipe is built -- the question only once it exists
        val gb = stores.sumOf { File(ConvoyConfig.TILE_DIR, "$it.mbtiles").length() } / 1_073_741_824.0
        line.value = "${"%.1f".format(gb)} GB of maps found on this tablet. Working out what the new map needs\u2026"
        stage.value = "checking"
        // VECWELCOME-2026-10-31d (Fred 19:52): the screen is shown ONLY once the recipe is built from the tiles on the phone now --
        // no recipe, no screen, nothing changed (logged); the next start tries again.
        Thread {
            try { prepare(app) }
            catch (e: Exception) { android.util.Log.e(TAG, "VECCONVERT prepare failed: ${e.message} -- no screen, nothing changed"); stage.value = "" }   // VECONE
        }.start()
    }

    /** 1 the recipe, 2 the estimate. */
    private fun prepare(app: Context) {
        val daily = ConvoyMapRecipe.writeFresh(app)
        if (daily == null) {
            android.util.Log.e(TAG, "VECCONVERT no recipe -- no screen, nothing converted, nothing deleted")
            stage.value = ""   // VECONE-2026-11-01: the checking screen closes
            return
        }
        val keep = recipeFile()
        if (keep.exists()) android.util.Log.i(TAG, "VECCONVERT conversion recipe kept from ${java.util.Date(keep.lastModified())}")
        else { daily.copyTo(keep); android.util.Log.i(TAG, "VECCONVERT conversion recipe ${keep.name} from ${daily.name}") }
        val sq = squaresOf(keep)
        val tiles = tilesFor(sq, VectorTiles.Z_MAX) + tilesFor(sq, ElevationTiles.Z_MAX)
        val mins = Math.max(1, Math.ceil(tiles / TILES_PER_MIN).toInt())
        val time = if (mins < 90) "$mins minute${if (mins == 1) "" else "s"}" else "${"%.1f".format(mins / 60.0)} hours"
        android.util.Log.i(TAG, "VECCONVERT estimate: ${sq.size} z12 squares, $tiles tiles, ~$mins min")
        val old = VectorTiles.oldTilesOf(keep)   // VECTILECOUNT-2026-10-31 (Fred 21:01)
        line.value = (if (old > 0) "Your old maps: ${"%,d".format(old)} tiles. " else "") +
            "The new map for the same area: ${"%,d".format(tiles)} tiles \u2014 about $time on Wi-Fi."
        stage.value = "ask"
    }

    /** PROCEED NOW: 3 queue, then delete. */
    fun proceed(context: Context) {
        val app = context.applicationContext
        if (emergency.isNotEmpty()) {   // VECEMERSCREEN-2026-11-01: an emergency run -- the Emergency download, from this screen
            val clean = emergency == "clean"
            stage.value = "running"; line.value = "Queuing your maps\u2026"
            Thread { line.value = try { GtEmergency.run(app, clean) } catch (e: Exception) { "Could not start (${e.message})." } }.start()
            return
        }
        stage.value = "running"; line.value = "Queuing your maps…"
        Thread {
            try {
                val r = VectorTiles.queueSquares(app, squaresOf(recipeFile()), false, "conversion")
                if (r.first == 0) {
                    android.util.Log.e(TAG, "VECCONVERT nothing queued -- old maps kept")
                    line.value = "Nothing could be queued, so your old maps were kept. The conversion runs again at the next start."
                    return@Thread
                }
                for (t in pending) MBTilesStore.deleteSource(t)
                android.util.Log.i(TAG, "VECCONVERT done: ${r.first} job(s), ~${r.second} tiles queued; deleted $pending")
                line.value = "Your maps are queued: ${r.first} download jobs."
            } catch (e: Exception) { android.util.Log.e(TAG, "VECCONVERT proceed failed: ${e.message}")
                line.value = "The conversion could not start (${e.message}). The next start runs it again." }
        }.start()
    }

    private fun squaresOf(f: File): Set<Long> {
        val sq = HashSet<Long>()
        for (b in VectorTiles.boxesOf(f)) VectorTiles.squaresInBox(b[0], b[1], b[2], b[3], sq)
        return sq
    }

    /** Exact tile count for [sq] (z12 squares, key x * 4096 + y) at zooms 0..[zMax]: below z12 the distinct parents, from z12 up 4^dz each. */
    private fun tilesFor(sq: Set<Long>, zMax: Int): Long {
        val n = 4096L; var total = 0L
        for (z in 0..zMax) {
            total += if (z >= 12) sq.size.toLong() * (1L shl (2 * (z - 12)))
            else { val s = 12 - z; sq.map { ((it / n) shr s) * n + ((it % n) shr s) }.toSet().size.toLong() }
        }
        return total
    }

    /** Maps > MAP RECOVERY > Emergency download (maps as of the conversion date): one line for the screen. */
    fun restart(context: Context): String {
        val f = recipeFile()
        if (!f.exists()) return "No conversion record on this phone — its maps were not converted (or it started on 2.7e)."
        val r = VectorTiles.queueSquares(context.applicationContext, squaresOf(f), false, "restart conversion")
        return if (r.first == 0) "Nothing to queue from the conversion record."
            else "Queued ${r.first} job(s) — only what is still missing downloads. Watch them in MAP DOWNLOADS."
    }
}

/** VECWELCOME-2026-10-31c: the 2.7e welcome screen (text Fred 19:28 + 19:40). */
@Composable
fun GtConversionPanel(onManual: () -> Unit, onNotes: () -> Unit, onQuickStart: () -> Unit, onQueue: () -> Unit) {
    val st = GtMapConversion.stage.value
    if (st.isEmpty()) return
    val ctx = LocalContext.current
    val close = { GtMapConversion.stage.value = ""; GtMapConversion.emergency = "" }   // VECEMERSCREEN-2026-11-01
    val q = com.geeksville.mesh.convoy.DownloadQueueManager.queue.collectAsState().value
    val conv = q.filter { it.label.endsWith("\u00b7 conversion") || (GtMapConversion.emergency.isNotEmpty() && it.label.endsWith("\u00b7 emergency download")) }   // VECEMERSCREEN
    val done = conv.count { it.status == com.geeksville.mesh.convoy.QueueStatus.COMPLETE }
    fun go(f: () -> Unit) { close(); f() }
    val learning = st == "running" || st == "info"
    val asking = st == "ask"
    if (st == "checking") {   // VECONE-2026-11-01: the plain wait while the recipe is built -- no buttons, nothing to decide yet
        AlertDialog(onDismissRequest = {}, title = { Text("Checking your maps\u2026") },
            text = { Text(GtMapConversion.line.value + "\n\nThis takes a few seconds.", fontSize = 14.sp) },
            confirmButton = {}, containerColor = Gt.panel)
        return
    }
    AlertDialog(
        onDismissRequest = { if (learning) close() },   // the question needs an answer
        title = { GtDialogTitle("GroupTrack OG 2.7e \u2014 one new map", "update27e", { if (learning) close() }) },
        text = {
            Column(modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                // VECWELCOME-2026-10-31d (Fred 19:51): ONE screen for the recipe and the question -- the warning first, the estimate
                // under it (worked out while the screen is open), the red button to end GroupTrack until the rider is ready.
                if (asking) {
                    Text("BEFORE YOU PROCEED: make sure this device is connected to POWER and to the INTERNET. Your maps are rebuilt " +
                        "by downloads that run in the background and can take hours.", fontSize = 15.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = GtScheme.current.value.warn /* CONTRAST2-2026-11-01 */)
                    Text(GtMapConversion.line.value,
                        fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Gt.accent,
                        modifier = Modifier.padding(top = 8.dp))
                }
                Text("GroupTrack OG's maps are moving to new sources, based on copyright law. The new maps are a digital (vector) format: " +
                    "3D with the shape of the ground, a map you can tilt, and a map that turns with you.", fontSize = 14.sp,
                    modifier = Modifier.padding(top = if (asking) 10.dp else 0.dp))
                Text("If you have maps stored on this tablet, they are removed and recreated during this installation. They are requested " +
                    "again and downloaded through the download queues, where you can watch them. If you have no stored maps, the " +
                    "conversion is skipped and GroupTrack OG is ready to use at once.", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                Text("The new map is fully available online as soon as GroupTrack OG opens. Your downloaded maps are rebuilt in the background " +
                    "and become available offline as their downloads complete, one queued job at a time.",
                    fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                Text("Your tracks, trails, routes and waypoints are kept and are not affected.", fontSize = 14.sp, color = Gt.accent,
                    modifier = Modifier.padding(top = 8.dp))
                if (asking) Column(modifier = Modifier.padding(top = 12.dp)) {
                    GtButton("PROCEED NOW", { GtMapConversion.proceed(ctx) })
                    if (GtMapConversion.emergency.isNotEmpty()) GtButton("CANCEL", close, emphasis = GtScheme.current.value.bad /* CONTRAST2-2026-11-01 */)   // VECEMERSCREEN: closes only
                    else GtButton("CANCEL \u2014 END GROUPTRACK UNTIL I AM READY", {
                        android.util.Log.i("MapConversion", "VECCONVERT cancelled -- GroupTrack ends; old maps untouched; asks again next start")
                        (ctx as? android.app.Activity)?.finishAffinity() }, emphasis = GtScheme.current.value.bad /* CONTRAST2-2026-11-01 */)
                    if (GtMapConversion.emergency.isEmpty()) Text("Cancel ends GroupTrack OG and changes nothing. It asks again the next time GroupTrack OG starts.", fontSize = 12.sp,
                        color = Gt.dim, modifier = Modifier.padding(top = 4.dp))
                }
                if (st == "running") {
                    Text(GtMapConversion.line.value, fontSize = 14.sp, color = Gt.accent, modifier = Modifier.padding(top = 10.dp))
                    if (conv.isNotEmpty()) Text("Done so far: $done of ${conv.size} jobs" +
                        (if (done == conv.size) " \u2014 your maps are rebuilt." else ""), fontSize = 13.sp, color = Gt.accent, modifier = Modifier.padding(top = 4.dp))
                }
                if (learning) {
                    Text("While these downloads repopulate your maps, you are encouraged to read the new release notes, the Quick Start " +
                        "instructions and the new user manual to familiarize yourself with the application. Use the online maps and begin " +
                        "to explore the new navigation and the new map while your conversion runs. You may notice slower than normal " +
                        "internet map loading while the downloads are running.", fontSize = 13.sp, color = Gt.dim, modifier = Modifier.padding(top = 10.dp))
                    Text("The manual opens with every section folded: tap a heading to open it. Each section starts with What is it?, then " +
                        "How do I use it?. The ? on any screen opens the manual at that screen's section.", fontSize = 13.sp, color = Gt.dim,
                        modifier = Modifier.padding(top = 6.dp))
                    Column(modifier = Modifier.padding(top = 10.dp)) {
                        GtButton("WHAT'S NEW", { go(onNotes) })
                        GtButton("QUICK START", { go(onQuickStart) })
                        GtButton("OPEN THE MANUAL", { go(onManual) })
                        if (st == "running") GtButton("SEE DOWNLOADS", { go(onQueue) })
                    }
                }
            }
        },
        confirmButton = { if (learning) GtButton("GO TO THE MAP", close) },
        containerColor = Gt.panel
    )
}
