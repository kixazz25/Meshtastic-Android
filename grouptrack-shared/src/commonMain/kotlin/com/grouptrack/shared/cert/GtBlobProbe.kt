package com.grouptrack.shared.cert

import com.grouptrack.shared.db.gtOpenDatabase

/**
 * BLOBPROBE-2026-11-14 !! TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released).
 *
 * WHAT IT ANSWERS, AND WHY BEFORE PASS 3: MBTilesStore is 18 live members over SQLite, and
 * readTile / insertTile are ENTIRELY blob I/O on the hottest path in the app. Measured 11-14:
 * NO blob ever crosses the shared SQLite driver anywhere in this project yet. SQLite is a certified
 * seam, but a BLOB COLUMN on it is unproven -- and the two platforms do not even run the same
 * SQLite (Android = AndroidSQLiteDriver, the OS's own; iPad = BundledSQLiteDriver, a fixed copy).
 *
 * Rather than discover that after building a 20-member harness, this probes it in one cheap run.
 * Every value is a decision the driver makes, nothing is sampled.
 *
 * !! PRAGMA page_size / freelist_count are PHYSICAL FILE PROPERTIES and depend on which SQLite
 * created the file. They are reported here precisely so that if they differ across devices we know
 * that BEFORE reclaimableBytes is certified, and can record it as EXPECTED with a reason instead of
 * mistaking a platform fact for a port defect.
 */
object GtBlobProbe {

    private fun hex(b: ByteArray): String = b.joinToString("") {
        val v = it.toInt() and 0xff
        val d = "0123456789abcdef"
        "" + d[v shr 4] + d[v and 0xf]
    }

    /** The blobs that matter: empty, one byte, an embedded NUL, a 0xff run, and a gzip magic head. */
    private fun cases(): List<Pair<String, ByteArray>> = listOf(
        "empty" to ByteArray(0),
        "one-zero" to byteArrayOf(0),
        "embedded-nul" to byteArrayOf(1, 0, 2, 0, 3),
        "high-bytes" to byteArrayOf(-1, -2, -1, 0, 127, -128),
        "gzip-magic" to byteArrayOf(0x1f, 0x8b.toByte(), 8, 0),
        // 1 KB, deterministic -- a real vector tile is a few KB of gzipped protobuf
        "kb-deterministic" to ByteArray(1024) { (it * 31 % 251).toByte() },
    )

