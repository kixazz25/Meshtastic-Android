package com.geeksville.mesh.convoy

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.Inflater

/**
 * OsmPbfTagReader — reads a Geofabrik .osm.pbf and writes the interim tag
 * table the classifier needs. PBFTAGS-2026-08-30.
 *
 * WHY THIS EXISTS. Geofabrik's GeoPackage is shapefile-derived and flattens
 * OSM's tag model: `ohv`, `motor_vehicle`, `access` and `4wd_only` are dropped
 * at export, and the roads layer has NO access field of any kind. So `fclass`
 * answers by SHAPE, not by permission — which produces both faults seen on the
 * ground: community roads arriving as trails, and rideable dirt roads not
 * arriving at all. The PBF carries what separates them.
 *
 * WHY HAND-WRITTEN. `core/proto` applies Wire, but it is Meshtastic's module
 * with `root("meshtastic.*")` pruning and publishing config — OSM protos in it
 * would not generate without editing upstream's build file, and a new module
 * puts a build change between us and a working import. We need ONE message's
 * worth of fields, so we read them directly.
 *
 * ⭐⭐ THE ALGORITHM BELOW IS PROVEN, NOT DRAFTED. It was written in Python
 * first (`pbf_handparse_validate_2026-08-30_v1.py`) and run against
 * utah-260828.osm.pbf with no OSM library. It reported 701,334 ways carrying a
 * highway tag and 87,079 taken by today's filter — matching the osmium survey
 * and the classifier ledger EXACTLY. `access private=34511` matched to the
 * digit, and `ohv` came back carrying real multi-clause values such as
 * `no @ (>48" wide);designated (48" wide or less)`, which a mis-indexed string
 * table cannot produce. This file is a transliteration of that run.
 *
 * ⚠ TWO THINGS IN THE FORMAT FAIL SILENTLY IF READ WRONG:
 *   THE STRING TABLE is PER BLOCK, and a way's keys/vals are INDICES into it.
 *   Carry a table across blocks and you get real tag names on the wrong ways —
 *   plausible output, wrong data, no error.
 *   KEYS AND VALS ARE PACKED (`repeated uint32 [packed=true]`) — one
 *   length-delimited blob of varints, not repeated fields. Read as unpacked
 *   they come back empty and every way looks untagged.
 *
 * ⭐ WAYS ONLY. Geometry comes from the GeoPackage, so nodes and relations are
 * skipped entirely — which is most of the file. Utah: 2,107,222 ways walked,
 * 701,334 with a highway tag. ⚠ And PBF stores ALL NODES FIRST, so this reader
 * finds nothing for most of the file and then everything at the end. That is
 * the layout, not a hang.
 *
 * THE FORMAT, in the order this walks it:
 *   file           repeat { int32 BE header length, BlobHeader, Blob }
 *   BlobHeader     1 = type ("OSMHeader" | "OSMData"), 3 = datasize
 *   Blob           1 = raw bytes, 3 = zlib_data
 *   PrimitiveBlock 1 = stringtable, 2 = repeated PrimitiveGroup
 *   StringTable    1 = repeated bytes (index 0 is always "")
 *   PrimitiveGroup 3 = repeated Way (1 nodes, 2 dense, 4 relations: skipped)
 *   Way            1 = id, 2 = packed keys, 3 = packed vals (8 = refs: skipped)
 */
object OsmPbfTagReader {

    private const val TAG = "OsmPbfTags"

    /** Must match OsmImportStage.verifyTags, which counts rows in this table. */
    const val TABLE = "osm_way_tags"

    /**
     * The widened net — stage one of the two-stage filter. Everything a
     * side-by-side could plausibly be on, to be narrowed by tags afterwards.
     * ⚠ Rows outside it are not written at all: 701k ways become ~425k rows.
     */
    val WIDE = setOf(
        "track", "unclassified", "residential", "service", "path",
        "tertiary", "cycleway", "bridleway", "byway", "road",
    )

