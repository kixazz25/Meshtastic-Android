package com.grouptrack.shared.cert

import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtImportPaths
import okio.Path
import com.grouptrack.shared.maprecipe.GtMapRecipe
import com.grouptrack.shared.maprecipe.GtRecipeEnv
import com.grouptrack.shared.maprecipe.GtRecipeResult
import com.grouptrack.shared.text.GtLocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * CERT17MAPRECIPE-2026-11-14 (2.8 step 17, Phase 2 pass 2): ConvoyMapRecipe, OLD vs NEW.
 *
 * SIMULATED BEFORE IT WAS WRITTEN (sim_maprecipe_v2_2026-11-14): 12 fixtures x 2 entry points
 * (writeDaily / writeFresh) = 24 scenarios, EVERY ONE TERMINATES, 182 compared keys, no collisions.
 * The simulation caught one of its own fixtures being wrong (already-written-today seeded a typed
 * "2026-11-01" filename while the pinned clock resolves to 2025-11-01, so it silently tested the
 * happy path). Fixture dates are derived from [NOW] here for the same reason.
 *
 * ⛔ THE DOCUMENT IS COMPARED BY PARSED VALUES, NEVER BY FILE TEXT. org.json's JSONObject is
 * HashMap-backed, so the written key ORDER is an artefact of a hash table and not a decision the
 * service makes -- pass 1's saveMap lesson, applying unchanged. Each side writes with its own
 * library and READS BACK with its own library: the round trip is the key.
 *
 * ⛔ EVERY DOUBLE IS COMPARED TWICE, AS BITS AND AS TEXT.
 * Kotlin's Double.toString() is platform-specific (JVM's algorithm on Android, Kotlin/Native's on
 * the iPad). The box coordinates are long decimals such as 84.23194746223983, unlike pass 1's short
 * 37.8 / 12.5. toRawBits() pins the COMPUTED VALUE; the decimal string pins WHAT IS WRITTEN TO THE
 * FILE, which is what a restore would read. If the bits agree and the strings do not, that is a
 * platform formatting difference and a real finding -- separable from a math difference, which
 * matters because the two want completely different fixes.
 *
 * ⚠ THE SLOT NAMES HERE ARE FIXTURE DATA, NOT THE APP'S. The recipe's logic depends only on
 * distinctness and ORDER, never on what the slot strings are. The real VectorTiles.SLOT /
 * ElevationTiles.SLOT constants are already pinned by G1c (GtMapSpec.VEC_SLOT), so re-pinning them
 * here would need the G1c asset-carrying trick for no gain.
 *
 * ⚠ WHAT THIS CERT DOES NOT COVER: deriveRecipe (the quadtree) and storeExists are INJECTED, so
 * both sides receive identical synthetic GtRecipeResults. That is the narrow-service scope working
 * as designed -- and it is why the quadtree must be certified with pass 3, not assumed here.
 */
object Cert17maprecipe {

    /** The pinned clock -- the GtHkFixture convention. Resolves to 2025-11-01 local. */
    const val NOW: Long = 1_762_000_000_000L
    /**
     * PINNEDLOCAL-2026-11-14: the UTC breakdown of [NOW], as ONE constant both devices use.
     * java.time does not exist on iOS, so the iPad cannot derive it the way the Droid does, and
     * hardcoding it in two files is the drift trap. The Droid harness asserts this really is NOW's
     * UTC breakdown, so a change to NOW fails loudly rather than diverging quietly.
     */
    val PINNED_LOCAL: GtLocalTime = GtLocalTime(2025, 11, 1, 12, 26, 40)

    const val VEC = "CERTVEC"
    const val DEM = "CERTDEM"
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    data class Fx(
        val name: String,
        val slots: List<Triple<String, String, String>>,
        val stores: Set<String>,
        val recipes: Map<String, GtRecipeResult?>,
        /** name to lastModified; the name may be [TODAY_FILE] or [GtMapRecipe.CONVERSION]. */
        val seed: List<Pair<String, Long>>,
        val busy: Int,
    )

