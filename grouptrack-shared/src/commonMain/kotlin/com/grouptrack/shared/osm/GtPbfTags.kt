package com.grouptrack.shared.osm

import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.log.gtLogger
import androidx.sqlite.SQLiteConnection
import okio.Buffer
import okio.EOFException
import okio.FileSystem
import okio.Inflater
import okio.InflaterSource
import okio.Path
import okio.buffer
import okio.use

/**
 * STEP15G-2026-11-10 (2.8 step 15g): 2.7e's OsmPbfTagReader (PBFTAGS-2026-08-30), PORTED AS IS -- a Geofabrik .osm.pbf -> the interim
 * tag table osm_way_tags (osm_id + the 14 access tags Geofabrik's GeoPackage drops), ways in the widened net only.
 * The hand-written protobuf walk is 2.7e's, step for step: per-block string table, PACKED keys/vals, ways only, the commit cadence
 * (`rows > 0 && rows % 5000 < 1000` after a block), the progress call at each commit, the index at the end.
 * WHAT CHANGED, and only this:
 *  - java.io.DataInputStream(BufferedInputStream(64 KB)) -> okio's buffered source: readInt is big-endian in both, EOF at a header ->
 *    EOFException -> the walk ends; EOF inside a header or a body (readFully) -> EOFException -> the outer catch (as 2.7e).
 *  - java.util.zip.Inflater (zlib, nowrap false) -> okio's InflaterSource over okio's Inflater() (zlib, nowrap false), read up to the
 *    same cap 2.7e allocated (raw_size, or 32 MB with no hint) and no further.
 *  - SQLiteDatabase.openOrCreateDatabase + compileStatement / beginTransaction / setTransactionSuccessful / endTransaction ->
 *    gtOpenDatabase + one prepared statement / BEGIN / COMMIT / ROLLBACK.
 *  - String(bytes, UTF_8) -> decodeToString; File -> okio [fs]; Log -> gtLogger (same tag).
 * KNOWN, kept as 2.7e (port as is): an implausible BlobHeader length or a missing datasize returns -1 WITHOUT deleting the table (the
 *   batches already committed stay; stageOf's verifyTags would then pass a partial table -- 2.7e's behaviour, for the owner's list);
 *   a body that will not inflate is skipped (logged), not fatal.
 * KNOWN, platform: a TRUNCATED zlib stream -- Java's loop returns the bytes it managed (needsInput -> break); okio throws (EOF) and the
 *   block is skipped as "would not inflate". Only a corrupt file reaches either; verifyPbf rejects truncated downloads first.
 *   Malformed UTF-8 in a string table: both put U+FFFD; the number of U+FFFD per bad sequence may differ (Geofabrik's data is UTF-8).
 */
object GtPbfTags {

    private val log = gtLogger("OsmPbfTags")

    /** Must match GtOsmStage.verifyTags, which counts rows in this table. */
    const val TABLE = "osm_way_tags"

    val WIDE = setOf(
        "track", "unclassified", "residential", "service", "path",
        "tertiary", "cycleway", "bridleway", "byway", "road",
    )

    /** ⚠ Column order here IS the insert order below -- change both together. */
    private val KEEP = listOf(
        "highway", "surface", "tracktype", "smoothness", "access",
        "motor_vehicle", "4wd_only", "ohv", "atv", "motorcar",
        "vehicle", "foot", "bicycle", "horse",
    )

    private fun columnFor(tag: String) = when (tag) {
        "4wd_only" -> "four_wd_only"
        else -> tag
    }

    private const val BATCH = 5_000

    private fun exec(db: SQLiteConnection, sql: String) { val st = db.prepare(sql); try { st.step() } finally { st.close() } }

    /**
     * Read [pbf] and write [out]. Returns the row count, or -1 on failure. [onProgress] (waysWalked, rowsWritten) at each commit.
     * ⛔ [out] is DELETED first if present (2.7e: a half-written tag table must not survive).
     * [clockMs] = System.currentTimeMillis() (the log's elapsed time only).
     */
    fun build(fs: FileSystem, pbf: Path, out: Path, clockMs: () -> Long, onProgress: ((Long, Int) -> Unit)? = null): Int {
        if (!fs.exists(pbf)) {
            log.e { "pbf missing: $pbf" }
            return -1
        }
        if (fs.exists(out)) {
            val deleted = try { fs.delete(out); true } catch (e: Exception) { false }
            if (!deleted) {
                log.e { "could not remove stale tag table: $out" }
                return -1
            }
        }

        var db: SQLiteConnection? = null
        var rows = 0
        var ways = 0L
        val started = clockMs()

        try {
            db = gtOpenDatabase(out.toString(), false)
            val cols = KEEP.joinToString(",") { "${columnFor(it)} TEXT" }
            exec(db, "CREATE TABLE $TABLE (osm_id INTEGER PRIMARY KEY, $cols)")
            val placeholders = (0..KEEP.size).joinToString(",") { "?" }
            val insert = db.prepare("INSERT OR REPLACE INTO $TABLE VALUES ($placeholders)")
            try {
                fs.source(pbf).buffer().use { din ->
                    exec(db, "BEGIN")
                    while (true) {
                        val hdrLen = try {
                            din.readInt()
                        } catch (_: EOFException) {
                            break
                        }
                        if (hdrLen <= 0 || hdrLen > 64 * 1024) {
                            log.e { "implausible BlobHeader length $hdrLen" }
                            exec(db, "ROLLBACK")
                            return -1
                        }
                        val hdr = din.readByteArray(hdrLen.toLong())
                        val (btype, datasize) = parseBlobHeader(hdr)
                        if (datasize == null || datasize <= 0) {
                            log.e { "blob header carried no datasize" }
                            exec(db, "ROLLBACK")
                            return -1
                        }
                        val body = din.readByteArray(datasize.toLong())
                        if (btype != "OSMData") continue

                        val block = inflateBlob(body) ?: run {
                            log.e { "blob would not inflate" }
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
                                    else insert.bindText(i + 2, v)
                                }
                                insert.step()
                                insert.reset()
                                true
                            } else {
                                false
                            }
                        }

                        if (rows > 0 && rows % BATCH < 1_000) {
                            exec(db, "COMMIT")
                            onProgress?.invoke(ways, rows)
                            exec(db, "BEGIN")
                        }
                    }
                    exec(db, "COMMIT")
                }
            } finally {
                insert.close()
            }