    /**
     * The tags Geofabrik drops, which are the whole point of this pass.
     * ⚠ Column order here IS the insert order below — change both together.
     */
    private val KEEP = listOf(
        "highway", "surface", "tracktype", "smoothness", "access",
        "motor_vehicle", "4wd_only", "ohv", "atv", "motorcar",
        "vehicle", "foot", "bicycle", "horse",
    )

    /** SQLite identifiers cannot start with a digit, hence four_wd_only. */
    private fun columnFor(tag: String) = when (tag) {
        "4wd_only" -> "four_wd_only"
        else -> tag
    }

    private const val BATCH = 5_000

    /**
     * Read [pbf] and write [out]. Returns the row count, or -1 on failure.
     *
     * [onProgress] is called with (waysWalked, rowsWritten) roughly every
     * batch, for the download panel's detail line.
     *
     * ⛔ [out] is DELETED first if present. A tag table half-written by a
     * previous run that died is exactly the artifact that must not survive —
     * verifyTags only counts rows, so a partial table would pass and the
     * classifier would type trails from an incomplete tag set and report
     * success. That is the 07-27 failure class.
     */
    fun build(
        pbf: File,
        out: File,
        onProgress: ((Long, Int) -> Unit)? = null,
    ): Int {
        if (!pbf.exists()) {
            Log.e(TAG, "pbf missing: ${pbf.absolutePath}")
            return -1
        }
        if (out.exists() && !out.delete()) {
            Log.e(TAG, "could not remove stale tag table: ${out.absolutePath}")
            return -1
        }

        var db: SQLiteDatabase? = null
        var rows = 0
        var ways = 0L
        val started = System.currentTimeMillis()

        try {
            db = SQLiteDatabase.openOrCreateDatabase(out, null)
            val cols = KEEP.joinToString(",") { "${columnFor(it)} TEXT" }
            db.execSQL("CREATE TABLE $TABLE (osm_id INTEGER PRIMARY KEY, $cols)")
            // ⚠ BOUND PARAMETERS, never concatenation. Real OSM values contain
            // quotes and semicolons — ohv carries
            // `no @ (>48" wide);designated (48" wide or less)`.
            val placeholders = (0..KEEP.size).joinToString(",") { "?" }
            val insert = db.compileStatement(
                "INSERT OR REPLACE INTO $TABLE VALUES ($placeholders)"
            )

            DataInputStream(BufferedInputStream(pbf.inputStream(), 1 shl 16)).use { din ->
                db.beginTransaction()
                while (true) {
                    val hdrLen = try {
                        din.readInt()
                    } catch (_: java.io.EOFException) {
                        break
                    }
                    if (hdrLen <= 0 || hdrLen > 64 * 1024) {
                        Log.e(TAG, "implausible BlobHeader length $hdrLen")
                        db.endTransaction()
                        return -1
                    }
                    val hdr = ByteArray(hdrLen)
                    din.readFully(hdr)
                    val (btype, datasize) = parseBlobHeader(hdr)
                    if (datasize == null || datasize <= 0) {
                        Log.e(TAG, "blob header carried no datasize")
                        db.endTransaction()
                        return -1
                    }
                    val body = ByteArray(datasize)
                    din.readFully(body)
                    if (btype != "OSMData") continue

                    val block = inflateBlob(body) ?: run {
                        Log.e(TAG, "blob would not inflate")
                        null
                    } ?: continue

                    rows += parsePrimitiveBlock(block) { id, tags ->
                        ways++
                        val hw = tags["highway"]
                        if (hw != null && hw in WIDE) {
                            insert.clearBindings()
                            insert.bindLong(1, id)
                            KEEP.forEachIndexed { i, key ->
                                val v = tags[key]
                                if (v == null) insert.bindNull(i + 2)
                                else insert.bindString(i + 2, v)
                            }
                            insert.executeInsert()
                            true
                        } else {
                            false
                        }
                    }

                    if (rows > 0 && rows % BATCH < 1_000) {
                        db.setTransactionSuccessful()
                        db.endTransaction()
                        onProgress?.invoke(ways, rows)
                        db.beginTransaction()
                    }
                }
                db.setTransactionSuccessful()
                db.endTransaction()
            }

            db.execSQL("CREATE INDEX idx_way_highway ON $TABLE(highway)")
            val secs = (System.currentTimeMillis() - started) / 1000.0
            Log.i(TAG, "tag table built: $rows rows from $ways ways in ${"%.1f".format(secs)}s")
            return rows
        } catch (e: Exception) {
            Log.e(TAG, "tag pass failed: ${e.javaClass.simpleName} ${e.message}", e)
            // ⛔ Leave nothing half-written — see the KDoc above.
            try { db?.close() } catch (_: Exception) {}
            out.delete()
            return -1
        } finally {
            try { db?.close() } catch (_: Exception) {}
        }
    }

