package com.grouptrack.shared.cert

import com.grouptrack.shared.platform.IosFlight
import com.grouptrack.shared.platform.IosImportPlatform
import com.grouptrack.shared.platform.IosOsmPlatform
import com.grouptrack.shared.platform.IosQueuePlatform
import com.grouptrack.shared.platform.IosRideFiles
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IOSPLAT17-2026-11-17 ⚠ TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released).
 *
 * ⭐ WHY THIS EXISTS. Measured 11-17: grouptrack-shared has 40 iosTest cert legs and 25 declared
 * classes in iosMain/platform, and NINETEEN of those classes are referenced by no cert and no test.
 * The 40 legs certify SHARED code through test doubles -- Cert16c supplies its own GtQueuePlatform
 * that collects entries and answers 18 / null / a fixed folder -- so the shared arithmetic is well
 * covered and the real iOS platform implementations have never been EXECUTED by anything. The
 * standing precedent is that zero of 21 production iOS implementations was ever exercised by a Mac
 * test. Every class written on 11-16 and 11-17 was compile-verified only. This leg is the first that
 * runs them.
 *
 * WHAT IS COMPARED vs WHAT IS PRINTED -- the GtCertPathsMacTest rule, applied deliberately:
 *  - COMPARED: any answer that MUST equal Android's (downloadZoom 18, satLayers null, the RideFiles
 *    return conventions, Flight's case table). A difference here is a defect.
 *  - PRINTED as a FACT, never compared: anything platform-specific by nature (free space, a container
 *    absolute). "Comparing those would fail on a CORRECT implementation."
 *
 * ⛔ AND THE THROW-ASSERTIONS ARE THE POINT, not an afterthought. append and enqueueCorridor must
 * throw AND carry their dated markers. The realistic regression is not that they break -- it is that
 * someone later "fixes" a throw into a silent no-op, after which a rider saves an area, is told it
 * worked, and receives no tiles. Asserting the throw turns that into a failing build.
 */
class GtCertIosPlatformMacTest {

    private val outDir = "/Users/kixazz25/AndroidStudioProjects/GroupTrackSpike/_data/cert"
    private val fs = FileSystem.SYSTEM

    private var same = 0
    private var bad = 0
    private fun eq(key: String, expect: Any?, got: Any?) {
        if (expect == got) same++ else { bad++; println("IOSPLAT17 mac: DIFF $key\n  expect=$expect\n  got   =$got") }
    }
    private fun fact(key: String, v: Any?) = println("IOSPLAT17 mac: FACT $key = $v")

    // ---- IosFlight ------------------------------------------------------------------------------
    // The same 12 cases the pre-patch simulation ran (gt_flightsim_2026-11-17_v1.py), now against the
    // real object. 2.7e's answers: AtomicBoolean compareAndSet(false,true) / set(false).
    // !! IosFlight IS AN `object`, SO ITS STATE SURVIVES BETWEEN CASES -- that is the process-wide
    // guarantee the interface requires, and it means every case must RESET first. A per-instance
    // class would need no reset, which is exactly the bug this design avoids.
    private fun resetFlight() { IosFlight.endSync(); IosFlight.endBackfill() }

    private fun flightCase(label: String, calls: List<String>, expect: List<Boolean?>) {
        resetFlight()
        val got = calls.map { c ->
            when (c) {
                "beginSync" -> IosFlight.beginSync()
                "endSync" -> { IosFlight.endSync(); null }
                "beginBackfill" -> IosFlight.beginBackfill()
                "endBackfill" -> { IosFlight.endBackfill(); null }
                else -> throw IllegalArgumentException(c)
            }
        }
        eq("flight|$label", expect, got)
    }

