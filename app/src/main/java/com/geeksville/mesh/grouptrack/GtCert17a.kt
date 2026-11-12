package com.geeksville.mesh.grouptrack

import android.content.Context
import android.util.Log
import com.geeksville.mesh.convoy.GroupTrackStorage

/**
 * STEP17G2A-2026-11-12 (2.8 step 17, G2a) ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): the storage socket,
 * OLD (GroupTrackStorage, 2.7e) = NEW (GtStorage through GtStorageAndroid) for every path the app asks for. Request mode "storage17a".
 * Lines: S17A <tab> key <tab> SAME|DIFF|EXPECTED <tab> value; footer "# S17A done: N SAME, N DIFF, N EXPECTED in S s".
 * The names: every folder name the 2.8 source asks GroupTrackStorage.dir for (collected 11-12; the dated ones with a sample date) + the
 * harmless edge cases; the refused ones ("./x", "../x", "a/../b") must be REFUSED by NEW (EXPECTED) -- OLD's answer is written beside it.
 */
object GtCert17a {
    private const val TAG = "CertRun"
    private val NAMES = listOf("rides", "recipes", "cert15c_input", "config", "state", "osm", "imports", "cert15d_input", "cert15b",
        "states/utah", "ipad_export.request", "gtcert_routes_2026-11-12_1015", "gtcert_rides_export", "gtcert_rides_2026-11-12_1015",
        "data/trail_areas", "cert_run.request", "cert16c_input", "cert15g_input", "cert15f_input", "", "rides/", "a//b", "states/")
    private val REFUSED = listOf("./x", "../x", "a/../b")

    fun run(app: Context, w: java.io.Writer) {
        val t0 = System.currentTimeMillis()
        var same = 0; var diff = 0; var expected = 0
        val st = GtStorageAndroid.of(app)
        fun check(k: String, old: String, new: String) {
            if (old == new) { same++; w.write("S17A\t$k\tSAME\t$new\n") }
            else { diff++; w.write("S17A\t$k\tDIFF\n  OLD\t$old\n  NEW\t$new\n") }
        }
        w.write("# S17A droid ${android.os.Build.MODEL}: the storage socket, OLD GroupTrackStorage = NEW GtStorage (internal=${GroupTrackStorage.isInternal()})\n")
        check("root", GroupTrackStorage.root(app).path, st.root().toString())
        check("tileRoot", GroupTrackStorage.tileRoot(app).path, st.tileRoot().toString())
        check("tracksRoot", GroupTrackStorage.tracksRoot(app).path, st.tracksRoot().toString())
        check("cacheDir", app.cacheDir.path, st.cacheDir().toString())
        check("filesDir", app.filesDir.path, st.filesDir().toString())
        for (n in NAMES) check("dir|$n", GroupTrackStorage.dir(n, app).path, st.dir(n).toString())
        for (n in REFUSED) {
            val r = runCatching { st.dir(n) }
            if (r.isFailure) { expected++; w.write("S17A\tdir|$n\tEXPECTED\trefused by NEW (OLD gives ${GroupTrackStorage.dir(n, app).path})\n") }
            else { diff++; w.write("S17A\tdir|$n\tDIFF\n  OLD\trefusal expected\n  NEW\t${r.getOrNull()}\n") }
        }
        w.write("# S17A done: $same SAME, $diff DIFF, $expected EXPECTED in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        Log.i(TAG, "GTCERT storage17a: $same SAME, $diff DIFF, $expected EXPECTED")
    }
}