            exec(db, "CREATE INDEX idx_way_highway ON $TABLE(highway)")
            val secs = (clockMs() - started) / 1000.0
            log.i { "tag table built: $rows rows from $ways ways in ${secs}s" }
            return rows
        } catch (e: Exception) {
            log.e { "tag pass failed: ${e::class.simpleName} ${e.message}" }
            try { db?.close() } catch (_: Exception) {}
            db = null
            try { fs.delete(out) } catch (_: Exception) {}
            return -1
        } finally {
            try { db?.close() } catch (_: Exception) {}
        }
    }

    // ── protobuf primitives (2.7e's, unchanged) ─────────────────────────

    private class Cursor(val buf: ByteArray, var pos: Int, val end: Int)

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

    private fun tag(c: Cursor, wireOut: IntArray): Int {
        val key = varint(c)
        wireOut[0] = (key and 0x07L).toInt()
        return (key ushr 3).toInt()
    }

    private fun skip(c: Cursor, wire: Int) {
        when (wire) {
            0 -> varint(c)
            1 -> c.pos += 8
            2 -> { val n = varint(c).toInt(); c.pos += n }
            5 -> c.pos += 4
            else -> throw IllegalStateException("unknown wire type $wire at ${c.pos}")
        }
    }

    private fun packed(c: Cursor, end: Int): IntArray {
        val out = ArrayList<Int>(16)
        while (c.pos < end) out.add(varint(c).toInt())
        return out.toIntArray()
    }

    /** 2.7e: String(buf, off, n, UTF_8) -- throws IndexOutOfBounds when off + n runs past the array, as Java's constructor does. */
    private fun utf8(buf: ByteArray, off: Int, n: Int): String {
        if (off < 0 || n < 0 || off > buf.size - n) throw IndexOutOfBoundsException("offset $off, count $n, length ${buf.size}")
        return buf.decodeToString(off, off + n)
    }

    private fun parseBlobHeader(buf: ByteArray): Pair<String?, Int?> {
        val c = Cursor(buf, 0, buf.size)
        val w = IntArray(1)
        var type: String? = null
        var datasize: Int? = null
        while (c.pos < c.end) {
            val f = tag(c, w)
            if (f == 1 && w[0] == 2) {
                val n = varint(c).toInt()
                type = utf8(buf, c.pos, n)
                c.pos += n
            } else if (f == 3 && w[0] == 0) {
                datasize = varint(c).toInt()
            } else {
                skip(c, w[0])
            }
        }
        return type to datasize
    }

    /** Blob: field 1 = raw, field 3 = zlib_data. 2.7e's copyOfRange bounds (IndexOutOfBounds / IllegalArgument) are copyOfRange's here too. */
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
                rawSize = varint(c).toInt()
            } else {
                skip(c, w[0])
            }
        }
        raw?.let { return it }
        val zd = z ?: return null
        val cap = (if (rawSize > 0) rawSize else 32 * 1024 * 1024).toLong()
        return try {
            val outBuf = Buffer()
            InflaterSource(Buffer().write(zd), Inflater()).buffer().use { src ->
                while (outBuf.size < cap) {
                    if (src.read(outBuf, cap - outBuf.size) == -1L) break
                }
            }
            outBuf.readByteArray()
        } catch (e: Exception) {
            log.e { "inflate failed: ${e.message}" }
            null
        }
    }

    private fun parsePrimitiveBlock(buf: ByteArray, onWay: (Long, Map<String, String>) -> Boolean): Int {
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
                out.add(utf8(buf, c.pos, n))
                c.pos += n
            } else {
                skip(c, w[0])
            }
        }
        return out.toTypedArray()
    }

    private fun parseGroup(buf: ByteArray, from: Int, to: Int, table: Array<String>, onWay: (Long, Map<String, String>) -> Boolean): Int {
        val c = Cursor(buf, from, to)
        val w = IntArray(1)
        var written = 0
        while (c.pos < c.end) {
            val f = tag(c, w)
            if (f == 3 && w[0] == 2) {
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
                        else -> skip(wc, ww[0])
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
                skip(c, w[0])
            }
        }
        return written
    }
}
