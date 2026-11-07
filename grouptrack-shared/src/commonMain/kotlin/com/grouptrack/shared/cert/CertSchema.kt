package com.grouptrack.shared.cert

import androidx.sqlite.SQLiteConnection

/**
 * STEP12-2026-11-07: a database's STRUCTURE as comparable lines -- every table's columns (order, type, not-null, default, key) and
 * every index (table, unique, partial, columns). Android's own android_metadata and SQLite's internal tables are left out.
 * Used to prove the shared GtSchema builds exactly what Droid 1 has, and that running it on a 2.7e database changes nothing. TEST-ONLY.
 */
/** positions: true = column order counts (UPGRADE/AGAIN: the same database before and after).
 *  false = column order ignored (FRESH: Droid 1's rides got distributed_at/status by the Sept ALTERs, so they sit last;
 *  a fresh install creates them in file order. Nothing reads rides by position -- every INSERT names its columns). */
object CertSchema {
    fun dump(c: SQLiteConnection, positions: Boolean): List<String> {
        val out = ArrayList<String>()
        val tables = ArrayList<String>()
        val st = c.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata' ORDER BY name")
        try { while (st.step()) tables.add(st.getText(0)) } finally { st.close() }
        for (t in tables) {
            val ti = c.prepare("PRAGMA table_info('$t')")
            try { while (ti.step()) out.add("T|$t|${if (positions) ti.getLong(0).toString() else "-"}|${ti.getText(1)}|${txt(ti, 2)}|${ti.getLong(3)}|${txt(ti, 4)}|${ti.getLong(5)}") } finally { ti.close() }
            val idx = ArrayList<String>()
            val il = c.prepare("PRAGMA index_list('$t')")
            try { while (il.step()) idx.add(il.getText(1) + "|" + il.getLong(2) + "|" + txt(il, 3) + "|" + txt(il, 4)) } finally { il.close() }
            for (i in idx.sorted()) {
                val name = i.substringBefore('|')
                val cols = ArrayList<String>()
                val ii = c.prepare("PRAGMA index_info('$name')")
                try { while (ii.step()) cols.add(txt(ii, 2)) } finally { ii.close() }
                out.add("I|$t|${if (name.startsWith("sqlite_autoindex")) "auto" else name}|${i.substringAfter('|')}|${cols.joinToString(",")}")
            }
        }
        return out.sorted()
    }

    /** Row count of every table -- the "no data lost" check. */
    fun counts(c: SQLiteConnection): Map<String, Long> {
        val tables = ArrayList<String>()
        val st = c.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        try { while (st.step()) tables.add(st.getText(0)) } finally { st.close() }
        return tables.associateWith { t -> val q = c.prepare("SELECT count(*) FROM \"$t\""); try { if (q.step()) q.getLong(0) else -1L } finally { q.close() } }
    }

    private fun txt(s: androidx.sqlite.SQLiteStatement, i: Int) = if (s.isNull(i)) "-" else s.getText(i)
}
