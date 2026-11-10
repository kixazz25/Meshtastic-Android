package com.grouptrack.shared.queue

import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtIds
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.text.GtJava
import com.grouptrack.shared.text.GtMath
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okio.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * STEP16B-2026-11-10: what the save-by-area path needs from the platform. Android's is app-side (the 16d switch); the cert's is fixed.
 */
interface GtQueuePlatform {
    /** The end of 2.7e enqueueArea's cell loop: add [entries] to the queue in this order, save download_queue.json, start what can
     *  start (Android: _queue.value += entries; saveQueue(); startNextIfAvailable()). */
    fun append(entries: List<GtQueueEntry>)
    /** ConvoyConfig.DOWNLOAD_ZOOM (a var -- read at each call, as 2.7e's default argument is). segmentCells' zMax. */
    fun downloadZoom(): Int
    /** MapSourceManager's SAT layer count, or null when there is no SAT source. 2.7e reads it on the VEC / DEM path for its
     *  SIZECALC log line only. */
    fun satLayers(): Int?
    /** GroupTrackStorage.dir("recipes"). */
    fun recipesDir(): Path
}

/**
 * STEP16B-2026-11-10 (2.8 step 16b): THE SAVE-BY-AREA WRITE PATH, PORTED AS IS --
 *   convoy/VectorTiles.kt: boxesOf, recipeBoxes, squaresInBox, enqueueWithElevation, sqX / sqY / lonOf / latOf, squaresFor,
 *     queueSquares, queueSquaresNamed, queueFromRecipe;
 *   convoy/ConvoyDownloadQueue.kt: DownloadQueueManager.enqueueArea, its VEC / DEM branch (isVec). The SAT / TOPO branch is not here:
 *     only Android has those sources (the 16d front door keeps it, unchanged).
 * Changes, each the platform rule or an injection:
 *  - java.lang.Math tan / log / cos / atan / sinh / pow -> GtMath; toRadians / toDegrees -> GtJava; Double text -> GtJava.jd.
 *  - queueSquaresNamed's HashMap<Long, Box> -> GtJavaHashMapLong (Java's iteration order = the box order = the job order).
 *  - sortedSetOf<String>() -> a set, then sorted() (String natural order: UTF-16 units, both platforms).
 *  - UUID.randomUUID() / System.currentTimeMillis() (QueueEntry's defaults) -> [ids] / [clock]; org.json -> GtOrgJson;
 *    android.util.Log -> gtLogger (same tags); Context / GroupTrackStorage / ConvoyConfig / MapSourceManager / the queue list -> [plat].
 *  - enqueueArea's init(context) is the platform's (it runs before the shared call).
 */
