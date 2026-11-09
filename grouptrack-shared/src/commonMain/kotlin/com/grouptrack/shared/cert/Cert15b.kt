package com.grouptrack.shared.cert

import com.grouptrack.shared.imports.GtGpxParse
import com.grouptrack.shared.imports.GtImportConverter
import com.grouptrack.shared.text.GtJava
import com.grouptrack.shared.text.GtRx
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path

/**
 * STEP15B-2026-11-09 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the step 15b certification's ONE runner,
 * used by every side so the cases, the order and the fingerprints cannot drift between them:
 *  - on the Droid, GtCert15b (app) runs it with OLD (the 2.7e copies, ImportParseCertOld / ImportConverterCertOld) and with [NewSide];
 *    the two line files are compared (re-syncing on keys, as GtCertRides);
 *  - on the iPad simulator, GtCert15bMacTest runs it with [NewSide] on the same files and compares with the Droid's NEW values.
 * Per test file, in this order: read | importExt | importExt(2) | extractEarliestTime | trailMarked (each <trk> block) | sanitizeFilename
 * (each <name>) | parseGpxWaypoints | parseGpxRoutes | parseGpxTrackPointsFull | toGpx asTrails=false and =true, twice each (the second
 * run exercises the "_2" name). Lists are fingerprinted: count, SHA-256 (first 16 hex) of their rendering, and the first item.
 * Doubles are rendered with GtJava.jd (= Double.toString on Android), so every side writes the same text for the same value.
 * A test file whose name starts with "expected_" carries " (EXPECTED ...)" on its toGpx keys: known different, not a failure.
 */
object Cert15b {

    class Wpt(val name: String, val lat: Double, val lon: Double, val type: String, val desc: String)
    class Rte(val name: String, val points: List<Pair<Double, Double>>, val desc: String)
    class Pt(val lat: Double, val lon: Double, val ele: Double?, val timeMs: Long?)
    class Conv(val fileName: String, val lines: Int, val tracks: Int, val points: Int)

    /** One implementation of the 15b functions. The runner never calls anything else. */
    interface Side {
        val name: String
        fun readText(fs: FileSystem, path: Path): String
        fun importExt(fileName: String): String
        fun trailMarked(trk: String, file: String): Boolean
        fun extractEarliestTime(content: String): Long?
        fun sanitizeFilename(name: String): String
        fun waypoints(text: String): List<Wpt>
        fun routes(text: String): List<Rte>
        fun pointsFull(text: String): List<Pt>
        /** throws as the implementation throws */
        fun toGpx(fs: FileSystem, src: Path, outDir: Path, asTrails: Boolean): Conv
    }

    /** NEW: the shared code (STEP15B). */
    object NewSide : Side {
        override val name = "NEW"
        override fun readText(fs: FileSystem, path: Path): String = fs.read(path) { readUtf8() }
        override fun importExt(fileName: String) = GtGpxParse.importExt(fileName)
        override fun trailMarked(trk: String, file: String) = GtGpxParse.trailMarked(trk, file)
        override fun extractEarliestTime(content: String) = GtGpxParse.extractEarliestTime(content)
        override fun sanitizeFilename(name: String) = GtGpxParse.sanitizeFilename(name)
        override fun waypoints(text: String) = GtGpxParse.parseGpxWaypoints(text).map { Wpt(it.name, it.lat, it.lon, it.type, it.description) }
        override fun routes(text: String) = GtGpxParse.parseGpxRoutes(text).map { Rte(it.name, it.points, it.description) }
        override fun pointsFull(text: String) = GtGpxParse.parseGpxTrackPointsFull(text).map { Pt(it.lat, it.lon, it.eleM, it.timeMs) }
        override fun toGpx(fs: FileSystem, src: Path, outDir: Path, asTrails: Boolean): Conv =
            GtImportConverter.toGpx(fs, src, outDir, asTrails).let { Conv(it.file.name, it.lines, it.tracks, it.points) }
    }

    /** 2.7e's own error texts (ImportConverter); any other exception is the platform's and is compared as THROWS(platform). */
    private val OWN = listOf("no lines or points found", "no .kml inside ")

