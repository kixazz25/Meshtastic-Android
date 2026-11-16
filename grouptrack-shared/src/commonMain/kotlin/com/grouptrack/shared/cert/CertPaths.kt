package com.grouptrack.shared.cert

import com.grouptrack.shared.imports.GtImportPlatform
import com.grouptrack.shared.storage.GtStorage
import okio.FileSystem
import okio.Path

/**
 * CERTPATHS-2026-11-16 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released):
 * seam 2's ENVIRONMENT half -- where a file goes. Deliberately last and separate from the value
 * services (Fred 11-16 09:53: "let do environmental values last as its own test with its own harness").
 *
 * THE ASSERTION UNDER TEST (Fred 11-16 09:35): "we will address certpaths today by using the default
 * android behavior paths to this app on the iPad. that is the assertion we will be testing."
 * So the iPad is not given an iOS-idiomatic layout; it is given Android's, with iOS's container
 * directories substituted. This harness is what proves or breaks that.
 *
 * ⛔ WHY THIS CANNOT BE A PLAIN STRING COMPARE, and why that is not a loophole. The absolute paths
 * differ BY DESIGN -- the OS hands each platform its own container:
 *     Droid  /storage/emulated/0/Android/data/com.grouptrack.android/files/GroupTrack
 *     iPad   /var/mobile/Containers/Data/Application/<uuid>/Documents/GroupTrack
 * Comparing those would fail on a CORRECT implementation, which makes it a useless test. So the
 * fingerprint is split, and the split is the honest part:
 *
 *   STRICT, compared between platforms -- everything the APP addresses:
 *     every dir(name) RELATIVE TO root()      "GroupTrack/rides", "GroupTrack/osm", ...
 *     root()'s last segment                   "GroupTrack"
 *     tracksRoot()'s last segment             "my_tracks"  (a SIBLING, never a child)
 *     tracksRoot relative to root's parent    proves the flat layout, not just the name
 *     the refusals: dir("./x"), dir("../x"), dir("a/../b") must all throw
 *     the ROUND TRIP: bytes written, read back, hashed -- identical on every platform
 *
 *   FACT, reported and never compared -- what the OS owns:
 *     the absolute container prefix
 *     cacheDir / filesDir, whose tails genuinely cannot match: Android names them "cache" and
 *     "files" inside the app's data dir; iOS names them "Library/Caches" and "Library". Those two
 *     are the ONLY members with no comparable tail, and both are app-private on both platforms,
 *     which is the property that actually matters (CERTPATHS ownership, 11-16 09:40).
 *
 * ⚠ THE ROUND TRIP IS THE PART A STRING COMPARE CANNOT DO. A path can be perfectly shaped and
 * unwritable -- wrong container, missing parent, no permission -- and every tail would still match.
 * 11-16 produced two defects that compiled and read correctly while doing nothing: a copy that wrote
 * zero bytes, and a private directory that silently became user-visible. Writing bytes and reading
 * them back is what catches that class.
 *
 * ⚠ IT WRITES, UNLIKE THE OTHER CERTS. Every other cert says "nothing live is read or written".
 * This one must write to prove a directory is real. It writes ONLY under <cacheDir>/certpaths_tmp
 * and deletes what it wrote; it never touches my_tracks, a database, the tile store or Download.
 */
object CertPaths {

    /** Keys known to differ for a reason that is not a defect. Empty: classify on the first run. */
    val EXPECTED_DIFF: Map<String, String> = emptyMap()

    /**
     * The dir() names the app actually asks for, measured from the source on 11-16 (recipes 10,
     * state 7, config 6, osm 5, my_tracks 3 -- all three of those being comments warning against it
     * -- data/trail_areas 2, rides 1), plus the shapes GtStorageMacTest already pins.
     * ⛔ "my_tracks" is NOT in this list on purpose: GroupTrackStorage carries an explicit warning
     * never to build it as dir("my_tracks"), because that nests it inside GroupTrack and changes a
     * layout that has been flat since the beginning. It is checked through tracksRoot instead.
     */
    val DIR_NAMES: List<String> = listOf(
        "recipes", "state", "config", "osm", "rides", "data/trail_areas",
        "states/utah", "", "rides/", "a//b", "states/",
    )

    /** The names the shared joining must REFUSE (sim17g2a_2026-11-12 found these are the only ones). */
    val REFUSED: List<String> = listOf("./x", "../x", "a/../b")

    /** One leg: a GtStorage built on that platform's own base. */
    class Side(val st: GtStorage, val fs: FileSystem)

    private fun tailBelow(p: Path, parent: Path?): String {
        val s = p.toString()
        val b = parent?.toString() ?: return p.name
        return if (b.isNotEmpty() && s.startsWith(b)) s.removePrefix(b).trimStart('/') else p.name
    }

