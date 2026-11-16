package com.geeksville.mesh.grouptrack

import android.content.Context
import com.geeksville.mesh.convoy.ConvoyTrackOps
import com.geeksville.mesh.convoy.GroupTrackStorage
import com.grouptrack.shared.cert.CertPaths
import com.grouptrack.shared.log.gtLogger
import okio.FileSystem
import java.io.Writer

/**
 * CERTPATHS-2026-11-16 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released):
 * the Droid legs of seam 2's ENVIRONMENT half. Request mode "certpaths".
 *
 * THREE LEGS, and this file carries two of them:
 *   1. OLD Droid  GroupTrackStorage -- java.io.File joining, the 2.7e code still running today
 *   2. NEW Droid  GtStorage over GtStorageAndroid -- okio joining, the shared assembly
 *   3. iPad       GtStorage over IosStorageBase -- GtCertPathsMacTest
 * Legs 1 and 2 are compared here on the TAILS and written to the report; leg 3 must reproduce leg
 * 2's strict keys. storage17a already compares OLD and NEW on the ABSOLUTE paths; this adds the
 * tail comparison because the tail is the only thing the iPad can be held to.
 *
 * \u26a0 IT WRITES, unlike every other cert. A path can be perfectly shaped and unwritable, and every
 * tail would still match -- so CertPaths writes a probe, reads it back and hashes it. It writes ONLY
 * under <cacheDir>/certpaths_tmp, <root>/certpaths_tmp and <my_tracks>/certpaths_tmp, and deletes
 * what it wrote. It never touches a database, the tile store, Download, or any real track file.
 *
 * Lines: CERTPATHS <tab> key <tab> SAME|EXPECTED|DIFF|FACT <tab> value
 * \u26d4 FACT lines are the container absolutes and cacheDir/filesDir, which CANNOT match across
 * platforms because the OS names those directories. They are reported so a human can read them and
 * are never compared -- comparing them would fail on a correct implementation, which is a useless test.
 */
object GtCertPaths {
    private const val TAG = "CertRun"
    private val log = gtLogger(TAG)

    private fun esc(s: String) =
        s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    /** the same tail computation CertPaths uses, for the OLD side's java.io.File paths */
    private fun tail(path: String, parent: String?): String {
        if (parent == null || parent.isEmpty()) return path.substringAfterLast('/')
        return if (path.startsWith(parent)) path.removePrefix(parent).trimStart('/') else path.substringAfterLast('/')
    }

    /**
     * Reads a file back OUT of Downloads through MediaStore and hashes it.
     * ⛔ This is the only way to verify a MediaStore write on API 29+: the file has no path the app
     * may open. Returns (found, hash) -- hash -1 when found but unreadable.
     */
    private fun mediaStoreRead(app: Context, name: String): Pair<Boolean, Long> {
        val cr = app.contentResolver
        val base = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        try {
            cr.query(base, arrayOf(android.provider.MediaStore.MediaColumns._ID),
                android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=?", arrayOf(name), null)?.use { c ->
                if (!c.moveToFirst()) return Pair(false, 0L)
                val id = c.getLong(0)
                val uri = android.content.ContentUris.withAppendedId(base, id)
                val text = cr.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
                    ?: return Pair(true, -1L)
                var h = 0L
                for (ch in text) h = h * 31 + ch.code
                return Pair(true, h)
            }
        } catch (e: Exception) {
            return Pair(false, -2L)
        }
        return Pair(false, 0L)
    }

