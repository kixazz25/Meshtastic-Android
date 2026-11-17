package com.grouptrack.shared.cert

import com.grouptrack.shared.platform.IosFlight
import com.grouptrack.shared.platform.IosImportPlatform
import com.grouptrack.shared.platform.IosOsmPlatform
import com.grouptrack.shared.platform.IosQueuePlatform
import com.grouptrack.shared.platform.IosDownloadQueue
import com.grouptrack.shared.platform.IosRideFiles
import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtIds
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.text.GtLocalTime
import com.grouptrack.shared.queue.GtQueueEntry
import com.grouptrack.shared.queue.GtQueueStatus
import okio.Path
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.random.Random
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

    // ---- IosDownloadQueue (IOSPLATQ-2026-11-17) --------------------------------------------------
    // Fred 11-17: "build and certify on both android (state trails) and apple for download queues.
    // apple tested in harness." This is the Apple leg. The same cases the pre-patch simulation ran
    // (gt_dlqsim_2026-11-17_v2.py), now against the real class, on the real okio FileSystem.
    //
    // ⭐ WHY A JOB-LEVEL LEG AND NOT MORE MEMBER ASSERTIONS. These platform members are pseudo-services
    // that walk right up to being independent system functions, so what has to match Android is the
    // JOB: execution, results, and what the screen would render from the state left behind. The
    // simulation proved the difference is not academic -- every member answered correctly while the
    // queue stalled at 4 of 14 jobs and left 10 QUEUED forever. A per-member harness passes that build.
    // Cases 8, 16 and 18 are the ones that catch it.

    private class QClock(var t: Long = 1000L) : GtClock {
        override fun nowInstant(): String = "2026-11-17T12:00:00Z"
        override fun localNow(): GtLocalTime = GtLocalTime(2026, 11, 17, 12, 0, 0)
        override fun epochMs(): Long = t
    }

    private class QIds : GtIds {
        private var n = 0
        override fun newId(): String { n++; return "00000000-0000-4000-8000-" + n.toString().padStart(12, '0') }
    }

    /** This case's own root and nothing else -- the same shape as GtCertMapState17MacTest.MacPaths. */
    private class QPaths(private val root: Path) : GtImportPaths {
        override fun groupTrackRoot(): Path = root
        override fun tracksDir(): Path = root / "my_tracks"
        override fun dir(name: String): Path = root / name
        override fun cacheDir(): Path = root / "cache"
        override fun filesDir(): Path = root / "files"
    }

    private fun qe(
        id: String, pri: Int = 0, st: GtQueueStatus = GtQueueStatus.QUEUED, created: Long = 1000L,
        started: Long = 0L, dl: Int = 0, failed: Int = 0, tps: Double = 0.0, retry: Int = 0,
        completed: Long = 0L,
    ) = GtQueueEntry(
        id = id, status = st, downloadedTiles = dl, failedTiles = failed, retryCount = retry,
        createdAt = created, priority = pri, completedAt = completed, startedAt = started, tilesPerSec = tps,
    )

    @Test
    fun iosDownloadQueue() {
        val fs = FileSystem.SYSTEM
        val base = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "gtdlq17_${Random.nextInt(100000, 999999)}"
        var caseNo = 0
        fun root(): Path { caseNo++; val r = base / "c$caseNo"; fs.createDirectories(r / "files"); return r }
        fun q(r: Path, clock: QClock, fetch: (GtQueueEntry, (Int, Int) -> Unit) -> Boolean) =
            IosDownloadQueue(QIds(), clock, QPaths(r), fs, fetch)

        // --- 1. save / load round trip: every field survives (QUEUE-SCHEMA-2026-07-24) ------------
        run {
            val r = root(); val c = QClock()
            val a = q(r, c) { _, _ -> true }
            a.append(listOf(qe("a"), qe("b", 5, GtQueueStatus.COMPLETE, 900L, 950L, 561, 2, 9.5, 0, 1500L)))
            val b = q(r, c) { _, _ -> true }
            b.loadQueue()
            eq("dlq|roundtrip count", 2, b.queue().size)
            eq("dlq|roundtrip order", listOf("a", "b"), b.queue().map { it.id })
            val second = b.queue()[1]
            eq("dlq|roundtrip downloadedTiles", 561, second.downloadedTiles)
            eq("dlq|roundtrip tilesPerSec", 9.5, second.tilesPerSec)
            eq("dlq|roundtrip completedAt", 1500L, second.completedAt)
            eq("dlq|roundtrip status", GtQueueStatus.COMPLETE, second.status)
            eq("dlq|no .tmp left behind", false,
                fs.exists(r / "files" / IosDownloadQueue.QUEUE_DIR / (IosDownloadQueue.QUEUE_FILE + ".tmp")))
        }

        // --- 2. a corrupt file is QUARANTINED, not deleted, and the app still starts --------------
        run {
            val r = root(); val d = r / "files" / IosDownloadQueue.QUEUE_DIR
            fs.createDirectories(d)
            fs.write(d / IosDownloadQueue.QUEUE_FILE) { writeUtf8("{not json at all") }
            val a = q(r, QClock()) { _, _ -> true }
            a.loadQueue()
            eq("dlq|corrupt -> empty queue", 0, a.queue().size)
            eq("dlq|corrupt file moved away", false, fs.exists(d / IosDownloadQueue.QUEUE_FILE))
            eq("dlq|.corrupt KEPT, not deleted", true, fs.exists(d / (IosDownloadQueue.QUEUE_FILE + ".corrupt")))
        }

        // --- 3. initAndResume: DOWNLOADING -> QUEUED and RUN. The tile queue auto-restarts; the ----
        //        state import does not (GtManifestSweep stamps and the rider reruns). Both deliberate.
        run {
            val r = root(); val c = QClock()
            val seed = q(r, c) { _, _ -> true }
            seed.append(listOf(qe("x", 0, GtQueueStatus.DOWNLOADING, 1000L, 1100L, 30, 0)))
            val seen = mutableListOf<String>()
            val a = q(r, c) { e, _ -> seen.add(e.id); true }
            a.initAndResume()
            eq("dlq|interrupted entry relaunched", listOf("x"), seen)
            eq("dlq|ends COMPLETE", GtQueueStatus.COMPLETE, a.queue()[0].status)
        }

        // --- 3b. nothing interrupted -> the "if changed" guard: no save, no schedule --------------
        run {
            val r = root(); val c = QClock()
            val seed = q(r, c) { _, _ -> true }
            seed.append(listOf(qe("y", 0, GtQueueStatus.COMPLETE, 1000L, 1100L, 10, 0, 1.0, 0, 1200L)))
            val f = r / "files" / IosDownloadQueue.QUEUE_DIR / IosDownloadQueue.QUEUE_FILE
            val before = fs.read(f) { readUtf8() }
            val seen = mutableListOf<String>()
            val a = q(r, c) { e, _ -> seen.add(e.id); true }
            a.initAndResume()
            eq("dlq|no relaunch when nothing interrupted", emptyList<String>(), seen)
            eq("dlq|file untouched (no save)", before, fs.read(f) { readUtf8() })
        }

        // --- 4. updateProgress does NOT save; markComplete DOES. 2.7e's asymmetry, and the reason --
        //        is thrash: progress fires per batch. Proved on the FILE, which is the observable.
        run {
            val r = root(); val c = QClock(1000L)
            val a = q(r, c) { e, p -> repeat(50) { i -> p(i * 10, 0) }; true }
            var sawFile = "never written"
            val a2 = q(r, c) { e, p ->
                repeat(50) { i -> p(i * 10, 0) }
                val f = r / "files" / IosDownloadQueue.QUEUE_DIR / IosDownloadQueue.QUEUE_FILE
                sawFile = if (fs.exists(f)) fs.read(f) { readUtf8() } else "never written"
                true
            }
            a2.initAndResume()
            c.t = 1000L
            a2.append(listOf(qe("p", 0, GtQueueStatus.QUEUED, 1000L)))
            // The file was last written by run()'s DOWNLOADING stamp; 50 progress calls added nothing.
            eq("dlq|progress did not reach the file", true, sawFile.contains("\"downloadedTiles\": 0"))
            eq("dlq|progress WAS held in memory", 490, a2.queue()[0].downloadedTiles)
            val f = r / "files" / IosDownloadQueue.QUEUE_DIR / IosDownloadQueue.QUEUE_FILE
            eq("dlq|completion DID reach the file", true, fs.read(f) { readUtf8() }.contains("\"downloadedTiles\": 490"))
        }

        // --- 5. tilesPerSec / completedAt (THROUGHPUT-2026-08-08C, COMPLETEDAT-STAMP-2026-07-24) ---
        run {
            val r = root(); val c = QClock(1000L)
            val a = q(r, c) { _, p -> p(500, 3); c.t = 6000L; true }
            a.initAndResume()
            a.append(listOf(qe("t", 0, GtQueueStatus.QUEUED, 1000L)))
            val e = a.queue()[0]
            eq("dlq|completedAt stamped", 6000L, e.completedAt)
            eq("dlq|tilesPerSec = 500 / 5.0s", 100.0, e.tilesPerSec)
            eq("dlq|failedTiles carried into completion", 3, e.failedTiles)
            eq("dlq|status COMPLETE", GtQueueStatus.COMPLETE, e.status)
            eq("dlq|SHOWALLCOMPLETE: entry NOT removed", 1, a.queue().size)
        }
        run {   // startedAt == 0 -> fall back to createdAt (an entry predating the field)
            val r = root(); val c = QClock(1000L)
            val a = q(r, c) { _, p -> p(200, 0); c.t = 4000L; true }
            a.initAndResume()
            c.t = 2000L
            a.append(listOf(qe("legacy", 0, GtQueueStatus.QUEUED, 2000L)))
            eq("dlq|startedAt=0 falls back to createdAt", 100.0, a.queue()[0].tilesPerSec)
        }
        run {   // a zero-duration job must not divide by zero
            val r = root(); val c = QClock(5000L)
            val a = q(r, c) { _, p -> p(7, 0); true }
            a.initAndResume()
            a.append(listOf(qe("inst", 0, GtQueueStatus.QUEUED, 5000L)))
            eq("dlq|zero duration -> 0.0, no divide-by-zero", 0.0, a.queue()[0].tilesPerSec)
        }

        // --- 7. markFailed: FAILED and retryCount + 1 --------------------------------------------
        run {
            val r = root(); val c = QClock()
            val a = q(r, c) { _, _ -> false }
            a.initAndResume()
            a.append(listOf(qe("f", 0, GtQueueStatus.QUEUED, 1000L, 0L, 0, 0, 0.0, 2)))
            eq("dlq|status FAILED", GtQueueStatus.FAILED, a.queue()[0].status)
            eq("dlq|retryCount 2 -> 3", 3, a.queue()[0].retryCount)
        }

        // --- 8. THE ONE THAT CAUGHT THE DEFECT. VECONE-2026-11-01 counted 14 jobs -> 22 launches ---
        //        without the lock. A bare latch gave 14 -> 4 and stalled (DRAIN-2026-11-17). Both wrong.
        run {
            val r = root(); val c = QClock()
            val launched = mutableListOf<String>()
            val a = q(r, c) { e, _ -> launched.add(e.id); true }
            a.initAndResume()
            a.maxConcurrent = 4
            a.append((0 until 14).map { qe("j$it", 0, GtQueueStatus.QUEUED, 1000L + it) })
            eq("dlq|14 jobs -> 14 launches", 14, launched.size)
            eq("dlq|no id launched twice", 14, launched.toSet().size)
            eq("dlq|all 14 COMPLETE", 14, a.queue().count { it.status == GtQueueStatus.COMPLETE })
            eq("dlq|nothing left QUEUED", 0, a.queue().count { it.status == GtQueueStatus.QUEUED })
        }

        // --- 9. maxConcurrent clamped 1..4 (QEVAL-2026-08-08I) -----------------------------------
        run {
            val r = root(); val a = q(r, QClock()) { _, _ -> true }
            a.maxConcurrent = 0;  eq("dlq|clamp 0 -> 1", 1, a.maxConcurrent)
            a.maxConcurrent = 99; eq("dlq|clamp 99 -> 4", 4, a.maxConcurrent)
            a.maxConcurrent = 3;  eq("dlq|clamp 3 -> 3", 3, a.maxConcurrent)
        }

        // --- 10. sortedBy is STABLE: a promoted job runs first, its peers keep enqueue order ------
        run {
            val r = root(); val c = QClock()
            val launched = mutableListOf<String>()
            val a = q(r, c) { e, _ -> launched.add(e.id); true }
            a.initAndResume()
            a.maxConcurrent = 4
            a.append(listOf(qe("first", 5), qe("second", 5), qe("urgent", 0), qe("third", 5)))
            eq("dlq|urgent first, peers keep order", listOf("urgent", "first", "second", "third"), launched)
        }

        // --- 11. a held queue starts nothing; resume drains it ------------------------------------
        run {
            val r = root(); val c = QClock()
            val launched = mutableListOf<String>()
            val a = q(r, c) { e, _ -> launched.add(e.id); true }
            a.initAndResume()
            a.holdQueue()
            a.append(listOf(qe("held")))
            eq("dlq|held -> nothing launched", emptyList<String>(), launched)
            eq("dlq|held entry still QUEUED", GtQueueStatus.QUEUED, a.queue()[0].status)
            eq("dlq|isQueuePaused", true, a.isQueuePaused())
            a.resumeQueue()
            eq("dlq|resume launched it", listOf("held"), launched)
        }

        // --- 12. before init the scheduler refuses, but the entry is still PERSISTED --------------
        run {
            val r = root(); val c = QClock()
            val launched = mutableListOf<String>()
            val a = q(r, c) { e, _ -> launched.add(e.id); true }
            a.append(listOf(qe("early")))
            eq("dlq|no launch before init", emptyList<String>(), launched)
            eq("dlq|entry persisted anyway", 1, a.queue().size)
        }

        // --- 13. a fetch that THROWS is a failure, not a crash -----------------------------------
        run {
            val r = root(); val c = QClock()
            val a = q(r, c) { _, _ -> throw RuntimeException("network died") }
            a.initAndResume()
            a.append(listOf(qe("boom")))
            eq("dlq|throwing fetch -> FAILED", GtQueueStatus.FAILED, a.queue()[0].status)
            eq("dlq|throwing fetch -> retryCount 1", 1, a.queue()[0].retryCount)
        }

        // --- 15. only QUEUED is eligible; no other status is ever picked -------------------------
        run {
            val r = root(); val c = QClock()
            val launched = mutableListOf<String>()
            val a = q(r, c) { e, _ -> launched.add(e.id); true }
            a.initAndResume()
            a.maxConcurrent = 4
            a.append(listOf(
                qe("c", 0, GtQueueStatus.COMPLETE, 1L, 2L, 1, 0, 1.0, 0, 3L),
                qe("fl", 0, GtQueueStatus.FAILED), qe("ca", 0, GtQueueStatus.CANCELLED),
                qe("pz", 0, GtQueueStatus.PAUSED), qe("go", 0, GtQueueStatus.QUEUED),
            ))
            eq("dlq|only the QUEUED entry ran", listOf("go"), launched)
        }

        // --- 16. DRAIN with maxConcurrent 1: all 14, one at a time, each exactly once -------------
        run {
            val r = root(); val c = QClock()
            val launched = mutableListOf<String>()
            val a = q(r, c) { e, _ -> launched.add(e.id); true }
            a.initAndResume()
            a.maxConcurrent = 1
            a.append((0 until 14).map { qe("s$it", 0, GtQueueStatus.QUEUED, 1000L + it) })
            eq("dlq|maxConcurrent 1 -> 14 launches", 14, launched.size)
            eq("dlq|in enqueue order", (0 until 14).map { "s$it" }, launched)
            eq("dlq|none twice", 14, launched.toSet().size)
        }

        // --- 18. VECONE proper: a caller arriving MID-PICK must not double-launch -----------------
        run {
            val r = root(); val c = QClock()
            val launched = mutableListOf<String>()
            var self: IosDownloadQueue? = null
            var reentered = false
            val a = q(r, c) { e, _ ->
                launched.add(e.id)
                if (!reentered) { reentered = true; self?.startNextIfAvailable() }   // a second caller
                true
            }
            self = a
            a.initAndResume()
            a.maxConcurrent = 4
            a.append((0 until 14).map { qe("v$it", 0, GtQueueStatus.QUEUED, 1000L + it) })
            eq("dlq|re-entrant caller did not double-launch", launched.size, launched.toSet().size)
            eq("dlq|all 14 still ran", 14, launched.size)
            eq("dlq|the re-entry did happen (the case is real)", true, reentered)
        }

        // --- 17. DEFERRED, AND SAYING SO. ---------------------------------------------------------
        // The simulation's case 17 asserts that an ASYNC fetch -- one that returns with its entry
        // still DOWNLOADING -- must NOT drain past maxConcurrent: 4 launch, 10 stay QUEUED. It cannot
        // be expressed here, because `fetch` returns Boolean: "still running" is not a value it has.
        // That is a property of the FUTURE signature (URLSession will need a completion callback or a
        // suspend fun), so it is recorded, not faked and not dropped. gt_dlqsim_2026-11-17_v2 case 17
        // is the design record, and it is the case that must be ported the day `fetch` becomes async
        // -- otherwise the drain loop silently becomes a loop that ignores the slot limit.
        fact("dlq|case 17 (async no-overdrain)", "DEFERRED -- needs an async fetch signature; see sim v2 case 17")
        fact("dlq|certified here", "state, file, quarantine, resume, selection, order, throughput, drain")
        fact("dlq|NOT certified here", "real tile fetching, true concurrency (runs serially), URLSession")

        try { fs.deleteRecursively(base) } catch (e: Exception) { }

        println("IOSPLATQ mac: $same SAME, $bad DIFF -- done")
        assertEquals(0, bad, "IosDownloadQueue behaved differently from 2.7e's documented behaviour")
        assertTrue(same > 40, "too few assertions ran ($same) -- the queue leg did not execute fully")
    }
}
