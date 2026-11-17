package com.grouptrack.shared.cert

import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.platform.IosClock
import com.grouptrack.shared.platform.IosFiles
import com.grouptrack.shared.platform.IosIds
import com.grouptrack.shared.queue.GtQueueEntry
import com.grouptrack.shared.queue.GtQueuePlatform
import com.grouptrack.shared.queue.GtVectorQueue
import com.grouptrack.shared.spatial.GtRead
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CORRIDORCERT-2026-11-17 -- mode corridor17. ⚠ TEST-ONLY (CODE RULE 3).
 *
 * Every track in the restored spatial database, through BOTH corridor parsers, into the SHARED square
 * and box math, with the queued AREAS compared. 2.7e's parser is [Cert17corridor.oldSegments]; the new
 * one is GtRead.corridorGeometry (CORRIDORREAD-2026-11-17).
 *
 * ⚠ NOTHING IS DOWNLOADED AND NOTHING IS WRITTEN. The GtQueuePlatform below only COLLECTS the entries
 * -- no init, no saveQueue, no worker -- which is the arrangement Cert16c certified.
 */
class GtCertCorridor17MacTest {

    private val dataDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/live_2026-11-08"
    private val fallbackDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/droid1_2026-11-06"

    /** Collects what would have been queued. Cert16c's shape: no init, no saveQueue, no worker. */
    private class Collector : GtQueuePlatform {
        val entries = ArrayList<GtQueueEntry>()
        override fun append(entries: List<GtQueueEntry>) { this.entries.addAll(entries) }
        override fun downloadZoom(): Int = 18
        override fun satLayers(): Int? = null
        override fun recipesDir(): okio.Path = "/tmp/gtcorridor17_recipes".toPath()
    }

    private fun queueFor(c: Collector): GtVectorQueue {
        val files: GtFiles = IosFiles()
        return GtVectorQueue(IosIds, IosClock, files, c)
    }

    @Test
    fun corridorAreas() {
        val fs = FileSystem.SYSTEM
        val dir = when {
            fs.exists("$dataDir/grouptrack_spatial.db".toPath()) -> dataDir
            fs.exists("$fallbackDir/grouptrack_spatial.db".toPath()) -> fallbackDir
            else -> { println("CORRIDOR17 mac: no spatial db at $dataDir or $fallbackDir -- skipped"); return }
        }
        println("CORRIDOR17 mac: start, data = $dir")
        val conn = gtOpenDatabase("$dir/grouptrack_spatial.db", true)
        val read = GtRead(conn, null)

        // every track row: the hash and the stored geometry
        val rows = ArrayList<Pair<String, String>>()
        run {
            val st = conn.prepare("SELECT geom_hash, geometry FROM tracks WHERE geom_hash IS NOT NULL")
            try { while (st.step()) { val h = st.getText(0); val g = if (st.isNull(1)) "" else st.getText(1); rows.add(Pair(h, g)) } }
            finally { st.close() }
        }
        println("CORRIDOR17 mac: ${rows.size} track row(s)")
        if (rows.isEmpty()) { println("CORRIDOR17 mac: no tracks -- nothing to compare"); conn.close(); return }

        var same = 0
        var bad = 0
        var shown = 0
        var withGeom = 0
        var totalSquares = 0L
        var totalAreas = 0L

        for ((hash, wkt) in rows) {
            val old = Cert17corridor.oldSegments(wkt)
            val new = read.corridorGeometry(hash)

            // 1. the segments themselves -- the parse
            if (old == new) same++ else {
                bad++
                if (shown++ < 10) println("CORRIDOR17 mac: DIFF segments hash=$hash\n  old=${shape(old)}\n  new=${shape(new)}")
                continue
            }
            if (old == null) continue
            withGeom++

            // 2. the squares -- the shared math, run from each side's segments
            val sqOld = HashSet<Long>(); val sqNew = HashSet<Long>()
            val cOld = Collector(); val cNew = Collector()
            queueFor(cOld).squaresFor(old, sqOld)
            queueFor(cNew).squaresFor(new!!, sqNew)
            val dOld = Cert17corridor.squareDigest(sqOld)
            val dNew = Cert17corridor.squareDigest(sqNew)
            if (dOld == dNew) same++ else {
                bad++
                if (shown++ < 10) println("CORRIDOR17 mac: DIFF squares hash=$hash old=$dOld new=$dNew")
                continue
            }
            totalSquares += sqOld.size.toLong()

            // 3. THE AREAS -- what the download queue would actually cover
            val nm = read.nameForGeomHash(hash) ?: hash.take(8)
            val qOld = queueFor(cOld); val qNew = queueFor(cNew)
            qOld.queueSquaresNamed(sqOld, sqOld.associateWith { setOf(nm) }, false, "corridor $nm")
            qNew.queueSquaresNamed(sqNew, sqNew.associateWith { setOf(nm) }, false, "corridor $nm")
            val aOld = Cert17corridor.areaDigest(cOld.entries.map {
                Cert17corridor.areaOf(it.north, it.south, it.east, it.west, it.refreshSlot, it.label) })
            val aNew = Cert17corridor.areaDigest(cNew.entries.map {
                Cert17corridor.areaOf(it.north, it.south, it.east, it.west, it.refreshSlot, it.label) })
            if (aOld == aNew) same++ else {
                bad++
                if (shown++ < 10) println("CORRIDOR17 mac: DIFF areas hash=$hash\n  old=$aOld\n  new=$aNew")
                continue
            }
            totalAreas += cOld.entries.size.toLong()
        }

        // the routes fallthrough, on hashes NOT in tracks (where the ?: actually fires)
        run {
            val trackHashes = rows.map { it.first }.toHashSet()
            val st = conn.prepare("SELECT geom_hash, geometry FROM routes WHERE geom_hash IS NOT NULL")
            var checked = 0
            try {
                while (st.step() && checked < 200) {
                    val h = st.getText(0)
                    if (trackHashes.contains(h)) continue      // corridorGeometry would answer from tracks
                    val g = if (st.isNull(1)) "" else st.getText(1)
                    checked++
                    if (Cert17corridor.oldSegments(g) == read.corridorGeometry(h)) same++ else {
                        bad++
                        if (shown++ < 10) println("CORRIDOR17 mac: DIFF routes-fallthrough hash=$h")
                    }
                }
            } finally { st.close() }
            println("CORRIDOR17 mac: FACT routes-only hashes checked = $checked")
        }

        conn.close()
        println("CORRIDOR17 mac: FACT tracks with geometry = $withGeom of ${rows.size}")
        println("CORRIDOR17 mac: FACT total squares = $totalSquares, total queued areas = $totalAreas")
        println("CORRIDOR17 mac: $same SAME, $bad DIFF -- done")
        assertEquals(0, bad, "the ported corridor parser disagrees with 2.7e's on real track geometry")
        assertTrue(withGeom > 0, "no track produced geometry -- the comparison proved nothing")
    }

    private fun shape(s: List<List<Pair<Double, Double>>>?): String =
        if (s == null) "null" else "${s.size} seg / ${s.sumOf { it.size }} pts" +
            (s.firstOrNull()?.firstOrNull()?.let { " first=(${it.first},${it.second})" } ?: "")
}