    /** One side's flattened answer. Strings only, so there is nothing to compare but decisions. */
    data class Out(
        val ret: String,
        val version: String,
        val created: String,
        val slotCount: String,
        val slots: List<String>,
        val boxes: List<String>,
        val files: String,
    )

    interface Side {
        /** Wipe this side's recipe dir, lay down [fx]'s seed files, run writeDaily/writeFresh, flatten. */
        fun run(fx: Fx, fresh: Boolean): Out
    }

    // ---------------------------------------------------------------- THE ONE FORMATTER
    /**
     * Both sides hand plain scalars to these. A compared key can then only differ because the
     * service differed -- never because two flatteners did.
     */
    fun slotLine(slot: String, source: String, zMin: Int, zMax: Int, tiles: Long, boxCount: Int): String =
        "$slot|$source|$zMin|$zMax|$tiles|$boxCount"

    /** A box: every coordinate as BITS and as TEXT. See the class note. */
    fun boxLine(n: Double, s: Double, w: Double, e: Double, tiles: Long): String =
        "bits=" + n.toRawBits() + "," + s.toRawBits() + "," + w.toRawBits() + "," + e.toRawBits() +
        "|text=" + n + "," + s + "," + w + "," + e + "|tiles=" + tiles

    private fun r(slot: String, refZ: Int, levels: List<Int>, boxes: List<LongArray>, total: Long) =
        GtRecipeResult(slot, refZ, levels, boxes, 0, total)

    /** The day the pinned clock falls on, and today's filename -- DERIVED, never typed. */
    fun todayFile(today: String): String = GtMapRecipe.PREFIX + today + GtMapRecipe.EXT

    private val BOX = listOf(longArrayOf(100, 103, 200, 204, 20), longArrayOf(0, 0, 0, 0, 1),
                             longArrayOf(8191, 8191, 8191, 8191, 1))

    /**
     * ⛔ Fixture names are keys. Never reorder or rename -- a golden run is keyed on them.
     * [todayName] is passed in because only the caller knows what the pinned clock resolved to.
     */
    fun fixtures(todayName: String): List<Fx> {
        val todayFile = todayFile(todayName)
        val conv = GtMapRecipe.CONVERSION
        return listOf(
            Fx("happy-two-slots", listOf(Triple("SAT", "Satellite", "sat_id")), setOf("SAT", VEC, DEM),
                mapOf("SAT" to r("SAT", 13, listOf(12, 13, 14), BOX, 22),
                      VEC to r(VEC, 13, listOf(9, 18), listOf(longArrayOf(1, 2, 3, 4, 5)), 5),
                      DEM to r(DEM, 13, listOf(9, 12), listOf(longArrayOf(0, 1, 0, 1, 4)), 4)), emptyList(), 0),
            Fx("no-slot-sources", emptyList(), setOf(VEC, DEM),
                mapOf(VEC to r(VEC, 13, listOf(9, 18), listOf(longArrayOf(1, 2, 3, 4, 5)), 5),
                      DEM to r(DEM, 13, listOf(9, 12), listOf(longArrayOf(0, 1, 0, 1, 4)), 4)), emptyList(), 0),
            Fx("nothing-stored", emptyList(), emptySet(), emptyMap(), emptyList(), 0),
            Fx("store-but-no-recipe", emptyList(), setOf(VEC), mapOf(VEC to null), emptyList(), 0),
            Fx("already-written-today", emptyList(), setOf(VEC),
                mapOf(VEC to r(VEC, 13, listOf(9, 18), listOf(longArrayOf(1, 2, 3, 4, 5)), 5)),
                listOf(todayFile to NOW), 0),
            Fx("duplicate-vec-in-slots", listOf(Triple(VEC, "Vector", "v")), setOf(VEC),
                mapOf(VEC to r(VEC, 13, listOf(9, 18), listOf(longArrayOf(1, 2, 3, 4, 5)), 5)), emptyList(), 0),
            Fx("conversion-busy", emptyList(), setOf(VEC),
                mapOf(VEC to r(VEC, 13, listOf(9, 18), listOf(longArrayOf(1, 2, 3, 4, 5)), 5)),
                listOf(conv to NOW), 2),
            Fx("conversion-idle", emptyList(), setOf(VEC),
                mapOf(VEC to r(VEC, 13, listOf(9, 18), listOf(longArrayOf(1, 2, 3, 4, 5)), 5)),
                listOf(conv to NOW), 0),
            Fx("prune-old", emptyList(), setOf(VEC),
                mapOf(VEC to r(VEC, 13, listOf(9, 18), listOf(longArrayOf(1, 2, 3, 4, 5)), 5)),
                listOf((GtMapRecipe.PREFIX + "2025-01-01" + GtMapRecipe.EXT) to (NOW - 61L * DAY_MS),
                       (GtMapRecipe.PREFIX + "2025-10-01" + GtMapRecipe.EXT) to (NOW - 10L * DAY_MS),
                       "unrelated.txt" to 0L), 0),
            Fx("single-level", emptyList(), setOf(VEC),
                mapOf(VEC to r(VEC, 0, listOf(7), listOf(longArrayOf(0, 0, 0, 0, 1)), 1)), emptyList(), 0),
            Fx("deep-refz", emptyList(), setOf(VEC),
                mapOf(VEC to r(VEC, 18, listOf(18), listOf(longArrayOf(262143, 262143, 262143, 262143, 1)), 1)), emptyList(), 0),
            Fx("empty-boxes", emptyList(), setOf(VEC),
                mapOf(VEC to r(VEC, 13, listOf(9), emptyList(), 0)), emptyList(), 0),
        )
    }

