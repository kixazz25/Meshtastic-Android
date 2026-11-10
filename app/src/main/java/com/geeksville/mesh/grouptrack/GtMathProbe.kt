package com.geeksville.mesh.grouptrack

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.geeksville.mesh.convoy.GroupTrackStorage

/**
 * MATHPROBE-2026-11-10 (TEST-ONLY; Fred 11-10 07:17 "find it", 07:37 "solve the issue"): what ANDROID's java.lang.Math returns for
 * sin / cos / asin / hypot on the inputs GroupTrack's shared code really feeds them -- so the iPad's GtMath can be made to return
 * exactly the same bits. Found 11-10: Apple's cos(0.6808...) = ...5005, Android's = ...5006 (the exact answer); Android runs bionic's
 * libm (FreeBSD msun, compiled for ARM), not Java's fdlibm -- so the iPad port must copy what Android RUNS, proven here, not assumed.
 * Inputs (read only, from the frozen cert copy GroupTrack/cert15c_input/grouptrack_spatial.db): every point of every track and of every
 * 7th trail -> cos(lat in radians); per pair of neighbouring points: sin(dLat/2), sin(dLon/2), asin(sqrt(haversine term)), hypot of
 * the local east/north offsets (as GtRiderScan / GtRoute); plus seeded random inputs (small, large, huge; asin near 1; hypot spread).
 * Each distinct input once. Lines: "C|<x bits>|<cos bits>", "S|...", "A|...", "H|<x bits>,<y bits>|<hypot bits>" (hex, raw bits).
 * Runs when GtCertRun is given mode "mathprobe"; written into the normal cert report (Download/GroupTrack_CERT_droid_<time>.txt).
 * ⚠ TEST-ONLY (CODE RULE 3): remove with GtCert before 2.8 is released.
 */
object GtMathProbe {
    private const val TAG = "CertRun"
    private const val D2R = 0.017453292519943295          // GtJava.toRadians / Android's Math.toRadians constant
    private const val CAP = 1_500_000                      // per function