    /** Deletes a cert file from Downloads through MediaStore, so the probes do not accumulate. */
    private fun mediaStoreDelete(app: Context, name: String): Boolean = try {
        app.contentResolver.delete(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=?", arrayOf(name)) > 0
    } catch (e: Exception) { false }

    fun run(app: Context, w: Writer): Unit {
        val t0 = System.currentTimeMillis()
        var same = 0; var diff = 0; var expected = 0; var facts = 0

        val st = GtStorageAndroid.of(app)
        w.write("# CERTPATHS droid ${android.os.Build.MODEL}: seam 2 environment -- internal=${GroupTrackStorage.isInternal()}\n")
        w.write("# CERTPATHS legs: 1 OLD GroupTrackStorage (java.io.File) | 2 NEW GtStorage (okio) | 3 iPad (GtCertPathsMacTest)\n")

        // ---- leg 2: the shared assembly, every key ------------------------------------------
        val new = LinkedHashMap<String, String>()
        try { CertPaths.run(CertPaths.Side(st, FileSystem.SYSTEM)) { k, v -> new[k] = v } }
        catch (e: Exception) { w.write("CERTPATHS\tNEW-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }

        // ---- leg 1: OLD, on the keys it can answer (the joining, not the round trip) --------
        val rootParent = GroupTrackStorage.root(app).parent
        val old = LinkedHashMap<String, String>()
        old["PATHS|root|name"] = GroupTrackStorage.root(app).name
        old["PATHS|tracksRoot|name"] = GroupTrackStorage.tracksRoot(app).name
        old["PATHS|tracksRoot|is-sibling-of-root"] =
            (GroupTrackStorage.tracksRoot(app).parent == rootParent).toString()
        old["PATHS|tileRoot|equals-root"] =
            (GroupTrackStorage.tileRoot(app).path == GroupTrackStorage.root(app).path).toString()
        for (n in CertPaths.DIR_NAMES) {
            // \u26a0 GroupTrackStorage.dir uses java.io.File(root, name); GtStorage uses okio. The two
            // normalise "rides/", "a//b" and "states/" differently in principle, which is exactly
            // why these shapes are in DIR_NAMES and why storage17a pinned them in the first place.
            old["PATHS|dir|" + CertPaths.esc(n)] = tail(GroupTrackStorage.dir(n, app).path, rootParent)
        }
        for (n in CertPaths.REFUSED) {
            // OLD does NOT refuse: java.io.File keeps "." and ".." parts. storage17a records that as
            // EXPECTED, and so does this -- the refusal is a NEW rule, deliberately added.
            old["PATHS|refused|" + CertPaths.esc(n)] = "ALLOWED:" + GroupTrackStorage.dir(n, app).path
        }

        // ---- compare legs 1 and 2 on the keys OLD answered ----------------------------------
        for (k in new.keys) {
            if (CertPaths.isFact(k)) { facts++; w.write("CERTPATHS\t$k\tFACT\t${esc(new[k]!!)}\n"); continue }
            val o = old[k]
            val n = new[k]!!
            val why = CertPaths.EXPECTED_DIFF[k]
            when {
                o == null -> { same++; w.write("CERTPATHS\t$k\tSAME\t${esc(n)}\n") }   // NEW-only (the round trip)
                o == n -> { same++; w.write("CERTPATHS\t$k\tSAME\t${esc(n)}\n") }
                k.startsWith("PATHS|refused|") && n == "REFUSED" -> {
                    expected++
                    w.write("CERTPATHS\t$k\tEXPECTED\tOLD ${esc(o)} | NEW REFUSED  [the refusal is a NEW rule: java.io.File keeps \".\"/\"..\" parts, okio resolves them, so the two would name different places]\n")
                    w.write("CERTPATHS\t$k|newonly\tSAME\t${esc(n)}\n")
                }
                why != null -> {
                    expected++
                    w.write("CERTPATHS\t$k\tEXPECTED\t${esc(o)} | ${esc(n)}  [$why]\n")
                    w.write("CERTPATHS\t$k|newonly\tSAME\t${esc(n)}\n")
                }
                else -> { diff++; w.write("CERTPATHS\t$k\tDIFF\tOLD ${esc(o)} | NEW ${esc(n)}\n") }
            }
        }
        val missed = old.keys - new.keys
        for (k in missed) { diff++; w.write("CERTPATHS\t$k\tDIFF\tOLD answered but NEW did not: ${esc(old[k]!!)}\n") }

        // ================================================================ THE EXPORT LEG
        // Fred 11-16 10:31: "it is the test that determines if our default environment mapping puts
        // files in the download directory properly."
        //
        // ⛔ THERE ARE TWO EXPORT MECHANISMS IN THIS APP AND THEY DO NOT AGREE:
        //   A  ConvoyTrackOps.exportGpxToDownloads -- a RAW java.io.File write into
        //      Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS). This is what
        //      GtExportAll (all tracks / routes / waypoints) and ConvoyArtifactOps use.
        //   B  ImportConverter.saveToDownloads -- MediaStore with IS_PENDING, which is the scoped
        //      storage way and needs no permission. This is what the import's converted-GPX save and
        //      the shared socket (GtImportAndroid.platform.saveToDownloads) use.
        // The manifest (NOSTORAGEPERMS-2026-09-13) says "an app may write to Downloads with no
        // permission on API 29+, which is how track export works - verified on device 09-13". That is
        // true of B. Whether it is true of A at targetSdk 36 is the open question, and A failing is
        // SILENT: exportGpxToDownloads catches the exception, returns false, and the only symptom is
        // a toast reading "Export failed: could not write to Downloads."
        // ⚠ This block does not assume an answer. It records what each mechanism actually did.
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val wantHash = CertPaths.payloadHash()
        // ⚠ FACT, not SAME: the iPad never emits this key, so a SAME line here would land in the
        // Mac test's "extra" set and fail a passing run. Both sides already emit EXPORT|payload|hash.
        w.write("CERTPATHS\tPATHS-FACT|payload|hash-expected\tFACT\t$wantHash\n"); facts++

        // ---- A: the RAW File write (what ExportAll uses) -----------------------------------
        val rawName = "GroupTrack_CERTPATHS_A_$stamp"
        val okRaw = try { ConvoyTrackOps.exportGpxToDownloads(rawName, CertPaths.EXPORT_PAYLOAD) }
                    catch (e: Exception) { false }
        w.write("CERTPATHS\tEXPORT-A|raw-write|returned\tFACT\t$okRaw\n"); facts++
        val dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        val rawFile = java.io.File(dl, "$rawName.gpx")
        var rawHash = 0L
        val rawFound = rawFile.exists()
        if (rawFound) { val txt = try { rawFile.readText(Charsets.UTF_8) } catch (e: Exception) { "" }
                        for (ch in txt) rawHash = rawHash * 31 + ch.code }
        w.write("CERTPATHS\tEXPORT-A|raw-write|file-exists\tFACT\t$rawFound\n"); facts++
        w.write("CERTPATHS\tEXPORT-A|raw-write|bytes-match\tFACT\t${rawFound && rawHash == wantHash}\n"); facts++
        w.write("CERTPATHS\tEXPORT-A|raw-write|path\tFACT\t${esc(rawFile.absolutePath)}\n"); facts++
        if (rawFound) runCatching { rawFile.delete() }

        // ---- B: through the SHARED SOCKET (MediaStore) -- the leg the iPad also runs --------
        val env = GtImportAndroid(app)
        val scratch = st.cacheDir() / "certpaths_export"
        try {
            CertPaths.runExport(env.platform, FileSystem.SYSTEM, scratch) { k, v ->
                same++; w.write("CERTPATHS\t$k\tSAME\t${esc(v)}\n")
            }
        } catch (e: Exception) { diff++; w.write("CERTPATHS\tEXPORT|runExport\tDIFF\t${esc(e.toString())}\n") }
        // did B's file land, and are its bytes the payload's?
        val bName = CertPaths.EXPORT_NAME
        val found = mediaStoreRead(app, bName)
        w.write("CERTPATHS\tEXPORT-B|mediastore|found\tFACT\t${found.first}\n"); facts++
        w.write("CERTPATHS\tEXPORT-B|mediastore|bytes-match\tFACT\t${found.first && found.second == wantHash}\n"); facts++
        if (found.first) w.write("CERTPATHS\tEXPORT-B|mediastore|deleted\tFACT\t${mediaStoreDelete(app, bName)}\n").also { facts++ }

        // ---- C: THE REAL THING -- export every track to one GPX, into Download --------------
        // ⚠ FACT, not compared: the content is this Droid's actual tracks, which the iPad does not
        // have. A content hash would differ on a CORRECT implementation. What matters is whether the
        // export reports success and whether a file is really there afterwards.
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val msg = try { GtExportAll.run(app, "tracks", true) } catch (e: Exception) { "THREW: " + e.message }
        w.write("CERTPATHS\tEXPORT-C|exportAll-tracks|message\tFACT\t${esc(msg)}\n"); facts++
        val cFile = java.io.File(dl, "GroupTrack_TRACKS_$day.gpx")
        w.write("CERTPATHS\tEXPORT-C|exportAll-tracks|file-exists\tFACT\t${cFile.exists()}\n"); facts++
        w.write("CERTPATHS\tEXPORT-C|exportAll-tracks|bytes\tFACT\t${if (cFile.exists()) cFile.length() else -1L}\n"); facts++
        w.write("CERTPATHS\tEXPORT-C|exportAll-tracks|path\tFACT\t${esc(cFile.absolutePath)}\n"); facts++
        // ⚠ NOT deleted: this is the rider's real export and he asked for it to be written.

        // the keys the Droid actually ran, printed (Fred 11-16 08:38)
        w.write("# CERTPATHS keys-used begin\n")
        for (n in CertPaths.DIR_NAMES) w.write("# KEY\tdir\t${CertPaths.esc(n)}\n")
        for (n in CertPaths.REFUSED) w.write("# KEY\trefused\t${CertPaths.esc(n)}\n")
        w.write("# CERTPATHS keys-used end\n")
        w.write("# CERTPATHS done: $same SAME, $diff DIFF, $expected EXPECTED, $facts FACT in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        w.flush()
        log.i { "GTCERT certpaths done: $same SAME, $diff DIFF, $expected EXPECTED, $facts FACT" }
    }
}
