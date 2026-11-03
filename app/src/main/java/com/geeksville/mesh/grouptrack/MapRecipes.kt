package com.geeksville.mesh.grouptrack

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.geeksville.mesh.convoy.GroupTrackStorage
import com.geeksville.mesh.convoy.VectorTiles
import org.json.JSONObject
import java.io.File

/**
 * MAPRECIPES-2026-11-03 (Fred 10:2x): the map recipes -- restore as of a day, send by email, receive with one click.
 * A recipe (grouptrack_maps_<day>.gtmaps, written once a day, kept 60 days) is WHAT the maps covered: its boxes. RESTORE queues those
 * boxes vector + elevation with replace OFF -- only what is missing downloads, nothing on the phone is deleted (as the emergency
 * download's fill-gaps run). Trails, tracks, routes and waypoints are never touched.
 */
object GtRecipes {
    private const val TAG = "MapRecipes"
    /** A recipe just opened from an email / Downloads: its path, picked when Saved map recipes opens. "" = none (CODE RULE 1). */
    val pick = androidx.compose.runtime.mutableStateOf("")
    /** An AI route recipe just opened from an email / Downloads: its JSON, consumed by the shell. "" = none. */
    val routeRecipe = androidx.compose.runtime.mutableStateOf("")
    /** RESTOREQUEUE-2026-11-03: a restore was queued -- the shell opens the download queue. */
    val openQueue = androidx.compose.runtime.mutableStateOf(false)

    class Row(val file: File, val label: String, val detail: String)

    fun receivedDir(ctx: Context): File = File(GroupTrackStorage.dir("recipes", ctx), "received").also { it.mkdirs() }

    private fun dayOf(f: File): String = f.name.removeSuffix(".gtmaps").removePrefix("grouptrack_maps_")
    private fun row(f: File, label: String): Row = try {
        Row(f, label, "${VectorTiles.boxesOf(f).size} areas   ${f.length() / 1024} KB")
    } catch (e: Exception) { Row(f, label, "unreadable (${f.length()} bytes)") }

