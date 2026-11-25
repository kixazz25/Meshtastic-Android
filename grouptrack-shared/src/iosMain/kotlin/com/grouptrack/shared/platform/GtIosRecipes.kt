package com.grouptrack.shared.platform

import com.geeksville.mesh.grouptrack.GtDlDefaults
import com.geeksville.mesh.grouptrack.GtRecipeMath
import com.geeksville.mesh.grouptrack.GtRecipeRow
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.queue.GtVectorQueue
import com.grouptrack.shared.text.GtFmt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * MAPRECIPESHARE-2026-11-25: the iPad's HOST for the shared Saved map recipes screen -- what Android's GtRecipes
 * (app/.../grouptrack/MapRecipes.kt) is there: the folders, the estimate, RESTORE and SEND. The rules it calls are shared:
 * boxesOf / squaresInBox / queueSquares (GtVectorQueue, Cert16c C/D), GtRecipeMath, GtDlDefaults, the emailAll service.
 * ⚠ NO DOWNLOADS FOLDER on iOS: RECEIVED is recipes/received only. A recipe sent by email arrives there through the
 * file-arrival build (its own item), as Android's ConvoyFileReceiver writes it there.
 */
object IosRecipes {
    private val log = gtLogger("MapRecipes")   // Android's tag, so the logs read the same
    private val fs = FileSystem.SYSTEM

    fun recipesDir(): Path = IosImportPaths().dir("recipes")
    fun receivedDir(): Path = recipesDir() / "received"

    private fun dayOf(p: Path): String = p.name.removeSuffix(".gtmaps").removePrefix("grouptrack_maps_")
    private fun createdDay(p: Path): String = try {
        Json.parseToJsonElement(fs.read(p) { readUtf8() }).jsonObject["created"]?.jsonPrimitive?.content?.take(10) ?: ""
    } catch (e: Exception) { "" }
    private fun row(q: GtVectorQueue, p: Path, label: String): GtRecipeRow = try {
        GtRecipeRow(p.toString(), label, "${q.boxesOf(p).size} areas   ${(fs.metadata(p).size ?: 0L) / 1024} KB")
    } catch (e: Exception) { GtRecipeRow(p.toString(), label, "unreadable") }

    /** SAVED ON THIS PHONE (newest first) and RECEIVED (recipes/received, newest first) -- Android's rows(). */
    fun rows(q: GtVectorQueue): Pair<List<GtRecipeRow>, List<GtRecipeRow>> {
        val saved = (runCatching { fs.list(recipesDir()) }.getOrNull() ?: emptyList())
            .filter { it.name.startsWith("grouptrack_maps_") && it.name.endsWith(".gtmaps") && fs.metadata(it).isRegularFile }
            .sortedByDescending { it.name }.map { row(q, it, dayOf(it)) }
        val rcv = (runCatching { fs.list(receivedDir()) }.getOrNull() ?: emptyList())
            .filter { it.name.endsWith(".gtmaps") && fs.metadata(it).isRegularFile }
            .sortedByDescending { fs.metadata(it).lastModifiedAtMillis ?: 0L }
            .map { row(q, it, createdDay(it).ifEmpty { dayOf(it) }) }
        return Pair(saved, rcv)
    }

    private fun squaresOf(q: GtVectorQueue, p: Path): Set<Long> {
        val sq = HashSet<Long>()
        for (b in q.boxesOf(p)) q.squaresInBox(b[0], b[1], b[2], b[3], sq)
        return sq
    }

    /** REPLACEDEFAULT-2026-11-03: the line under RESTORE -- Android's words. */
    fun replaceLine(): String = if (GtDlDefaults.replace(IosSettings()))
        "Downloads every map again, fresh (Replace existing tiles: Yes, from your download settings). Nothing is deleted first."
        else "Downloads only what is missing on this phone (Replace existing tiles: No, from your download settings). Nothing is deleted."

    /** What RESTORE will queue -- Android's estimateText. */
    fun estimateText(q: GtVectorQueue, path: String): String = try {
        val sq = squaresOf(q, path.toPath())
        if (sq.isEmpty()) "No map areas in this recipe." else {
            val t = GtRecipeMath.tiles(sq)
            "${sq.size} map squares — up to ${GtFmt.d(t)} tiles (vector and elevation), about ${GtRecipeMath.timeText(t)} on Wi-Fi " +
                (if (GtDlDefaults.replace(IosSettings())) "." else "if none are on this phone.")
        }
    } catch (e: Exception) { "Could not read this recipe: ${e.message}" }

    /** RESTORE AS OF [label] -- Android's restore: the recipe's boxes, vector + elevation, Replace from Download settings. */
    fun restore(q: GtVectorQueue, path: String, label: String): String {
        val sq = try { squaresOf(q, path.toPath()) } catch (e: Exception) { return "Could not read this recipe: ${e.message}" }
        if (sq.isEmpty()) return "No map areas in this recipe."
        val rep = GtDlDefaults.replace(IosSettings())
        val r = q.queueSquares(sq, rep, "restore $label")
        log.i { "MAPRECIPES restore $label (${path.toPath().name}): ${sq.size} squares -> ${r.first} job(s), ~${r.second} tiles" }
        return if (r.first == 0) "Nothing could be queued." else
            "Queued ${r.first} download jobs for the maps as of $label — " + (if (rep) "every map downloads fresh." else "only what is missing downloads.") +
                " Watch them in Map › MAP DOWNLOADS."
    }

    /** SEND -- Android's send through the shared emailAll service: the recipe as "GroupTrack maps <label>.json" (RECIPEJSON-
     *  2026-11-03) with Android's words. The share sheet drops the subject, so it leads the body (as rides.email). */
    fun send(path: String, label: String): String = try {
        val tmp = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "GroupTrack maps $label.json"
        fs.copy(path.toPath(), tmp)
        val play = "https://play.google.com/store/apps/details?id=com.grouptrack.android&hl=en_US"
        val subject = "GroupTrack OG maps as of $label"
        val body = subject + "\n\nThese are my GroupTrack OG maps as of $label.\n\n" +
            "On your Android phone or tablet: tap the attachment and choose GroupTrack OG (\"Always\"). GroupTrack OG opens Saved map recipes " +
            "with these maps picked — tap RESTORE to download them. Wi-Fi recommended.\n\n" +
            "Don't have GroupTrack OG yet? Get it on Google Play:\n$play\n"
        val ok = IosArtifactSink().emailAll(listOf(tmp.toString()), subject, body)
        log.i { "MAPRECIPES send $label (${path.toPath().name}) -- " + (if (ok) "share sheet presented" else "nothing took it") }
        if (ok) "" else "Nothing took the send."
    } catch (e: Exception) { log.e { "MAPRECIPES send failed: ${e.message}" }; "Could not send: ${e.message}" }
}