    private fun h(x: Double) = java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(x))

    fun run(app: Context, w: java.io.Writer) {
        val t0 = System.currentTimeMillis(); var last = t0
        fun progress(msg: String) { val now = System.currentTimeMillis(); if (now - last >= 10_000) { last = now; Log.i(TAG, "GTCERT mathprobe $msg") } }
        val f = java.io.File(GroupTrackStorage.dir("cert15c_input", app), "grouptrack_spatial.db")
        if (!f.isFile) { w.write("# MATHPROBE: STOP -- no input at ${f.path} (gt_cert pushes it for rider15c)\n"); return }
        val cosIn = LinkedHashSet<Long>(); val sinIn = LinkedHashSet<Long>(); val asinIn = LinkedHashSet<Long>()
        val hyIn = LinkedHashSet<String>()
        fun addC(x: Double) { if (cosIn.size < CAP) cosIn.add(java.lang.Double.doubleToRawLongBits(x)) }
        fun addS(x: Double) { if (sinIn.size < CAP) sinIn.add(java.lang.Double.doubleToRawLongBits(x)) }
        fun addA(x: Double) { if (asinIn.size < CAP) asinIn.add(java.lang.Double.doubleToRawLongBits(x)) }
        fun addH(x: Double, y: Double) { if (hyIn.size < CAP) hyIn.add(h(x) + "," + h(y)) }
        val pair = java.util.regex.Pattern.compile("(-?[0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?) (-?[0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)")
        var lines = 0; var points = 0
        fun line(wkt: String) {
            lines++
            val m = pair.matcher(wkt)
            var pLat = Double.NaN; var pLon = Double.NaN
            while (m.find()) {
                val lon = m.group(1)!!.toDouble(); val lat = m.group(2)!!.toDouble(); points++
                addC(lat * D2R)
                if (!pLat.isNaN()) {
                    val dLat = (lat - pLat) * D2R; val dLon = (lon - pLon) * D2R
                    addS(dLat / 2); addS(dLon / 2)
                    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(pLat * D2R) * Math.cos(lat * D2R) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
                    addA(Math.sqrt(a)); addA(Math.min(1.0, Math.sqrt(a)))
                    val kx = 111320.0 * Math.cos(pLat * D2R)
                    addH((lon - pLon) * kx, (lat - pLat) * 111320.0)
                    addH((lon - pLon) * 69.0 * Math.cos(pLat * D2R), (lat - pLat) * 69.0)
                }
                pLat = lat; pLon = lon
            }
            progress("lines $lines points $points cos ${cosIn.size} sin ${sinIn.size} asin ${asinIn.size} hypot ${hyIn.size}")
        }
        val db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT geometry FROM tracks WHERE geometry IS NOT NULL", null).use { c -> while (c.moveToNext()) line(c.getString(0)) }
            db.rawQuery("SELECT geometry FROM trails WHERE geometry IS NOT NULL AND rowid % 7 = 0", null).use { c -> while (c.moveToNext()) line(c.getString(0)) }
        } finally { db.close() }
        val real = "real data: $lines lines, $points points"
        val rnd = java.util.Random(20261110L)
        repeat(200_000) { val x = (rnd.nextDouble() * 8.0) - 4.0; addC(x); addS(x) }
        repeat(50_000) { val x = (rnd.nextDouble() * 2e6) - 1e6; addC(x); addS(x) }
        repeat(20_000) { val x = (rnd.nextDouble() * 2e20) - 1e20; addC(x); addS(x) }
        repeat(100_000) { addA(rnd.nextDouble()) }
        repeat(20_000) { addA(0.97 + rnd.nextDouble() * 0.03) }
        repeat(100_000) { addH(rnd.nextGaussian() * Math.pow(10.0, rnd.nextInt(12) - 4.0), rnd.nextGaussian() * Math.pow(10.0, rnd.nextInt(12) - 4.0)) }
        w.write("# MATHPROBE droid ${android.os.Build.MODEL}: $real; + seeded random (20261110); cos ${cosIn.size}, sin ${sinIn.size}, asin ${asinIn.size}, hypot ${hyIn.size}\n")
        var n = 0
        for (b in cosIn) { val x = java.lang.Double.longBitsToDouble(b); w.write("C|"); w.write(java.lang.Long.toHexString(b)); w.write("|"); w.write(h(Math.cos(x))); w.write("\n"); if (++n % 200_000 == 0) progress("writing $n") }
        for (b in sinIn) { val x = java.lang.Double.longBitsToDouble(b); w.write("S|"); w.write(java.lang.Long.toHexString(b)); w.write("|"); w.write(h(Math.sin(x))); w.write("\n"); if (++n % 200_000 == 0) progress("writing $n") }
        for (b in asinIn) { val x = java.lang.Double.longBitsToDouble(b); w.write("A|"); w.write(java.lang.Long.toHexString(b)); w.write("|"); w.write(h(Math.asin(x))); w.write("\n"); if (++n % 200_000 == 0) progress("writing $n") }
        for (p in hyIn) { val (xs, ys) = p.split(','); val x = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(xs, 16)); val y = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(ys, 16)); w.write("H|"); w.write(p); w.write("|"); w.write(h(Math.hypot(x, y))); w.write("\n"); if (++n % 200_000 == 0) progress("writing $n") }
        w.write("# MATHPROBE done: $n values in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        Log.i(TAG, "GTCERT mathprobe done: $n values")
    }

    /**
     * MATHPOW-2026-11-10 (TEST-ONLY, phase 2): GtRead's haversine (2.7e ConvoyEngine) squares with x.pow(2) = java.lang.Math.pow(x, 2.0),
     * a different routine from sin/cos. Proven, not assumed: what the Droid's pow(x, 2.0) returns on the values GtRead really squares --
     * sin(dLat/2) and sin(dLon/2) of every neighbouring point pair (tracks + every 7th trail, up to 1,200,000) -- plus seeded random
     * values (uniform, wide exponents, near 1). Lines "P|<x bits>|<pow bits>". The header counts how often pow(x, 2.0) != x * x here.
     * Runs when GtCertRun is given mode "mathpow". ⚠ TEST-ONLY (CODE RULE 3): remove with GtCert before 2.8 is released.
     */
    fun runPow(app: Context, w: java.io.Writer) {
        val t0 = System.currentTimeMillis(); var last = t0
        fun progress(msg: String) { val now = System.currentTimeMillis(); if (now - last >= 10_000) { last = now; Log.i(TAG, "GTCERT mathpow $msg") } }
        val f = java.io.File(GroupTrackStorage.dir("cert15c_input", app), "grouptrack_spatial.db")
        if (!f.isFile) { w.write("# MATHPOW: STOP -- no input at ${f.path} (gt_cert pushes it for rider15c)\n"); return }
        val pIn = LinkedHashSet<Long>(); val realCap = 1_200_000
        fun addP(x: Double, cap: Int) { if (pIn.size < cap) pIn.add(java.lang.Double.doubleToRawLongBits(x)) }
        val pair = java.util.regex.Pattern.compile("(-?[0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?) (-?[0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)")
        var lines = 0; var points = 0
        fun line(wkt: String) {
            if (pIn.size >= realCap) return
            lines++
            val m = pair.matcher(wkt)
            var pLat = Double.NaN; var pLon = Double.NaN
            while (m.find()) {
                val lon = m.group(1)!!.toDouble(); val lat = m.group(2)!!.toDouble(); points++
                if (!pLat.isNaN()) {
                    val dLat = (lat - pLat) * D2R; val dLon = (lon - pLon) * D2R      // GtRead.toRadians(lat2 - lat1) = (lat2 - lat1) * D2R
                    addP(Math.sin(dLat / 2), realCap); addP(Math.sin(dLon / 2), realCap)
                }
                pLat = lat; pLon = lon
            }
            progress("lines $lines points $points pow ${pIn.size}")
        }
        val db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT geometry FROM tracks WHERE geometry IS NOT NULL", null).use { c -> while (c.moveToNext()) line(c.getString(0)) }
            db.rawQuery("SELECT geometry FROM trails WHERE geometry IS NOT NULL AND rowid % 7 = 0", null).use { c -> while (c.moveToNext()) line(c.getString(0)) }
        } finally { db.close() }
        val real = pIn.size
        val rnd = java.util.Random(20261110L)
        repeat(150_000) { addP(rnd.nextDouble() * 2.0 - 1.0, CAP) }
        repeat(150_000) { addP(rnd.nextGaussian() * Math.pow(10.0, rnd.nextInt(60) - 30.0), CAP) }
        repeat(30_000) { addP(0.99 + rnd.nextDouble() * 0.01, CAP) }
        var notSq = 0
        for (b in pIn) { val x = java.lang.Double.longBitsToDouble(b); if (java.lang.Double.doubleToRawLongBits(Math.pow(x, 2.0)) != java.lang.Double.doubleToRawLongBits(x * x)) notSq++ }
        w.write("# MATHPOW droid ${android.os.Build.MODEL}: real data $lines lines, $points points -> $real values; + seeded random (20261110); pow ${pIn.size}; Math.pow(x, 2.0) != x * x on $notSq\n")
        var n = 0
        for (b in pIn) { val x = java.lang.Double.longBitsToDouble(b); w.write("P|"); w.write(java.lang.Long.toHexString(b)); w.write("|"); w.write(h(Math.pow(x, 2.0))); w.write("\n"); if (++n % 200_000 == 0) progress("writing $n") }
        w.write("# MATHPOW done: $n values in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        Log.i(TAG, "GTCERT mathpow done: $n values")
    }
}