    /** No EXPECTED divergence is predicted: nothing here parses hand-written JSON. */
    val EXPECTED_DIFF: Map<String, String> = emptyMap()

    // ---------------------------------------------------------------- THE ONE NEW SIDE
    /**
     * FIXTURETIMES-2026-11-14: a GtFiles whose lastModified() comes from the FIXTURE, not the filesystem.
     *
     * \u26d4 WHY THIS EXISTS: prune() compares lastModified against a cutoff, so an mtime is an
     * INPUT to the service under test. Seeding it by calling setLastModified worked on the Droid and
     * silently did nothing on the iPad (okio has no setter there), so the two devices ran prune on
     * different inputs and the listing DIFFed -- a platform asymmetry reading as a port defect.
     * Supplying the mtime through the socket removes the asymmetry instead of papering over it, and
     * makes the fixture exact rather than dependent on a filesystem honouring a write.
     */
    fun overlay(base: GtFiles, seeded: Map<String, Long>): GtFiles = object : GtFiles {
        override val fs = base.fs
        override fun setLastModified(path: Path, epochMs: Long): Boolean = true   // the overlay IS the answer
        override fun lastModified(path: Path): Long = seeded[path.name] ?: base.lastModified(path)
        override fun list(dir: Path): List<Path> = base.list(dir)
    }

    /** The env a fixture becomes. Everything deterministic; nothing reaches the app. */
    class FxEnv(private val fx: Fx) : GtRecipeEnv {
        override fun slotSources() = fx.slots
        override fun storeExists(slot: String) = slot in fx.stores
        override fun deriveRecipe(slot: String) = fx.recipes[slot]
        override fun vectorBusyCount() = fx.busy
        override val vecSlot = VEC
        override val demSlot = DEM
    }