    fun sha(s: String): String = s.encodeUtf8().sha256().hex().take(16)
    /** a value on one line: tab, CR and LF escaped. */
    fun esc(s: String): String = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
    private fun d(x: Double) = GtJava.jd(x)
    /** a list's fingerprint, built as it goes (a 29 MB GPX has about a million points: no list of strings is kept) -- the SHA-256 of
     *  the items joined with \u0001, as UTF-8. */
    private class Fp {
        private val buf = okio.Buffer()
        private var n = 0
        private var first = ""
        fun add(s: String) { if (n > 0) buf.writeUtf8("\u0001"); buf.writeUtf8(s); if (n == 0) first = s; n++ }
        fun text(): String = "n=$n;sha=${buf.sha256().hex().take(16)};first=${esc(first.take(200))}"
    }
    private inline fun <T> list(items: Iterable<T>, render: (T) -> String): String { val f = Fp(); for (x in items) f.add(render(x)); return f.text() }

    private val TRK = Regex("""<trk>([\s\S]*?)</trk>""")          // importGpxAllArtifacts' track split (2.7e)
    private val NAME = Regex("""<name>([^<]*)</name>""")

    /**
     * Runs every case for one test file on [side]; [work] is this side's scratch folder (emptied per toGpx case). [emit] gets
     * (key, value): key = "<file>|<function>[|<case>]".
     */
    fun runFile(side: Side, fs: FileSystem, file: Path, work: Path, emit: (String, String) -> Unit) {
        val n = file.name
        val label = if (n.startsWith("expected_")) " (EXPECTED ${n.removePrefix("expected_").substringBefore('.').replace('_', ' ')})" else ""
        fun guard(key: String, f: () -> String) = emit(key, try { f() } catch (e: Throwable) { "EXCEPTION " + (e.message ?: e::class.simpleName) })
        val text = try { side.readText(fs, file) } catch (e: Throwable) { emit("$n|read", "EXCEPTION " + e.message); return }
        emit("$n|read", "len=${text.length};sha=${sha(text)}")
        guard("$n|importExt") { side.importExt(n) }
        guard("$n|importExt(2)") { side.importExt("$n (2)") }
        guard("$n|extractEarliestTime") { side.extractEarliestTime(text).toString() }
        guard("$n|trailMarked") {
            val blocks = GtRx.findAll(TRK, text).map { it.groupValues[1] }.toList()
            val marks = blocks.map { if (side.trailMarked(it, text)) "T" else "F" }
            "n=${blocks.size};marked=${marks.count { it == "T" }};sha=${sha(marks.joinToString(""))}"
        }
        guard("$n|sanitizeFilename") { list(GtRx.findAll(NAME, text).asIterable()) { side.sanitizeFilename(it.groupValues[1].trim()) } }
        guard("$n|parseGpxWaypoints") {
            list(side.waypoints(text)) { "${it.name}\u0002${d(it.lat)}\u0002${d(it.lon)}\u0002${it.type}\u0002${it.desc}" }
        }
        guard("$n|parseGpxRoutes") {
            list(side.routes(text)) { r -> r.name + "\u0002" + r.desc + "\u0002" + r.points.joinToString(",") { "${d(it.first)} ${d(it.second)}" } }
        }
        guard("$n|parseGpxTrackPointsFull") {
            list(side.pointsFull(text)) { "${d(it.lat)} ${d(it.lon)} ${it.ele?.let { e -> d(e) }} ${it.timeMs}" }
        }
        for (asTrails in listOf(false, true)) {
            val case = work / ("tg_" + (if (asTrails) "T" else "F"))
            if (fs.exists(case)) fs.deleteRecursively(case)
            val inDir = case / "in"; val outDir = case / "out"
            fs.createDirectories(inDir); fs.createDirectories(outDir)
            val src = inDir / n
            fs.copy(file, src)
            for (run in 1..2) guard("$n|toGpx|asTrails=$asTrails|run$run$label") {
                try {
                    val c = side.toGpx(fs, src, outDir, asTrails)
                    val bytes = fs.read(outDir / c.fileName) { readByteString() }
                    "name=${c.fileName};lines=${c.lines};tracks=${c.tracks};points=${c.points};len=${bytes.size};sha=${bytes.sha256().hex().take(16)}"
                } catch (e: Throwable) {
                    val m = e.message ?: ""
                    if (OWN.any { m.startsWith(it) }) "THROWS($m)" else "THROWS(platform)"
                }
            }
        }
    }
}