    // ── protobuf primitives. This is the whole of it. ────────────────────

    private class Cursor(val buf: ByteArray, var pos: Int, val end: Int)

    /** Base-128, little-endian groups, high bit means continue. */
    private fun varint(c: Cursor): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = c.buf[c.pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw IllegalStateException("varint too long at ${c.pos}")
        }
    }

    /** Returns field number; wire type is left in [wireOut]. */
    private fun tag(c: Cursor, wireOut: IntArray): Int {
        val key = varint(c)
        wireOut[0] = (key and 0x07L).toInt()
        return (key ushr 3).toInt()
    }

    /** Skip a field we do not care about. Every message needs this. */
    private fun skip(c: Cursor, wire: Int) {
        when (wire) {
            0 -> varint(c)
            1 -> c.pos += 8
            2 -> { val n = varint(c).toInt(); c.pos += n }
            5 -> c.pos += 4
            else -> throw IllegalStateException("unknown wire type $wire at ${c.pos}")
        }
    }

    /** ⚠ THE ONE THAT FAILS SILENTLY IF READ AS UNPACKED. */
    private fun packed(c: Cursor, end: Int): IntArray {
        val out = ArrayList<Int>(16)
        while (c.pos < end) out.add(varint(c).toInt())
        return out.toIntArray()
    }

    // ── the OSM messages we actually need ────────────────────────────────

    private fun parseBlobHeader(buf: ByteArray): Pair<String?, Int?> {
        val c = Cursor(buf, 0, buf.size)
        val w = IntArray(1)
        var type: String? = null
        var datasize: Int? = null
        while (c.pos < c.end) {
            val f = tag(c, w)
            if (f == 1 && w[0] == 2) {
                val n = varint(c).toInt()
                type = String(buf, c.pos, n, Charsets.UTF_8)
                c.pos += n
            } else if (f == 3 && w[0] == 0) {
                datasize = varint(c).toInt()
            } else {
                skip(c, w[0])
            }
        }
        return type to datasize
    }

    /** Blob: field 1 = raw, field 3 = zlib_data. */
    private fun inflateBlob(buf: ByteArray): ByteArray? {
        val c = Cursor(buf, 0, buf.size)
        val w = IntArray(1)
        var raw: ByteArray? = null
        var z: ByteArray? = null
        var rawSize = 0
        while (c.pos < c.end) {
            val f = tag(c, w)
            if (w[0] == 2) {
                val n = varint(c).toInt()
                when (f) {
                    1 -> raw = buf.copyOfRange(c.pos, c.pos + n)
                    3 -> z = buf.copyOfRange(c.pos, c.pos + n)
                }
                c.pos += n
            } else if (f == 2 && w[0] == 0) {
                rawSize = varint(c).toInt()   // uncompressed size hint
            } else {
                skip(c, w[0])
            }
        }
        raw?.let { return it }
        val zd = z ?: return null
        val inf = Inflater()
        return try {
            inf.setInput(zd)
            val out = ByteArray(if (rawSize > 0) rawSize else 32 * 1024 * 1024)
            var total = 0
            while (!inf.finished() && total < out.size) {
                val n = inf.inflate(out, total, out.size - total)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                total += n
            }
            if (total == out.size) out else out.copyOf(total)
        } catch (e: Exception) {
            Log.e(TAG, "inflate failed: ${e.message}")
            null
        } finally {
            inf.end()
        }
    }

    /**
     * PrimitiveBlock: 1 = stringtable, 2 = repeated PrimitiveGroup.
     * ⚠ THE TABLE IS PER BLOCK. Carrying one across blocks is the bug that
     * produces real tag names on the wrong ways.
     */
    private inline fun parsePrimitiveBlock(
        buf: ByteArray,
        onWay: (Long, Map<String, String>) -> Boolean,
    ): Int {
        val c = Cursor(buf, 0, buf.size)
        val w = IntArray(1)
        var table: Array<String>? = null
        val groups = ArrayList<IntArray>(4)
        while (c.pos < c.end) {
            val f = tag(c, w)
            if (w[0] == 2) {
                val n = varint(c).toInt()
                when (f) {
                    1 -> table = parseStringTable(buf, c.pos, c.pos + n)
                    2 -> groups.add(intArrayOf(c.pos, c.pos + n))
                }
                c.pos += n
            } else {
                skip(c, w[0])
            }
        }
        val t = table ?: return 0
        var written = 0
        for (g in groups) written += parseGroup(buf, g[0], g[1], t, onWay)
        return written
    }

    private fun parseStringTable(buf: ByteArray, from: Int, to: Int): Array<String> {
        val c = Cursor(buf, from, to)
        val w = IntArray(1)
        val out = ArrayList<String>(8_000)
        while (c.pos < c.end) {
            val f = tag(c, w)
            if (f == 1 && w[0] == 2) {
                val n = varint(c).toInt()
                out.add(String(buf, c.pos, n, Charsets.UTF_8))
                c.pos += n
            } else {
                skip(c, w[0])
            }
        }
        return out.toTypedArray()
    }

    private inline fun parseGroup(
        buf: ByteArray,
        from: Int,
        to: Int,
        table: Array<String>,
        onWay: (Long, Map<String, String>) -> Boolean,
    ): Int {
        val c = Cursor(buf, from, to)
        val w = IntArray(1)
        var written = 0
        while (c.pos < c.end) {
            val f = tag(c, w)
            if (f == 3 && w[0] == 2) {          // Way
                val n = varint(c).toInt()
                val end = c.pos + n
                var id = -1L
                var keys: IntArray? = null
                var vals: IntArray? = null
                val wc = Cursor(buf, c.pos, end)
                val ww = IntArray(1)
                while (wc.pos < wc.end) {
                    val wf = tag(wc, ww)
                    when {
                        wf == 1 && ww[0] == 0 -> id = varint(wc)
                        wf == 2 && ww[0] == 2 -> {
                            val ln = varint(wc).toInt()
                            keys = packed(wc, wc.pos + ln)
                        }
                        wf == 3 && ww[0] == 2 -> {
                            val ln = varint(wc).toInt()
                            vals = packed(wc, wc.pos + ln)
                        }
                        else -> skip(wc, ww[0])   // 8 = refs (node ids): skipped
                    }
                }
                c.pos = end
                val k = keys
                val v = vals
                if (id >= 0 && k != null && v != null && k.size == v.size) {
                    val tags = HashMap<String, String>(k.size * 2)
                    for (i in k.indices) {
                        val ki = k[i]
                        val vi = v[i]
                        if (ki < table.size && vi < table.size) {
                            tags[table[ki]] = table[vi]
                        }
                    }
                    if (onWay(id, tags)) written++
                }
            } else {
                skip(c, w[0])                    // 1 nodes, 2 dense, 4 relations
            }
        }
        return written
    }
}
