package com.geeksville.mesh.grouptrack

import android.content.Context
import java.io.File

/**
 * SEAM2PATHS-2026-11-16: the Android side of seam 2's path conversion.
 *
 * WHY THIS EXISTS. The conversion replaces every `ctx.cacheDir` / `ctx.filesDir` in app code with a
 * call that goes through the shared storage socket, so the iPad resolves the same places. But the
 * types do not line up: ctx.cacheDir is a java.io.File and GtStorage.cacheDir() is an okio Path, and
 * 22 of the call sites immediately wrap the value in File(...):
 *     val dir = File(ctx.cacheDir, "exports").also { it.mkdirs() }
 *     val stage = java.io.File(context.filesDir, "gpx_staging")
 * Without this accessor the patch would have to rewrite each site's downstream use as well, which is
 * 22 hand edits instead of one scripted pass -- and hand edits across 22 files is how v4 broke the
 * build this morning.
 *
 * ⚠ IT CHANGES NOTHING ABOUT WHERE FILES GO, and that is the point. certpaths proved on Droid 1 at
 * 10:51 that app.cacheDir and GtStorage.cacheDir() resolve to the IDENTICAL absolute path
 * (/data/user/0/com.grouptrack.android/cache), and likewise filesDir. So every converted site reads
 * exactly the same directory it read before -- the conversion makes the call PORTABLE, not different.
 * What it buys: the iPad gets Library/Caches and Library from the same expression.
 *
 * ⛔ File(p.toString()), NOT okio's Path.toFile(). toFile() almost certainly exists in okio 3.16.4's
 * JVM source set, but "almost certainly" is how the iOS build broke on 11-15. toString() is the
 * documented behaviour this codebase already relies on (GtCert17maprecipe.kt:52 does
 * File(dir.toString())), so it is the form with a precedent rather than the form that reads better.
 *
 * ⚠ GtStorageAndroid.of(ctx) is constructed per call, as GtImportAndroid does and as
 * OsmImportLedger.kt:27 and StateData.kt:69 already do. It holds no state; its only work is
 * GtStorageAndroid.of(app), which resolves the four platform directories on demand because
 * GroupTrackStorage's root can change per session (SESSIONMODE-2026-09-12).
 */
object GtPathsAndroid {

    /** context.cacheDir, through the socket. The SAME directory -- see certpaths, 11-16 10:51. */
    fun cache(ctx: Context): File = File(GtStorageAndroid.of(ctx).cacheDir().toString())

    /** context.filesDir, through the socket. The SAME directory -- see certpaths, 11-16 10:51. */
    fun files(ctx: Context): File = File(GtStorageAndroid.of(ctx).filesDir().toString())
}
