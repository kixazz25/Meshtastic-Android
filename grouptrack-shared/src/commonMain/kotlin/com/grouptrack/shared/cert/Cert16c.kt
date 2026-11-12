package com.grouptrack.shared.cert

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtIds
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.queue.GtJavaHashMapLong
import com.grouptrack.shared.queue.GtQueueEntry
import com.grouptrack.shared.queue.GtQueueJson
import com.grouptrack.shared.queue.GtQueuePlatform
import com.grouptrack.shared.queue.GtTileMath
import com.grouptrack.shared.queue.GtVectorQueue
import com.grouptrack.shared.text.GtLocalTime
import okio.FileSystem
import okio.Path

/**
 * STEP16C-2026-11-11 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the step 16c certification's ONE runner
 * (the save-by-area writes: 2.7e VectorTiles + DownloadQueueManager.enqueueArea's VEC / DEM branch, ported in 16b as GtVectorQueue /
 * GtTileMath / GtQueueJson), used by every side so the cases, their order and the values cannot drift:
 *  - on the Droid, GtCert16c (app) runs it with OLD (VectorTilesCertOld / DownloadQueueCertOld: the GENERATED copies, made by
 *    make_old_copies_16c.py) and with [NewSide]; the two value streams are compared key by key;
 *  - on the iPad simulator, GtCertQueue16cMacTest runs it with [NewSide] on the same input and compares with the Droid's report.
 * WHAT IT PROVES (plan v10 step 16, Fred 11-09): from the same area, track or recipe, OLD and NEW queue the same requests -- boxes, cells,
 * counts, labels, order, and the download_queue.json text. A pure write: nothing is downloaded and the live queue is never touched
 * (the OLD copy's queue is a list the cert holds; NEW's platform collects).
 * IDS AND CLOCKS: 2.7e's QueueEntry takes UUID.randomUUID() and System.currentTimeMillis() as defaults; OLD's entries are renumbered in
 * append order (q1, q2, ... per case, createdAt 0); NEW's GtIds gives q1, q2, ... (reset per case) and its GtClock 0 -- so NEW's own id /
 * clock order is checked, not rewritten.
 * THE INPUT, real data only (the same on every side; its fingerprints are compared before anything else -- "cross-device COMPARED"):
 *   the tracks of the 15c input database (cert15c_input on the Droid = _data/live_2026-11-08 on the Mac): geom_hash, name, geometry
 *   parsed by 2.7e's SpatialDbManager.parseWktSegments (copied below as [parseWkt] -- the reader, not what is certified);
 *   the recipes (*.gtmaps) of Droid 1 (the Mac's _data/droid1_2026-11-06/recipes -> the Droid's GroupTrack/cert16c_input/recipes);
 *   the boxes of Droid 1's download_queue.json (Fred's real save-by-area areas, READ only on the Droid, written into its report as hex
 *   bits so the iPad replays exactly the same ones).
 * THE SCRIPT (keys in brackets):
 *   A  every track: squaresFor, then queueSquaresNamed with its own name (2.7e enqueueCorridor's vector path)       [A|<k>|...]
 *   B  all tracks together (enqueueCorridorBatch's vector path, the multi-corridor HashMap order), replace on; then queueSquares
 *      of the same squares (the conversion path)                                                                    [B|...] [B2|...]
 *   C  every recipe: boxesOf, squaresInBox, queueSquares "restore <name>" (MapRecipes / EmergencyRestore / MapConversion)  [C|<file>|...]
 *   D  recipeBoxes and queueFromRecipe(5) / (all) on the recipe folder                                              [D|...]
 *   E  crafted folders: empty, missing, no boxes, no slots, odd boxes (duplicates, a missing edge, numbers as text, 1e1, junk
 *      entries, the newest-name rule, other files), corrupt (the reader's message differs -- EXPECTED, the outcome is the same)   [E|<case>|...]
 *   F  every box of the download queue: enqueueWithElevation (submitDownload's vector path)                         [F|<k>|...]
 *   G  crafted boxes: Utah (splits into many cells), a small Moab box with a non-ASCII label, a point, a blank label      [G|<case>|...]
 *   H  the tile math on every box above: quickEstimate z0-14 / z0-12 / z0-18 / z4-18, segmentCells (the box's own size, 200,000
 *      tiles, 3,000,000 at z14)                                                                                       [H|<k>|...]
 * Every queuing call: its result, then every entry it queued (org.json compact text) and the file text's length + sha.
 * [treeBins] counts the HashMap tree-bin case (the one order GtJavaHashMapLong does not reproduce -- 49+ runs in one z12 row).
 */