class GtVectorQueue(
    private val ids: GtIds,
    private val clock: GtClock,
    private val files: GtFiles,
    private val plat: GtQueuePlatform
) {
    companion object {
        const val SLOT = "VEC"          // VectorTiles.SLOT
        const val Z_MAX = 14            // VectorTiles.Z_MAX
        const val DEM_SLOT = "DEM"      // ElevationTiles.SLOT
        const val DEM_Z_MAX = 12        // ElevationTiles.Z_MAX
        private const val SQ_Z = 12
        private const val RING = 1
        private const val TAG = "VectorTiles"
    }
    private val log = gtLogger(TAG)
    private val qlog = gtLogger("DownloadQueue")
    private val sizeLog = gtLogger("SIZECALC")

    private fun boxKey(box: DoubleArray) = box.joinToString(",") { GtJava.jd(it) }   // DoubleArray.joinToString = Double.toString

    private fun boxesOfText(text: String): List<DoubleArray> {
        val slots = (GtOrgJson.parse(text) as JsonObject)["slots"] as? JsonArray ?: return emptyList()
        val seen = HashSet<String>(); val out = ArrayList<DoubleArray>()
        for (i in 0 until slots.size) {
            val b = (slots[i] as? JsonObject)?.get("boxes") as? JsonArray ?: continue
            for (j in 0 until b.size) {
                val x = b[j] as? JsonObject ?: continue
                val box = doubleArrayOf(GtOrgJson.optDouble(x, "n"), GtOrgJson.optDouble(x, "s"), GtOrgJson.optDouble(x, "e"), GtOrgJson.optDouble(x, "w"))
                if (box.any { it.isNaN() }) continue
                if (seen.add(boxKey(box))) out.add(box)
            }
        }
        return out
    }

    /** The newest recipe saved on this phone: every box of every slot (n, s, e, w), exact duplicates once, in file order. */
    fun recipeBoxes(): Pair<String, List<DoubleArray>>? {
        val f = files.list(plat.recipesDir())
            .filter { it.name.startsWith("grouptrack_maps_") && it.name.endsWith(".gtmaps") }
            .maxByOrNull { it.name } ?: return null
        val text = files.fs.read(f) { readUtf8() }
        // 2.7e: optJSONArray("slots") ?: Pair(name, emptyList()) -- boxesOfText returns the empty list for that case
        return Pair(f.name, boxesOfText(text))
    }

    /** VECCONVERT-2026-10-31: every box of one recipe file (n, s, e, w), exact duplicates once. */
    fun boxesOf(f: Path): List<DoubleArray> = boxesOfText(files.fs.read(f) { readUtf8() })

    fun squaresInBox(n: Double, s: Double, e: Double, w: Double, into: MutableSet<Long>) {
        val nn = 1 shl SQ_Z
        val x0 = sqX(w + 1e-9).coerceIn(0, nn - 1); val x1 = sqX(e - 1e-9).coerceIn(0, nn - 1)
        val y0 = sqY(n - 1e-9).coerceIn(0, nn - 1); val y1 = sqY(s + 1e-9).coerceIn(0, nn - 1)
        for (x in x0..x1) for (y in y0..y1) into.add(x.toLong() * nn + y)
    }

    /** DEMSOURCE-2026-10-30: THE BINDING -- the vector box and its elevation, two jobs, one step. */
    fun enqueueWithElevation(n: Double, s: Double, e: Double, w: Double, replaceExisting: Boolean, what: String): Int =
        enqueueArea(SLOT, n, s, e, w, replaceExisting, what) + enqueueArea(DEM_SLOT, n, s, e, w, replaceExisting, what)

    private fun sqX(lon: Double) = floor((lon + 180.0) / 360.0 * (1 shl SQ_Z)).toInt()
    private fun sqY(lat: Double): Int {
        val r = GtJava.toRadians(lat.coerceIn(-85.0, 85.0))
        return floor((1.0 - GtMath.ln(GtMath.tan(r) + 1.0 / GtMath.cos(r)) / PI) / 2.0 * (1 shl SQ_Z)).toInt()
    }
    private fun lonOf(x: Int) = x.toDouble() / (1 shl SQ_Z) * 360.0 - 180.0
    private fun latOf(y: Int): Double { val k = PI - 2.0 * PI * y / (1 shl SQ_Z); return GtJava.toDegrees(GtMath.atan(GtMath.sinh(k))) }

    /** Adds the squares under [segments] (lat, lon), with the ring, to [into]. Long straight legs are walked in ~2 km steps. */
    fun squaresFor(segments: List<List<Pair<Double, Double>>>, into: MutableSet<Long>) {
        val n = 1 shl SQ_Z
        fun add(lat: Double, lon: Double) {
            val x = sqX(lon); val y = sqY(lat)
            for (dx in -RING..RING) for (dy in -RING..RING) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until n && yy in 0 until n) into.add(xx.toLong() * n + yy)
            }
        }
        for (seg in segments) {
            var prev: Pair<Double, Double>? = null
            for (p in seg) {
                val q = prev
                if (q != null) {
                    val steps = maxOf(1, ceil(maxOf(abs(p.first - q.first), abs(p.second - q.second)) / 0.02).toInt())
                    for (i in 1 until steps) { val f = i.toDouble() / steps; add(q.first + (p.first - q.first) * f, q.second + (p.second - q.second) * f) }
                }
                add(p.first, p.second); prev = p
            }
        }
    }

    fun queueSquares(squares: Set<Long>, replaceExisting: Boolean, why: String): Pair<Int, Int> =
        queueSquaresNamed(squares, emptyMap(), replaceExisting, why)

    /** [names] = the tracks each square is for; a box is labelled with the tracks in it (empty -> [why]). Returns (jobs, est. tiles). */
    fun queueSquaresNamed(squares: Set<Long>, names: Map<Long, Set<String>>, replaceExisting: Boolean, why: String): Pair<Int, Int> {
        if (squares.isEmpty()) return Pair(0, 0)
        val n = (1 shl SQ_Z).toLong()
        class Box(val x0: Int, val x1: Int, val y0: Int, var y1: Int)
        val byRow = squares.groupBy { (it % n).toInt() }
        var open = GtJavaHashMapLong<Box>(); val done = ArrayList<Box>()
        for (y in byRow.keys.sorted()) {
            val xs = byRow.getValue(y).map { (it / n).toInt() }.sorted()
            val runs = ArrayList<Pair<Int, Int>>(); var a = xs[0]; var b = xs[0]
            for (x in xs.drop(1)) { if (x == b + 1) b = x else { runs.add(a to b); a = x; b = x } }
            runs.add(a to b)
            val next = GtJavaHashMapLong<Box>()
            for ((x0, x1) in runs) {
                val k = x0.toLong() * n + x1; val o = open[k]
                if (o != null && o.y1 == y - 1) { o.y1 = y; next[k] = o } else next[k] = Box(x0, x1, y, y)
            }
            for ((k, o) in open.entries()) if (next[k] !== o) done.add(o)
            open = next
        }
        done.addAll(open.values())
        var jobs = 0; var tiles = 0
        for (bx in done) {
            val nn = latOf(bx.y0); val ss = latOf(bx.y1 + 1); val ww = lonOf(bx.x0); val ee = lonOf(bx.x1 + 1)
            val inBoxSet = HashSet<String>()
            if (names.isNotEmpty()) for (x in bx.x0..bx.x1) for (y in bx.y0..bx.y1) names[x.toLong() * n + y]?.let { inBoxSet.addAll(it) }
            val inBox = inBoxSet.sorted()
            val what = when { inBox.isEmpty() -> why; inBox.size <= 2 -> inBox.joinToString(", "); else -> inBox.take(2).joinToString(", ") + " +${inBox.size - 2}" }
            jobs += enqueueWithElevation(nn, ss, ee, ww, replaceExisting, what)
            tiles += GtTileMath.quickEstimate(nn, ss, ee, ww, zMin = 0, zMax = Z_MAX).tileCount +
                GtTileMath.quickEstimate(nn, ss, ee, ww, zMin = 0, zMax = DEM_Z_MAX).tileCount
        }
        log.i { "VECDL-2026-10-31 $why: ${squares.size} z12 square(s) -> ${done.size} box(es), $jobs job(s), ~$tiles tiles, replace=$replaceExisting" }
        return Pair(jobs, tiles)
    }

    /** Queue the first [count] recipe boxes as VEC AREA downloads. Returns one line for the screen. */
    fun queueFromRecipe(count: Int): String {
        val r = try { recipeBoxes() } catch (e: Exception) { return "Could not read the recipe: ${e.message}" }
            ?: return "No saved recipe on this phone yet."
        val take = r.second.take(count)
        if (take.isEmpty()) return "${r.first}: no boxes in it."
        var jobs = 0
        for (b in take) jobs += enqueueWithElevation(b[0], b[1], b[2], b[3], false, what = "")
        return "${r.first.removePrefix("grouptrack_maps_").removeSuffix(".gtmaps")}: queued ${take.size} of ${r.second.size} boxes " +
            "as $jobs job(s) -- vector and elevation together (DL VEC, DL DEM). Watch them in Download queues."
    }

    /** DownloadQueueManager.enqueueArea for [slotName] VEC or DEM (2.7e's isVec branch). Returns the number of jobs queued. */
    fun enqueueArea(slotName: String, north: Double, south: Double, east: Double, west: Double, replaceExisting: Boolean, what: String): Int {
        require(slotName == SLOT || slotName == DEM_SLOT) { "GtVectorQueue.enqueueArea: VEC / DEM only, not $slotName" }
        val sfx = if (what.isBlank()) "" else " · $what"
        val slotLayers = 1
        val vecZ = if (slotName == DEM_SLOT) DEM_Z_MAX else Z_MAX
        val oneLayerTiles = GtTileMath.quickEstimate(north, south, east, west, zMin = 0, zMax = vecZ).tileCount
        val satLayers = plat.satLayers() ?: slotLayers
        val sizeTiles = oneLayerTiles
        val cells = GtTileMath.segmentCells(north, south, east, west, sizeTiles, zMax = plat.downloadZoom())
        run {
            val entryTilesWhole = oneLayerTiles * slotLayers
            sizeLog.i {
                "src=enqueueArea label='DL $slotName' slot=$slotName " +
                    "N=${GtJava.jd(north)} S=${GtJava.jd(south)} E=${GtJava.jd(east)} W=${GtJava.jd(west)} " +
                    "dLat=${GtJava.jd(north - south)} dLon=${GtJava.jd(east - west)} " +
                    "oneLayer=$oneLayerTiles slotLayers=$slotLayers satLayers=$satLayers " +
                    "sizeTiles=$sizeTiles segments=${cells.size} " +
                    "entryTilesWholeBox=$entryTilesWhole " +
                    "perCellApprox=${entryTilesWhole / maxOf(1, cells.size)} " +
                    "replace=$replaceExisting"
            }
        }
        if (cells.isEmpty()) {   // 2.7e: UNREACHABLE (segmentCells floors at 1) -- kept, as 2.7e keeps it
            val entry = GtQueueEntry(
                north = north, south = south, east = east, west = west,
                totalTiles = oneLayerTiles * slotLayers,
                label = "DL $slotName" + sfx,
                refreshMode = replaceExisting,
                refreshSlot = slotName,
                downloadType = GtDownloadType.MAP_SOURCE_REFRESH,
                priority = GtDownloadPriority.REFRESH,
                id = ids.newId(), createdAt = clock.epochMs()   // 2.7e's defaults: evaluated after the given arguments, id first
            )
            plat.append(listOf(entry))
            return 1
        }
        val added = ArrayList<GtQueueEntry>()
        cells.forEachIndexed { i, cell ->
            val entry = GtQueueEntry(
                north = cell[0], south = cell[1], east = cell[2], west = cell[3],
                totalTiles = GtTileMath.quickEstimate(cell[0], cell[1], cell[2], cell[3], zMin = 0, zMax = vecZ).tileCount * slotLayers,
                label = "DL $slotName ${i + 1}/${cells.size}" + sfx,
                refreshMode = replaceExisting,
                refreshSlot = slotName,
                downloadType = GtDownloadType.MAP_SOURCE_REFRESH,
                priority = GtDownloadPriority.REFRESH,
                id = ids.newId(), createdAt = clock.epochMs()
            )
            sizeLog.i {
                "  cell ${i + 1}/${cells.size} src=enqueueArea slot=$slotName " +
                    "label='${entry.label}' entryTiles=${entry.totalTiles} " +
                    "N=${GtJava.jd(cell[0])} S=${GtJava.jd(cell[1])} E=${GtJava.jd(cell[2])} W=${GtJava.jd(cell[3])} " +
                    "id=${entry.id}"
            }
            added.add(entry)
        }
        plat.append(added)
        qlog.i { "Enqueued area: $slotName ${cells.size} cells replace=$replaceExisting" }
        return cells.size
    }
}
