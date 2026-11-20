/*
 * NOTESMOVE-2026-11-19 (2.8b): the notes builders, on kotlinx instead of org.json.
 *
 * Android's notesFromDraft / notesFromRouteId / routeHasDescription (ConvoyNotesPanel.kt:111, 171,
 * 186) read the narrative with org.json, which does not exist on iOS. The PAYLOAD is the same text
 * either way -- it is stored verbatim in route_notes.payload (NARRBTN-2026-08-23Y) -- and shared
 * GtRead already parses it (readRouteNotesPayload :194, notesObject :199). So this is the same
 * logic over the same bytes with a different parser, key for key.
 *
 * TWO COPIES EXIST while Android keeps its org.json versions. They are identical by construction
 * and this one is transcribed from the app file: every key, every order, every heading. Android's
 * retire when the app side switches over -- NOT today, because Droid 1 carries 2.8a into the ride.
 */
package com.geeksville.mesh.convoy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private fun JsonObject.obj(k: String): JsonObject? =
    try { this[k]?.jsonObject } catch (e: Exception) { null }
private fun JsonObject.str(k: String): String =
    try { (this[k] as? JsonPrimitive)?.content ?: "" } catch (e: Exception) { "" }
private fun JsonObject.dbl(k: String): Double? =
    try { (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull() } catch (e: Exception) { null }
private fun JsonObject.list(k: String): List<String> =
    try { this[k]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content }?.filter { it.isNotBlank() } ?: emptyList() }
    catch (e: Exception) { emptyList() }

/** one decimal place without java.util.Formatter -- Kotlin/Native has no String.format */
private fun fmt1(x: Double): String {
    val neg = x < 0
    val scaled = kotlin.math.round(kotlin.math.abs(x) * 10.0).toLong()
    return (if (neg) "-" else "") + (scaled / 10).toString() + "." + (scaled % 10).toString()
}
private fun fmt0(x: Double): String =
    (if (x < 0) "-" else "") + kotlin.math.round(kotlin.math.abs(x)).toLong().toString()

/**
 * notesFromDraft (ConvoyNotesPanel.kt:111), section for section and in the same order: headline,
 * the STATS block, ABOUT THIS ROUTE, WHAT YOU WILL SEE (folded), IN ORDER (folded), GROUND COVERED,
 * BEFORE YOU GO (warn, never folded -- NOTESTWISTY-2026-09-03: the warning stays open, always).
 */
fun gtNotesFromNarrative(notes: JsonObject?): List<NoteSection> {
    if (notes == null) return emptyList()
    val out = ArrayList<NoteSection>()
    val nar = notes.obj("narrative")
    val sum = notes.obj("summary")

    nar?.str("headline")?.takeIf { it.isNotBlank() }?.let { out.add(NoteSection(lines = listOf(it))) }

    if (sum != null) {
        val p = ArrayList<Pair<String, String>>()
        sum.dbl("total_miles")?.takeIf { it >= 0 }?.let { p.add("Distance" to (fmt1(it) + " miles")) }
        try {
            val h = sum["est_hours"]?.jsonArray
            if (h != null && h.size >= 2) {
                val a = (h[0] as? JsonPrimitive)?.content?.toDoubleOrNull()
                val b = (h[1] as? JsonPrimitive)?.content?.toDoubleOrNull()
                if (a != null && b != null) p.add("Riding time" to (fmt1(a) + " – " + fmt1(b) + " hours"))
            }
        } catch (e: Exception) { }
        sum.dbl("features")?.takeIf { it >= 0 }?.let { f ->
            val of = sum.dbl("of_available")?.toInt() ?: 0
            p.add("Features" to if (of > 0) (f.toInt().toString() + " of " + of + " in this area") else f.toInt().toString())
        }
        sum.dbl("retrace_pct")?.takeIf { it >= 0 }?.let { p.add("Doubles back" to (fmt0(it) + "%")) }
        if (p.isNotEmpty()) out.add(NoteSection(pairs = p, style = NoteStyle.STATS))
    }

    fun arr(key: String, heading: String, style: NoteStyle = NoteStyle.PLAIN, collapsed: Boolean = false) {
        val l = nar?.list(key) ?: return
        if (l.isNotEmpty()) out.add(NoteSection(heading, l, style = style, collapsed = collapsed))
    }
    // TRACKDESC-2026-09-26: a converted track's rider-written text, shown OPEN.
    nar?.str("description")?.takeIf { it.isNotBlank() }?.let { out.add(NoteSection("ABOUT THIS ROUTE", listOf(it))) }
    arr("what_you_will_see", "WHAT YOU WILL SEE", collapsed = true)
    arr("stops_in_order", "IN ORDER", collapsed = true)
    nar?.str("ground_covered")?.takeIf { it.isNotBlank() }?.let { out.add(NoteSection("GROUND COVERED", listOf(it))) }
    arr("before_you_go", "BEFORE YOU GO", NoteStyle.WARN)
    return out
}

/**
 * routeHasDescription (ConvoyNotesPanel.kt:186): true when there is something to READ -- a
 * headline, the route's own description, ground covered, or one of the three lists. A row carrying
 * only the summary NUMBERS is false, because the detail card already shows those and the tap should
 * open the card rather than a panel of stats (ROUTEDESC-2026-10-23).
 */
fun gtNarrativeHasDescription(notes: JsonObject?): Boolean {
    val nar = notes?.obj("narrative") ?: return false
    for (k in listOf("headline", "description", "ground_covered")) if (nar.str(k).isNotBlank()) return true
    for (k in listOf("what_you_will_see", "stops_in_order", "before_you_go")) if (nar.list(k).isNotEmpty()) return true
    return false
}