object Cert16c {
    data class Est(val tileCount: Int, val mbBits: Int, val within: Boolean, val zMin: Int, val zMax: Int)

    interface Side {
        val name: String
        fun setRecipesDir(dir: Path)
        fun boxesOf(f: Path): List<DoubleArray>
        fun recipeBoxes(): Pair<String, List<DoubleArray>>?
        fun squaresInBox(n: Double, s: Double, e: Double, w: Double, into: MutableSet<Long>)
        fun squaresFor(segments: List<List<Pair<Double, Double>>>, into: MutableSet<Long>)
        fun enqueueWithElevation(n: Double, s: Double, e: Double, w: Double, replace: Boolean, what: String): Int
        fun queueSquares(sq: Set<Long>, replace: Boolean, why: String): Pair<Int, Int>
        fun queueSquaresNamed(sq: Set<Long>, names: Map<Long, Set<String>>, replace: Boolean, why: String): Pair<Int, Int>
        fun queueFromRecipe(count: Int): String
        fun quickEstimate(n: Double, s: Double, e: Double, w: Double, zMin: Int, zMax: Int): Est
        fun segmentCells(n: Double, s: Double, e: Double, w: Double, sizeTiles: Int, zMax: Int): List<DoubleArray>
        /** The entries queued since the last call, in append order, as org.json compact text each, and the download_queue.json text
         *  of exactly those entries (saveQueue: JSONArray.toString(2)). Clears them. */
        fun takeEntries(): Pair<List<String>, String>
    }

    /** NEW: the shared code (16b), on a fixed platform: append collects, DOWNLOAD_ZOOM 18, no SAT source (its layer count feeds a log
     *  line only), the recipe folder the case sets. [files]' list = GtImportAndroid's contract (java.io.File.listFiles() ?: empty). */
    class NewSide(private val fsys: FileSystem) : Side {
        override val name = "NEW"
        private val appended = ArrayList<GtQueueEntry>()
        private var k = 0
        private var recipes: Path? = null   // CODE RULE 1: null = no folder set yet (the script sets it before any recipe call)
        private val plat = object : GtQueuePlatform {
            override fun append(entries: List<GtQueueEntry>) { appended.addAll(entries) }
            override fun downloadZoom(): Int = 18
            override fun satLayers(): Int? = null
            override fun recipesDir(): Path = recipes ?: throw IllegalStateException("cert: no recipe folder set")
        }
        private val files = object : GtFiles {
            override val fs: FileSystem = fsys
            override fun setLastModified(path: Path, epochMs: Long): Boolean = throw UnsupportedOperationException("not used by 16c")
            override fun lastModified(path: Path): Long = throw UnsupportedOperationException("not used by 16c")
            override fun list(dir: Path): List<Path> = fsys.listOrNull(dir) ?: emptyList()
        }
        private val ids = object : GtIds { override fun newId(): String { k++; return "q$k" } }
        private val clock = object : GtClock {
            override fun nowInstant(): String = throw UnsupportedOperationException("not used by 16c")
            override fun localNow(): GtLocalTime = throw UnsupportedOperationException("not used by 16c")
            override fun epochMs(): Long = 0L
        }
        private val q = GtVectorQueue(ids, clock, files, plat)
        override fun setRecipesDir(dir: Path) { recipes = dir }
        override fun boxesOf(f: Path) = q.boxesOf(f)
        override fun recipeBoxes() = q.recipeBoxes()
        override fun squaresInBox(n: Double, s: Double, e: Double, w: Double, into: MutableSet<Long>) = q.squaresInBox(n, s, e, w, into)
        override fun squaresFor(segments: List<List<Pair<Double, Double>>>, into: MutableSet<Long>) = q.squaresFor(segments, into)
        override fun enqueueWithElevation(n: Double, s: Double, e: Double, w: Double, replace: Boolean, what: String) =
            q.enqueueWithElevation(n, s, e, w, replace, what)
        override fun queueSquares(sq: Set<Long>, replace: Boolean, why: String) = q.queueSquares(sq, replace, why)
        override fun queueSquaresNamed(sq: Set<Long>, names: Map<Long, Set<String>>, replace: Boolean, why: String) =
            q.queueSquaresNamed(sq, names, replace, why)
        override fun queueFromRecipe(count: Int) = q.queueFromRecipe(count)
        override fun quickEstimate(n: Double, s: Double, e: Double, w: Double, zMin: Int, zMax: Int): Est =
            GtTileMath.quickEstimate(n, s, e, w, zMin, zMax).let { Est(it.tileCount, it.estimatedMB.toRawBits(), it.withinCeiling, it.zoomMin, it.zoomMax) }
        override fun segmentCells(n: Double, s: Double, e: Double, w: Double, sizeTiles: Int, zMax: Int) =
            GtTileMath.segmentCells(n, s, e, w, sizeTiles, zMax)
        override fun takeEntries(): Pair<List<String>, String> {
            val es = appended.toList(); appended.clear(); k = 0
            return Pair(es.map { GtOrgJson.compact(GtQueueJson.toJson(it)) }, GtQueueJson.fileText(es))
        }
    }