    /** NEW, in COMMON code, so the Droid and the iPad run the same bytes. */
    fun newSide(files: GtFiles, paths: GtImportPaths, clock: GtClock): Side = object : Side {
        override fun run(fx: Fx, fresh: Boolean): Out {
            val dir = paths.dir("recipes")
            try { files.fs.deleteRecursively(dir) } catch (e: Exception) { }
            files.fs.createDirectories(dir)
            for ((name, ms) in fx.seed) {
                files.fs.write(dir / name) { writeUtf8("{}") }
            }
            // FIXTURETIMES-2026-11-14: the fixture's own mtimes, identical on both devices
            val fxFiles = overlay(files, fx.seed.toMap())
            val store = GtMapRecipe(fxFiles, paths, clock, FxEnv(fx))
            val ret = if (fresh) store.writeFresh() else store.writeDaily()
            // READ IT BACK WITH THIS SIDE'S OWN LIBRARY (kotlinx here, org.json on the Droid's OLD
            // side) and flatten through the SHARED formatters. The parse must differ -- that is the
            // whole point; the FORMATTING must not.
            val text = ret?.let { try { fxFiles.fs.read(it) { readUtf8() } } catch (e: Exception) { null } }
            var version = "null"; var created = "null"; var slotCount = "null"
            val slots = ArrayList<String>(); val boxes = ArrayList<String>()
            if (text != null) try {
                val root = Json.parseToJsonElement(text).jsonObject
                version = root["version"]?.jsonPrimitive?.content ?: "null"
                created = root["created"]?.jsonPrimitive?.content ?: "null"
                val arr = root["slots"]?.jsonArray ?: JsonArray(emptyList())
                slotCount = arr.size.toString()
                for ((i, el) in arr.withIndex()) {
                    val o = el.jsonObject
                    val bx = o["boxes"]?.jsonArray ?: JsonArray(emptyList())
                    slots.add(slotLine(
                        o["slot"]?.jsonPrimitive?.content ?: "",
                        o["source"]?.jsonPrimitive?.content ?: "",
                        o["zMin"]?.jsonPrimitive?.content?.toInt() ?: -1,
                        o["zMax"]?.jsonPrimitive?.content?.toInt() ?: -1,
                        o["tiles"]?.jsonPrimitive?.content?.toLong() ?: -1L,
                        bx.size))
                    for ((j, be) in bx.withIndex()) {
                        val b = be.jsonObject
                        boxes.add("$i.$j=" + boxLine(
                            b["n"]!!.jsonPrimitive.content.toDouble(), b["s"]!!.jsonPrimitive.content.toDouble(),
                            b["w"]!!.jsonPrimitive.content.toDouble(), b["e"]!!.jsonPrimitive.content.toDouble(),
                            b["tiles"]!!.jsonPrimitive.content.toLong()))
                    }
                }
            } catch (e: Exception) { version = "PARSE-FAILED" }
            val listing = try { fxFiles.list(dir).map { it.name }.sorted().joinToString(",") } catch (e: Exception) { "?" }
            return Out(ret?.name ?: "null", version, created, slotCount, slots, boxes, listing)
        }
    }

    private fun emit(sink: (String, String) -> Unit, key: String, o: Out) {
        sink("$key|ret", o.ret)
        sink("$key|version", o.version)
        sink("$key|created", o.created)
        sink("$key|slotCount", o.slotCount)
        for ((i, s) in o.slots.withIndex()) sink("$key|slot$i", s)
        for ((i, b) in o.boxes.withIndex()) sink("$key|box$i", b)
        sink("$key|files", o.files)
    }

    fun run(side: Side, todayName: String, sink: (String, String) -> Unit) {
        sink("CONST|prefix", GtMapRecipe.PREFIX)
        sink("CONST|ext", GtMapRecipe.EXT)
        sink("CONST|keepDays", GtMapRecipe.KEEP_DAYS.toString())
        sink("CONST|conversion", GtMapRecipe.CONVERSION)
        sink("CONST|today", todayName)
        for (fx in fixtures(todayName)) {
            emit(sink, "DAILY|${fx.name}", side.run(fx, false))
            emit(sink, "FRESH|${fx.name}", side.run(fx, true))
        }
    }
}