    @Test
    fun iosplat17() {
        println("IOSPLAT17 mac: start")

        // ---------------- IosFlight: 12 cases, OLD/NEW Android answers ----------------
        flightCase("1 first sync wins", listOf("beginSync"), listOf(true))
        flightCase("2 second concurrent sync REFUSED", listOf("beginSync", "beginSync"), listOf(true, false))
        flightCase("3 third also refused", listOf("beginSync", "beginSync", "beginSync"), listOf(true, false, false))
        flightCase("4 released then re-acquired", listOf("beginSync", "endSync", "beginSync"), listOf(true, null, true))
        flightCase("5 backfill independent of sync", listOf("beginSync", "beginBackfill"), listOf(true, true))
        flightCase("6 second backfill refused", listOf("beginBackfill", "beginBackfill"), listOf(true, false))
        flightCase("7 both held, released, re-held",
            listOf("beginSync", "beginBackfill", "endSync", "endBackfill", "beginSync", "beginBackfill"),
            listOf(true, true, null, null, true, true))
        flightCase("8 end without begin clears anyway", listOf("endSync", "beginSync"), listOf(null, true))
        flightCase("9 double end harmless", listOf("beginSync", "endSync", "endSync", "beginSync"), listOf(true, null, null, true))
        flightCase("10 endSync does NOT release backfill", listOf("beginBackfill", "endSync", "beginBackfill"), listOf(true, null, false))
        flightCase("11 sync runs a backfill (real nesting)",
            listOf("beginSync", "beginBackfill", "endBackfill", "endSync", "beginSync"), listOf(true, true, null, null, true))
        flightCase("12 interleaved, worst order",
            listOf("beginSync", "beginBackfill", "beginSync", "beginBackfill", "endSync", "beginSync"),
            listOf(true, true, false, false, null, true))
        resetFlight()

        // ---------------- IosQueuePlatform ----------------
        val q = IosQueuePlatform()
        eq("queue|downloadZoom", 18, q.downloadZoom())            // ConvoyConfig.DOWNLOAD_ZOOM (never assigned; see the class note)
        eq("queue|satLayers", null, q.satLayers())                // interface: "null when there is no SAT source"
        eq("queue|recipesDir.name", "recipes", q.recipesDir().name)
        fact("queue|recipesDir", q.recipesDir())                  // the container absolute: platform-specific, NOT compared
        var threw = ""
        try { q.append(emptyList()); threw = "NO THROW" } catch (e: Exception) { threw = e.message ?: "" }
        eq("queue|append throws", true, threw != "NO THROW")
        eq("queue|append marker", true, threw.contains("IOSQUEUE-2026-11-17"))

        // ---------------- IosImportPlatform.enqueueCorridor ----------------
        val ip = IosImportPlatform()
        var cthrew = ""
        try { ip.enqueueCorridor("deadbeef", "VEC", false); cthrew = "NO THROW" } catch (e: Exception) { cthrew = e.message ?: "" }
        eq("import|enqueueCorridor throws", true, cthrew != "NO THROW")
        eq("import|enqueueCorridor marker", true, cthrew.contains("CERTPATHS-2026-11-16"))

        // ---------------- IosRideFiles: java.io.File's RETURN CONVENTIONS ----------------
        val rf = IosRideFiles()
        val work = "$outDir/iosplat17_work"
        try { fs.deleteRecursively(work.toPath()) } catch (e: Exception) { }
        eq("ride|mkdirs creates -> true", true, rf.mkdirs(work))
        eq("ride|mkdirs when EXISTS -> FALSE", false, rf.mkdirs(work))   // File.mkdirs(): true only if it created
        eq("ride|exists dir", true, rf.exists(work))
        val f1 = rf.child(work, "a.json")
        eq("ride|child", "$work/a.json", f1)
        eq("ride|name", "a.json", rf.name(f1))
        eq("ride|sibling", "$work/b.json", rf.sibling(f1, "b.json"))
        eq("ride|isFile before write -> false", false, rf.isFile(f1))
        eq("ride|length missing -> 0", 0L, rf.length(f1))
        eq("ride|delete missing -> false", false, rf.delete(f1))
        rf.writeText(f1, "{\"k\":1}")
        eq("ride|readText round trip", "{\"k\":1}", rf.readText(f1))
        eq("ride|isFile after write", true, rf.isFile(f1))
        eq("ride|length after write", 7L, rf.length(f1))
        eq("ride|list(dir) names", listOf("a.json"), rf.list(work))
        eq("ride|list(FILE) -> NULL", null, rf.list(f1))                  // CODE RULE 1: not a folder
        eq("ride|list(missing) -> NULL", null, rf.list(rf.child(work, "nope")))
        val f2 = rf.child(work, "b.json")
        eq("ride|rename -> true", true, rf.rename(f1, f2))
        eq("ride|rename of MISSING -> FALSE not throw", false, rf.rename(f1, rf.child(work, "c.json")))
        eq("ride|delete existing -> true", true, rf.delete(f2))

        // ---------------- IosOsmPlatform: contracts, not equalities ----------------
        val osm = IosOsmPlatform()
        val space = osm.usableSpace(work.toPath())
        eq("osm|usableSpace >= 0 (fail-closed)", true, space >= 0L)
        fact("osm|usableSpace", space)                                    // a real number here; NOT compared
        var empties = "ok"
        try {
            osm.onOsmRoot(work.toPath()); osm.discardLegacy("utah"); osm.foreground("w", "t")
            osm.sweepDownloadedZips("utah")
        } catch (e: Exception) { empties = "THREW: ${e.message}" }
        eq("osm|empty-on-purpose members do not throw", "ok", empties)

        try { fs.deleteRecursively(work.toPath()) } catch (e: Exception) { }

        println("IOSPLAT17 mac: $same SAME, $bad DIFF -- done")
        assertEquals(0, bad, "an iOS platform class answered differently from Android's documented behaviour")
        assertTrue(same > 30, "too few assertions ran ($same) -- the leg did not execute fully")
    }
}