    /**
     * Emits the STRICT keys, then the FACT keys. Both go through [sink]; a caller compares the
     * strict ones and prints the facts.
     */
    fun run(side: Side, sink: (String, String) -> Unit) {
        val st = side.st
        val root = st.root()
        val tracks = st.tracksRoot()
        val rootParent = root.parent

        // ---- STRICT: what the app addresses -------------------------------------------------
        sink("PATHS|root|name", root.name)
        sink("PATHS|tracksRoot|name", tracks.name)
        // the flat-sibling layout, not merely the name: both hang off the SAME parent
        sink("PATHS|tracksRoot|is-sibling-of-root", (tracks.parent?.toString() == rootParent?.toString()).toString())
        sink("PATHS|tileRoot|equals-root", (st.tileRoot().toString() == root.toString()).toString())

        for (n in DIR_NAMES) {
            sink("PATHS|dir|" + esc(n), tailBelow(st.dir(n), rootParent))
        }
        for (n in REFUSED) {
            val r = runCatching { st.dir(n) }
            sink("PATHS|refused|" + esc(n), if (r.isFailure) "REFUSED" else "ALLOWED:" + r.getOrNull())
        }

        // ---- STRICT: the round trip, at each location ---------------------------------------
        // ⚠ cacheDir is used for the scratch folder because it is the one place the platform
        // guarantees is writable and disposable on both.
        val tmp = st.cacheDir() / "certpaths_tmp"
        val payload = "CERTPATHS-2026-11-16 round trip — ascii + non-ascii → done\n"
        for ((label, dir) in listOf("cache" to tmp, "root" to (root / "certpaths_tmp"), "tracks" to (tracks / "certpaths_tmp"))) {
            val res = runCatching {
                side.fs.createDirectories(dir)
                val f = dir / "probe.txt"
                side.fs.write(f) { writeUtf8(payload) }
                val back = side.fs.read(f) { readUtf8() }
                var h = 0L
                for (c in back) h = h * 31 + c.code
                val ok = back == payload
                side.fs.delete(f)
                runCatching { side.fs.delete(dir) }
                "written=" + payload.length + ";readback=" + back.length + ";identical=" + ok + ";hash=" + h
            }
            sink("PATHS|roundtrip|" + label, res.getOrElse { "FAILED: " + it.message })
        }

        // ---- FACT: what the OS owns, reported and never compared ----------------------------
        sink("PATHS-FACT|container|root-absolute", root.toString())
        sink("PATHS-FACT|container|tracks-absolute", tracks.toString())
        sink("PATHS-FACT|cacheDir|absolute", st.cacheDir().toString())
        sink("PATHS-FACT|filesDir|absolute", st.filesDir().toString())
        sink("PATHS-FACT|cacheDir|tail-below-root-parent", tailBelow(st.cacheDir(), rootParent))
        sink("PATHS-FACT|filesDir|tail-below-root-parent", tailBelow(st.filesDir(), rootParent))
    }

    // ------------------------------------------------------------------ THE EXPORT LEG
    /**
     * Fred 11-16 10:31: "it is the test that determines if our default environment mapping puts
     * files in the download directory properly."   10:29: "tests of this change should also include
     * export all tracks to gpx and should write file in download directory."
     *
     * A FIXED payload -- not a real track -- goes through the platform's OWN saveToDownloads, so the
     * two platforms are asked the identical question. The Droid additionally exports its real tracks
     * (GtCertPaths), but that content cannot be compared: the iPad has no tracks, so a content hash
     * would differ on a CORRECT implementation.
     *
     * ⚠ THE PAYLOAD CARRIES AN EM DASH AND AN ARROW on purpose. The logging cert found 37 non-ASCII
     * characters among 994 real messages this morning, all em dashes and arrows, and that is exactly
     * where two platforms' UTF-8 writers can diverge. An ASCII-only payload would not ask the question.
     */
    const val EXPORT_NAME: String = "GroupTrack_CERTPATHS_probe.gpx"

    val EXPORT_PAYLOAD: String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<gpx version=\"1.1\" creator=\"GroupTrack\">\n" +
        "  <metadata><name>CERTPATHS-2026-11-16 — export probe → Download</name></metadata>\n" +
        "  <trk><name>probe</name><trkseg>\n" +
        "    <trkpt lat=\"37.1234\" lon=\"-112.5678\"/>\n" +
        "    <trkpt lat=\"37.1240\" lon=\"-112.5670\"/>\n" +
        "  </trkseg></trk>\n" +
        "</gpx>\n"

    fun payloadHash(): Long {
        var h = 0L
        for (c in EXPORT_PAYLOAD) h = h * 31 + c.code
        return h
    }

    /**
     * Stages the payload and hands it to [platform].saveToDownloads, recording what came back.
     * ⛔ Whether the file LANDED is verified by each driver, not here: Android has to ask MediaStore
     * and iOS reads the file system, so the check itself is platform code. This emits the part both
     * platforms can be held to -- the name returned, and the bytes that went in.
     */
    fun runExport(platform: GtImportPlatform, fs: FileSystem, scratch: Path, sink: (String, String) -> Unit) {
        sink("EXPORT|payload|length", EXPORT_PAYLOAD.length.toString())
        sink("EXPORT|payload|hash", payloadHash().toString())
        sink("EXPORT|payload|non-ascii", EXPORT_PAYLOAD.count { it.code > 126 }.toString())

        val staged = runCatching {
            fs.createDirectories(scratch)
            val src = scratch / EXPORT_NAME
            fs.write(src) { writeUtf8(EXPORT_PAYLOAD) }
            src
        }
        if (staged.isFailure) {
            sink("EXPORT|staged", "FAILED: " + staged.exceptionOrNull()?.message)
            return
        }
        val src = staged.getOrThrow()
        sink("EXPORT|staged", "ok")

        val r = runCatching { platform.saveToDownloads(src) }
        // ⚠ the NAME is comparable; the absolute location is not, and is a FACT in each driver.
        sink("EXPORT|saveToDownloads|name", r.getOrElse { "THREW: " + it.message })
        sink("EXPORT|saveToDownloads|threw", r.isFailure.toString())

        runCatching { fs.delete(src) }
        runCatching { fs.delete(scratch) }
    }

    /** true when a key is a FACT line -- reported, never compared between platforms. */
    fun isFact(key: String): Boolean = key.startsWith("PATHS-FACT")

    fun esc(s: String): String = buildString {
        for (c in s) {
            val n = c.code
            if (n in 32..126 && c != '\t') append(c) else append("\\u").append(n.toString(16).padStart(4, '0'))
        }
    }
}