    /** SAVED ON THIS PHONE (newest first) and RECEIVED (recipes/received, then grouptrack_maps_*.gtmaps in Downloads). */
    fun rows(ctx: Context): Pair<List<Row>, List<Row>> {
        val saved = (GroupTrackStorage.dir("recipes", ctx).listFiles()?.filter { it.isFile && it.name.startsWith("grouptrack_maps_") && it.name.endsWith(".gtmaps") }
            ?: emptyList()).sortedByDescending { it.name }.map { row(it, dayOf(it)) }
        val rcv = ArrayList<Row>()
        receivedDir(ctx).listFiles()?.filter { it.isFile && it.name.endsWith(".gtmaps") }?.sortedByDescending { it.lastModified() }
            ?.forEach { f -> rcv.add(row(f, createdDay(f).ifEmpty { dayOf(f) })) }
        try {
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).listFiles()
                ?.filter { it.name.startsWith("grouptrack_maps_") && it.name.endsWith(".gtmaps") }?.sortedByDescending { it.name }
                ?.forEach { rcv.add(row(it, dayOf(it))) }
        } catch (e: Exception) { Log.w(TAG, "MAPRECIPES Downloads not readable: ${e.message}") }
        return Pair(saved, rcv)
    }

    /** The day a recipe was written ("created": yyyy-MM-dd...), "" if it does not say. */
    private fun createdDay(f: File): String = try { JSONObject(f.readText()).optString("created").take(10) } catch (e: Exception) { "" }

    private fun squaresOf(f: File): Set<Long> {
        val sq = HashSet<Long>()
        for (b in VectorTiles.boxesOf(f)) VectorTiles.squaresInBox(b[0], b[1], b[2], b[3], sq)
        return sq
    }

    /** REPLACEDEFAULT-2026-11-03: the line under RESTORE -- what the rider's Replace setting will do. */
    fun replaceLine(ctx: Context): String = if (GtDownloadDefaults.initialReplace(ctx))
        "Downloads every map again, fresh (Replace existing tiles: Yes, from your download settings). Nothing is deleted first."
        else "Downloads only what is missing on this phone (Replace existing tiles: No, from your download settings). Nothing is deleted."

    /** What RESTORE will queue, for the screen. */
    fun estimateText(ctx: Context, f: File): String = try {
        val sq = squaresOf(f)
        if (sq.isEmpty()) "No map areas in this recipe." else {
            val t = GtEmergency.tiles(sq)
            "${sq.size} map squares — up to ${"%,d".format(t)} tiles (vector and elevation), about ${GtEmergency.timeText(t)} on Wi-Fi " +
                (if (GtDownloadDefaults.initialReplace(ctx)) "." else "if none are on this phone.")   /* REPLACEDEFAULT-2026-11-03 */
        }
    } catch (e: Exception) { "Could not read this recipe: ${e.message}" }

    /** RESTORE AS OF [label]: the recipe's boxes, vector + elevation, fill gaps. One line for the screen. */
    fun restore(ctx: Context, f: File, label: String): String {
        val sq = try { squaresOf(f) } catch (e: Exception) { return "Could not read this recipe: ${e.message}" }
        if (sq.isEmpty()) return "No map areas in this recipe."
        val rep = GtDownloadDefaults.initialReplace(ctx)   // REPLACEDEFAULT-2026-11-03
        val r = VectorTiles.queueSquares(ctx, sq, rep, "restore $label")
        Log.i(TAG, "MAPRECIPES restore $label (${f.name}): ${sq.size} squares -> ${r.first} job(s), ~${r.second} tiles")
        return if (r.first == 0) "Nothing could be queued." else
            "Queued ${r.first} download jobs for the maps as of $label — " + (if (rep) "every map downloads fresh." else "only what is missing downloads.") +
                " Watch them in Map › MAP DOWNLOADS."
    }

    /** SEND: the share sheet with the recipe attached; the email tells the rider how to open it. "" = the sheet opened. */
    fun send(ctx: Context, f: File, label: String): String = try {
        ctx.cacheDir.listFiles { x -> x.name.startsWith("GroupTrack maps ") }?.forEach { it.delete() }
        val copy = File(ctx.cacheDir, "GroupTrack maps $label.json")   // RECIPEJSON-2026-11-03: Gmail hands a .json on; not a .gtmaps
        f.copyTo(copy, overwrite = true)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", copy)
        val play = "https://play.google.com/store/apps/details?id=com.grouptrack.android&hl=en_US"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "GroupTrack maps as of $label")
            putExtra(Intent.EXTRA_TEXT, "These are my GroupTrack maps as of $label.\n\n" +
                "On your Android phone or tablet: tap the attachment and choose GroupTrack (\"Always\"). GroupTrack opens Saved map recipes " +
                "with these maps picked — tap RESTORE to download them. Wi-Fi recommended.\n\n" +
                "Don't have GroupTrack yet? Get it on Google Play:\n$play\n")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Send the map recipe").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Log.i(TAG, "MAPRECIPES send $label (${f.name}, ${f.length()} bytes)")
        ""
    } catch (e: Exception) { Log.e(TAG, "MAPRECIPES send failed", e); "Could not send: ${e.message}" }

    /** An AI route recipe: the recipe itself (anchorLat + milesHigh), bare or under "recipe" (an ISSUE attachment). */
    private fun routeOf(o: JSONObject): JSONObject? {
        val r = o.optJSONObject("recipe") ?: o
        return if (r.has("anchorLat") && r.has("milesHigh")) r else null
    }

    /**
     * From ConvoyFileReceiver: a JSON file opened from an email / Downloads. A map recipe is kept in recipes/received and opens
     * Saved map recipes with it picked; a route recipe opens the AI build at its summary. Returns the rider's message,
     * "" = not a recipe (the receiver goes on). [displayName]: null = the sending app gave no name (as GpxOpen.displayName).
     */
    fun receive(ctx: Context, displayName: String?, content: String, o: JSONObject): String {
        if (o.optJSONArray("slots") != null) {
            val base = (displayName ?: "grouptrack_maps_received_${System.currentTimeMillis()}").removeSuffix(".json").removeSuffix(".gtmaps")   // RECIPEJSON-2026-11-03
                .replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().ifBlank { "received" }
            var f = File(receivedDir(ctx), "$base.gtmaps"); var k = 2
            while (f.exists() && f.readText() != content) { f = File(receivedDir(ctx), "$base ($k).gtmaps"); k++ }
            f.writeText(content)
            Log.i(TAG, "MAPRECIPES received ${f.name} (${f.length()} bytes) -> Saved map recipes")
            pick.value = f.absolutePath
            GtArea.ready.value = "recipes"
            bringForward(ctx)
            return "Map recipe received — tap RESTORE to download its maps."
        }
        val r = routeOf(o) ?: return ""
        Log.i(TAG, "MAPRECIPES received a route recipe (${displayName ?: "no name"}) -> the AI build")
        routeRecipe.value = r.toString()
        bringForward(ctx)
        return "Route recipe received — the AI build opens at its summary."
    }

    private fun bringForward(ctx: Context) {
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ctx.startActivity(launch)
        }
    }
}