    /**
     * [dbPath] must be a path this process can create. Writes nothing else and reads nothing else.
     * Emits key -> value pairs; the caller compares them across devices.
     */
    fun run(dbPath: String, sink: (String, String) -> Unit) {
        val c = try { gtOpenDatabase(dbPath, false) } catch (e: Exception) {
            sink("OPEN", "THREW ${e::class.simpleName}: ${e.message}"); return
        }
        fun exec(sql: String) { val st = c.prepare(sql); try { st.step() } finally { st.close() } }
        fun one(sql: String): String {
            val st = c.prepare(sql)
            // SQLite is loosely typed, but do not ASSUME getText coerces an integer -- the pragmas
            // return integers and sqlite_version() returns text.
            return try {
                if (!st.step()) "(no row)"
                else try { st.getText(0) } catch (e: Exception) { st.getLong(0).toString() }
            } catch (e: Exception) { "THREW ${e.message}" } finally { st.close() }
        }

        sink("sqlite_version", one("SELECT sqlite_version()"))

        // the real MBTiles schema, as MBTilesStore.initSchema writes it
        exec("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
        exec("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
        exec("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles(zoom_level, tile_column, tile_row)")
        sink("schema", "created")

        // ---- 1. THE BLOB ROUND TRIP. The one thing pass 3 cannot proceed without.
        for ((i, kv) in cases().withIndex()) {
            val (name, bytes) = kv
            try {
                val ins = c.prepare("INSERT OR REPLACE INTO tiles(zoom_level,tile_column,tile_row,tile_data) VALUES(?,?,?,?)")
                try {
                    ins.bindLong(1, 10L); ins.bindLong(2, i.toLong()); ins.bindLong(3, 0L); ins.bindBlob(4, bytes)
                    ins.step()
                } finally { ins.close() }
                val sel = c.prepare("SELECT tile_data FROM tiles WHERE zoom_level=10 AND tile_column=$i AND tile_row=0 LIMIT 1")
                val back = try { if (sel.step()) sel.getBlob(0) else null } finally { sel.close() }
                sink("blob|$name|size", (back?.size ?: -1).toString())
                sink("blob|$name|hex", if (back == null) "(null)" else hex(back))
                sink("blob|$name|roundtrip", if (back != null && back.contentEquals(bytes)) "IDENTICAL" else "** DIFFERS **")
            } catch (e: Exception) {
                sink("blob|$name|roundtrip", "THREW ${e::class.simpleName}: ${e.message}")
            }
        }

        // ---- 2. INSERT OR REPLACE really replaces (the unique index doing its job)
        try {
            val ins = c.prepare("INSERT OR REPLACE INTO tiles(zoom_level,tile_column,tile_row,tile_data) VALUES(?,?,?,?)")
            try { ins.bindLong(1, 10L); ins.bindLong(2, 0L); ins.bindLong(3, 0L); ins.bindBlob(4, byteArrayOf(9, 9)); ins.step() } finally { ins.close() }
            sink("replace|count-at-z10", one("SELECT COUNT(*) FROM tiles WHERE zoom_level=10"))
            val sel = c.prepare("SELECT tile_data FROM tiles WHERE zoom_level=10 AND tile_column=0 AND tile_row=0")
            val back = try { if (sel.step()) sel.getBlob(0) else null } finally { sel.close() }
            sink("replace|new-value", if (back == null) "(null)" else hex(back))
        } catch (e: Exception) { sink("replace|result", "THREW ${e.message}") }

        // ---- 3. the SQL MBTilesStore uses that is not plain SELECT: >> and GROUP BY ordinals
        try {
            for (z in listOf(12, 12, 12, 13)) {
                val ins = c.prepare("INSERT OR REPLACE INTO tiles(zoom_level,tile_column,tile_row,tile_data) VALUES(?,?,?,?)")
                try { ins.bindLong(1, z.toLong()); ins.bindLong(2, (z * 4 + 1).toLong()); ins.bindLong(3, (z * 4 + 2).toLong()); ins.bindBlob(4, byteArrayOf(1)); ins.step() } finally { ins.close() }
            }
            val st = c.prepare("SELECT tile_column >> 2, tile_row >> 2, COUNT(*) FROM tiles WHERE zoom_level=12 GROUP BY 1, 2")
            val rows = StringBuilder()
            try { while (st.step()) rows.append(st.getLong(0)).append('/').append(st.getLong(1)).append('=').append(st.getLong(2)).append(';') }
            finally { st.close() }
            sink("shift-groupby", if (rows.isEmpty()) "(no rows)" else rows.toString())
            sink("between", one("SELECT COUNT(*) FROM tiles WHERE zoom_level=12 AND tile_column BETWEEN 0 AND 999 AND tile_row BETWEEN 0 AND 999"))
            sink("minmax", one("SELECT MIN(tile_column) || ',' || MAX(tile_column) FROM tiles WHERE zoom_level=12"))
            val dz = c.prepare("SELECT DISTINCT zoom_level FROM tiles ORDER BY zoom_level")
            val zs = StringBuilder(); try { while (dz.step()) zs.append(dz.getLong(0)).append(',') } finally { dz.close() }
            sink("distinct-zooms", zs.toString())
        } catch (e: Exception) { sink("sql-features", "THREW ${e.message}") }

        // ---- 4. the pragmas reclaimableBytes depends on. PHYSICAL -- may legitimately differ.
        for (p in listOf("page_size", "freelist_count", "page_count", "auto_vacuum", "encoding")) {
            sink("pragma|$p", one("PRAGMA $p"))
        }

        // ---- 5. SAFETYPRAGMAS-2026-11-14: THE THREE SAFETY PRAGMAS MBTilesStore SETS.
        // Each in its OWN try, as 2.7e does -- that structure exists because an earlier attempt used
        // execSQL for two of the three and set NOTHING on device: the first threw and took the other
        // two down with it. Record what SQLite returns when SETTING, and then whether it STUCK.
        for (p in listOf("secure_delete=OFF", "synchronous=NORMAL", "journal_mode=TRUNCATE")) {
            val name = p.substringBefore('=')
            try {
                // the set. Some of these return a row with the new value, some return none at all.
                sink("set|$p", one("PRAGMA $p"))
            } catch (e: Exception) {
                sink("set|$p", "THREW ${e::class.simpleName}: ${e.message}")
            }
            try {
                // \u26d4 THE ONE THAT MATTERS: did it stick? A pragma that silently did not take is
                // exactly the failure 2.7e's comment describes, and it is invisible without this read.
                sink("stuck|$name", one("PRAGMA $name"))
            } catch (e: Exception) {
                sink("stuck|$name", "THREW ${e::class.simpleName}: ${e.message}")
            }
        }

        try { c.close() } catch (e: Exception) { }
        sink("DONE", "ok")
    }
}