    // =================================================================================================================================
    // the input
    // =================================================================================================================================
    class Track(val hash: String, val name: String, val segs: List<List<Pair<Double, Double>>>?)

    /** 2.7e SpatialDbManager.parseWktSegments, as it is (the input reader -- the same on every side; its output is fingerprinted). */
    fun parseWkt(wkt: String): List<List<Pair<Double, Double>>>? {
        val g = wkt.trim()
        if (g.isBlank()) return null
        val segments: List<String> = when {
            g.startsWith("MULTILINESTRING(") -> g.removePrefix("MULTILINESTRING(").removeSuffix(")").split("),(").map { it.trim('(', ')') }
            g.startsWith("LINESTRING(") -> listOf(g.removePrefix("LINESTRING(").removeSuffix(")"))
            else -> return null
        }
        val out = segments.mapNotNull { seg ->
            val pts = seg.split(",").mapNotNull { p ->
                val parts = p.trim().split(" ")
                if (parts.size < 2) return@mapNotNull null
                val lon = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                Pair(lat, lon)          // FLIPPED - WKT is lon lat
            }
            if (pts.isEmpty()) null else pts
        }
        return if (out.isEmpty()) null else out
    }

    /** Every distinct track geom_hash (in hash order) with 2.7e's corridor name (SpatialDbManager.nameForGeomHash: the tracks row,
     *  then routes, else the hash's first 8) and geometry (getTrackPoints: the first row with that hash). */
    fun readTracks(c: SQLiteConnection): List<Track> {
        val firsts = LinkedHashMap<String, Pair<String?, String?>>()
        val st = c.prepare("SELECT geom_hash, name, geometry FROM tracks WHERE geom_hash IS NOT NULL ORDER BY geom_hash, rowid")
        try { while (st.step()) { val h = st.getText(0); if (h !in firsts) firsts[h] = Pair(if (st.isNull(1)) null else st.getText(1), if (st.isNull(2)) null else st.getText(2)) } }
        finally { st.close() }
        fun routeName(h: String): String? {
            val r = c.prepare("SELECT name FROM routes WHERE geom_hash=? LIMIT 1")
            try { r.bindText(1, h); return if (r.step() && !r.isNull(0)) r.getText(0).takeIf { it.isNotBlank() } else null } finally { r.close() }
        }
        return firsts.map { (h, v) ->
            val nm = v.first?.takeIf { it.isNotBlank() } ?: routeName(h) ?: h.take(8)
            Track(h, nm, v.second?.let { parseWkt(it) })
        }
    }

