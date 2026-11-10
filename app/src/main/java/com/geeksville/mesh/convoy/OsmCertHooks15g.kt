package com.geeksville.mesh.convoy

import java.io.File

/**
 * STEP15G-2026-11-10 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the hooks the generated *CertOld copies
 * of 2.7e's OSM files call (make_old_copies_15g.py). Set by GtCert15g for the OLD run and cleared in its finally; unset, every hook is
 * the app's own behaviour. [root] set = a cert run: the osm root, nothing outside it touched (sweeps recorded in [rec], fixed clocks).
 */
object OsmCertHooks15g {
    @Volatile var root: File? = null
    @Volatile var config: File? = null
    @Volatile var rec: MutableList<String>? = null
    fun ms(): Long = if (root != null) 0L else System.currentTimeMillis()
    fun date(): java.util.Date = if (root != null) java.util.GregorianCalendar(2026, 10, 1, 12, 0, 0).time else java.util.Date()
    fun ldt(): java.time.LocalDateTime = if (root != null) java.time.LocalDateTime.of(2026, 11, 1, 12, 0, 0) else java.time.LocalDateTime.now()
    /** D16 (Fred 11-10): the published skinny's rows over [tables], counted by SQL (as GtOsmExtract.skinnyRows); any failure -1. */
    fun skinnyRows(f: File, tables: List<String>): Long = try {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(f.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        try { var n = 0L; for (t in tables) db.rawQuery("SELECT COUNT(*) FROM " + t, null).use { c -> if (c.moveToFirst()) n += c.getLong(0) }; n } finally { db.close() }
    } catch (e: Exception) { -1L }
}