    fun hx(d: Double): String = d.toRawBits().toULong().toString(16)
    fun dx(s: String): Double = Double.fromBits(s.toULong(16).toLong())
    fun boxText(b: DoubleArray): String = b.joinToString(",") { hx(it) }
    fun boxOf(t: String): DoubleArray = t.split(',').map { dx(it) }.toDoubleArray()

    /** input|tracks: every track's hash, name and points (hex bits), fingerprinted */
    fun tracksText(ts: List<Track>): String {
        val sb = StringBuilder(); var pts = 0
        for (t in ts) {
            sb.append(t.hash).append('|').append(t.name).append('|')
            val s = t.segs
            if (s == null) sb.append("NULL") else for (seg in s) { for (p in seg) { sb.append(hx(p.first)).append(',').append(hx(p.second)).append(';'); pts++ }; sb.append('/') }
            sb.append('\n')
        }
        return "n=${ts.size};points=$pts;fp=" + Cert15d.sha(sb.toString())
    }

    // ---- crafted recipe folders ----
    class Crafted(val dirs: List<Pair<String, Path>>)

    fun craft(fs: FileSystem, root: Path): Crafted {
        if (fs.exists(root)) fs.deleteRecursively(root)
        fs.createDirectories(root)
        fun dir(n: String): Path { val d = root / n; fs.createDirectories(d); return d }
        fun file(d: Path, n: String, t: String) { fs.write(d / n) { writeUtf8(t) } }
        val empty = dir("empty")
        val missing = root / "missing"   // never created
        val noboxes = dir("noboxes"); file(noboxes, "grouptrack_maps_2099-01-01.gtmaps", """{"slots":[{"slot":"SAT","tiles":5,"boxes":[]}]}""")
        val noslots = dir("noslots"); file(noslots, "grouptrack_maps_2099-01-02.gtmaps", """{"version":1}""")
        val odd = dir("odd")
        file(odd, "grouptrack_maps_2099-01-03.gtmaps", """{"slots":[{"slot":"SAT","tiles":12,"boxes":[""" +
            """{"n":38.5,"s":38.25,"e":-109.5,"w":-109.75},{"n":38.5,"s":38.25,"e":-109.5,"w":-109.75},""" +
            """{"n":"38.75","s":"38.5","e":-109.5,"w":-109.75},{"n":39,"s":38,"e":-110,"w":-111},{"n":38.5,"s":38.25,"e":-109.5}]},""" +
            """{"slot":"TOPO"},{"slot":"VEC","boxes":[{"n":37.1,"s":37.0,"e":-113.4,"w":-113.5}]},"x",""" +
            """{"slot":"DEM","boxes":["y",{"n":1e1,"s":9.5,"e":-100,"w":-100.5},{"n":38.5,"s":38.25,"e":-109.5,"w":-109.75}]}]}""")
        file(odd, "grouptrack_maps_2001-01-01.gtmaps", """{"slots":[{"slot":"SAT","boxes":[{"n":40.0,"s":39.5,"e":-111.0,"w":-111.5}]}]}""")
        file(odd, "grouptrack_maps_2099-12-31.gtmaps.bak", """{"slots":[]}""")
        file(odd, "notes.txt", "not a recipe")
        val corrupt = dir("corrupt"); file(corrupt, "grouptrack_maps_2099-01-04.gtmaps", "not json {")
        return Crafted(listOf("empty" to empty, "missing" to missing, "noboxes" to noboxes, "noslots" to noslots, "odd" to odd,
            "corrupt (EXPECTED: the reader's message text differs, the outcome is the same)" to corrupt))
    }

    /** the crafted boxes of G: (key, n, s, e, w, replace, what) */
    private val G = listOf(
        listOf("utah", 42.0, 37.0, -109.04, -114.05, false, "Utah"),
        listOf("moab", 38.62, 38.55, -109.45, -109.6, true, "Moab · Slickrock éü"),
        listOf("point", 38.5733, 38.5733, -109.5498, -109.5498, false, ""),
        listOf("blank", 37.2, 37.0, -113.4, -113.7, false, "   "))

    /** the HashMap tree-bin case in queueSquaresNamed's "next" map, counted the way 16b's GtJavaHashMapLong counts it (side-free) */
    fun treeBins(sq: Set<Long>): Int {
        if (sq.isEmpty()) return 0
        val n = 4096L
        var tb = 0
        for ((_, row) in sq.groupBy { (it % n).toInt() }) {
            val xs = row.map { (it / n).toInt() }.sorted()
            val m = GtJavaHashMapLong<Int>()
            var a = xs[0]; var b = xs[0]
            for (x in xs.drop(1)) { if (x == b + 1) b = x else { m[a.toLong() * n + b] = 1; a = x; b = x } }
            m[a.toLong() * n + b] = 1
            tb += m.treeified
        }
        return tb
    }

    private fun setText(s: Set<Long>): String {
        val l = s.sorted()
        return "n=${l.size};fp=" + Cert15d.sha(l.joinToString(",")) + (if (l.size <= 40) ";sq=" + l.joinToString(",") else "")
    }
    private fun boxesText(bs: List<DoubleArray>): String =
        "n=${bs.size};fp=" + Cert15d.sha(bs.joinToString("/") { boxText(it) }) + (if (bs.size <= 12) ";boxes=" + bs.joinToString("/") { boxText(it) } else "")
    private fun estText(e: Est) = "tiles=${e.tileCount};mb=${e.mbBits.toUInt().toString(16)};within=${e.within};z=${e.zMin}-${e.zMax}"

    // =================================================================================================================================
    // the script
    // =================================================================================================================================
    class Inputs(val tracks: List<Track>, val recipesDir: Path, val recipeFiles: List<Path>, val qboxes: List<DoubleArray>, val crafted: Crafted)

    /** the recipes of a folder, by name (what C walks) */
    fun recipeFiles(fs: FileSystem, dir: Path): List<Path> = (fs.listOrNull(dir) ?: emptyList()).filter { it.name.endsWith(".gtmaps") }.sortedBy { it.name }

    fun run(side: Side, inp: Inputs, fs: FileSystem, emit: (String, String) -> Unit, progress: (String) -> Unit) {
        fun call(key: String, f: () -> String) { emit(key, try { f() } catch (e: Throwable) { "EXCEPTION" }) }
        fun entries(key: String) {
            val r = try { side.takeEntries() } catch (e: Throwable) { emit("$key|entries", "EXCEPTION"); return }
            emit("$key|n", r.first.size.toString())
            r.first.forEachIndexed { i, t -> emit("$key|e|" + (i + 1).toString().padStart(5, '0'), t) }
            emit("$key|file", "len=${r.second.length};sha=" + Cert15d.sha(r.second))
        }
        side.takeEntries()   // nothing before the script

        // ---- A / B: the corridors ----
        val all = HashSet<Long>(); val names = HashMap<Long, MutableSet<String>>(); var used = 0
        inp.tracks.forEachIndexed { i, t ->
            val k = "A|" + (i + 1).toString().padStart(4, '0')
            if (i % 10 == 0) progress("${side.name} A track ${i + 1} of ${inp.tracks.size}")
            val segs = t.segs
            if (segs == null) { emit("$k|skip", "no geometry"); return@forEachIndexed }
            val mine = HashSet<Long>()
            call("$k|sq") { side.squaresFor(segs, mine); setText(mine) }
            emit("$k|tb", treeBins(mine).toString())
            call("$k|q") { side.queueSquaresNamed(mine, mine.associateWith { setOf(t.name) }, false, "corridor ${t.name}").toString() }
            entries(k)
            for (q in mine) names.getOrPut(q) { HashSet() }.add(t.name)
            all.addAll(mine); used++
        }
        progress("${side.name} B"); emit("B|sq", setText(all)); emit("B|tb", treeBins(all).toString())
        call("B|q") { side.queueSquaresNamed(all, names, true, "corridors x${inp.tracks.size}").toString() }; entries("B")
        call("B2|q") { side.queueSquares(all, false, "conversion").toString() }; entries("B2")

        // ---- C / D: the recipes ----
        val hboxes = LinkedHashMap<String, DoubleArray>()
        for (f in inp.recipeFiles) {
            val k = "C|" + f.name
            progress("${side.name} $k")
            var boxes: List<DoubleArray> = emptyList()
            call("$k|boxes") { boxes = side.boxesOf(f); boxesText(boxes) }
            for (b in boxes) hboxes.getOrPut(boxText(b)) { b }
            val sq = HashSet<Long>()
            call("$k|sq") { for (b in boxes) side.squaresInBox(b[0], b[1], b[2], b[3], sq); setText(sq) }
            emit("$k|tb", treeBins(sq).toString())
            call("$k|q") { side.queueSquares(sq, false, "restore " + f.name.removePrefix("grouptrack_maps_").removeSuffix(".gtmaps")).toString() }
            entries(k)
        }
        progress("${side.name} D")
        side.setRecipesDir(inp.recipesDir)
        call("D|rb") { side.recipeBoxes()?.let { it.first + "|" + boxesText(it.second) } ?: "null" }
        call("D|r5") { side.queueFromRecipe(5) }; entries("D|r5")
        call("D|rall") { side.queueFromRecipe(Int.MAX_VALUE) }; entries("D|rall")

        // ---- E: crafted folders ----
        for ((nm, dir) in inp.crafted.dirs) {
            val k = "E|$nm"
            side.setRecipesDir(dir)
            call("$k|rb") { side.recipeBoxes()?.let { it.first + "|" + boxesText(it.second) } ?: "null" }
            for (f in recipeFiles(fs, dir)) call("$k|boxesOf|" + f.name) { boxesText(side.boxesOf(f)) }
            call("$k|r5") { side.queueFromRecipe(5) }; entries("$k|r5")
        }

        // ---- F: the download queue's boxes ----
        inp.qboxes.forEachIndexed { i, b ->
            val k = "F|" + (i + 1).toString().padStart(4, '0')
            if (i % 25 == 0) progress("${side.name} F box ${i + 1} of ${inp.qboxes.size}")
            hboxes.getOrPut(boxText(b)) { b }
            call("$k|n") { side.enqueueWithElevation(b[0], b[1], b[2], b[3], false, "").toString() }; entries(k)
        }

        // ---- G: crafted boxes ----
        for (g in G) {
            val k = "G|" + g[0]; val b = doubleArrayOf(g[1] as Double, g[2] as Double, g[3] as Double, g[4] as Double)
            progress("${side.name} $k")
            hboxes.getOrPut(boxText(b)) { b }
            call("$k|n") { side.enqueueWithElevation(b[0], b[1], b[2], b[3], g[5] as Boolean, g[6] as String).toString() }; entries(k)
        }

        // ---- H: the tile math on every box above ----
        progress("${side.name} H (${hboxes.size} boxes)")
        var i = 0
        for ((t, b) in hboxes) {
            val k = "H|" + (++i).toString().padStart(4, '0')
            emit("$k|box", t)
            var own = 0
            call("$k|qe14") { side.quickEstimate(b[0], b[1], b[2], b[3], 0, 14).also { own = it.tileCount }.let { estText(it) } }
            call("$k|qe12") { estText(side.quickEstimate(b[0], b[1], b[2], b[3], 0, 12)) }
            call("$k|qe18") { estText(side.quickEstimate(b[0], b[1], b[2], b[3], 0, 18)) }
            call("$k|qe4-18") { estText(side.quickEstimate(b[0], b[1], b[2], b[3], 4, 18)) }
            call("$k|seg") { boxesText(side.segmentCells(b[0], b[1], b[2], b[3], own, 18)) }
            call("$k|seg200k") { boxesText(side.segmentCells(b[0], b[1], b[2], b[3], 200_000, 18)) }
            call("$k|seg3m14") { boxesText(side.segmentCells(b[0], b[1], b[2], b[3], 3_000_000, 14)) }
        }
        progress("${side.name} done: $used tracks, ${inp.recipeFiles.size} recipes, ${inp.qboxes.size} queue boxes, ${hboxes.size} boxes for the tile math")
    }
}
