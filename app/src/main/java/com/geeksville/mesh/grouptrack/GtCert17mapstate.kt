package com.geeksville.mesh.grouptrack

import android.content.Context
import com.geeksville.mesh.convoy.MapStateStore
import com.geeksville.mesh.convoy.MapStateStoreCertOld
import com.grouptrack.shared.cert.Cert17mapstate
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.mapstate.GtMapState
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.Writer

/**
 * GTCERT17MAPSTATEV2-2026-11-14 (2.8 step 17, Phase 2 pass 1, Droid side) !! TEST-ONLY
 * (CODE RULE 3: remove with GtCertRun before 2.8 is released).
 *
 * OLD = 2.7e's MapStateStore logic as real Android code over real org.json (MapStateStoreCertOld,
 * 12-item counted manifest). NEW = Cert17mapstate.newSide, which is COMMON code.
 *
 * !! THIS FILE HOLDS ONLY THE OLD SIDE, ON PURPOSE. The formatter and the NEW side were private
 * here in v1, which meant the iPad test would have needed its own copies -- and then a difference
 * between two FORMATTERS would have read as a difference between two DEVICES, on all 349 keys. They
 * now live in commonMain (Cert17mapstate.typeLine/.../newSide), so the Droid and the iPad run the
 * same bytes and only their throwaway directories differ. org.json does not exist in commonMain,
 * so OLD is the one side that cannot move.
 *
 * NOTHING LIVE IS READ OR WRITTEN. Each side gets its own root under
 * app.cacheDir/gtcert17mapstate/{old,new}, wiped at the start of the run. The live GroupTrackStorage
 * root is never resolved and CertPaths exposes no live path at all, so a malformed fixture cannot
 * reach anything real.
 *
 * SIMULATED FIRST (sim_mapstate_v5_2026-11-14, the standing rule extended 11-13 to the harness):
 * 20 fixtures, every scenario terminates, 349 compared keys, 340 SAME, 0 DIFF, 9 EXPECTED.
 * The simulation caught three defects in this design before it ran -- see the cert's own header.
 *
 * Lines: MAPSTATE <tab> key <tab> SAME|EXPECTED|DIFF <tab> value
 * Footer: "# MAPSTATE done: N SAME, N DIFF, N EXPECTED in S s".
 */
object GtCert17mapstate {

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    /** Files: okio over the real filesystem, scoped to this run's own directory. */
    private class CertFiles : GtFiles {
        override val fs: FileSystem = FileSystem.SYSTEM
        override fun setLastModified(path: Path, epochMs: Long): Boolean = File(path.toString()).setLastModified(epochMs)
        override fun lastModified(path: Path): Long = File(path.toString()).lastModified()
        override fun list(dir: Path): List<Path> = (File(dir.toString()).listFiles() ?: emptyArray()).map { it.toOkioPath() }
    }

    /** Paths: this side's root and nothing else -- no live path is reachable through this env. */
    private class CertPaths(private val root: Path, private val cache: Path) : GtImportPaths {
        override fun groupTrackRoot(): Path = root
        override fun tracksDir(): Path = root / "my_tracks"
        override fun dir(name: String): Path = root / name
        override fun cacheDir(): Path = cache
        override fun filesDir(): Path = root / "files"
    }

    // ------------------------------------------------------------------ OLD (org.json; cannot move)
    private class OldSide(private val files: GtFiles, private val paths: GtImportPaths) : Cert17mapstate.Side {
        private val store = MapStateStoreCertOld(files, paths)

        private fun write(name: String, text: String, exists: Boolean) {
            val d = paths.dir("state"); files.fs.createDirectories(d)
            val p = d / name
            if (exists) files.fs.write(p) { writeUtf8(text) } else try { files.fs.delete(p) } catch (e: Exception) { }
        }

        /** 2.7e's types -> plain scalars -> THE SHARED FORMATTER. This side builds no compared string. */
        private fun flat(s: MapStateStore.MapSnapshot): List<String> =
            GtMapState.TYPES.map { t ->
                val ts = s.types[t]
                Cert17mapstate.typeLine(t, ts?.state ?: 0, ts?.rows?.size ?: 0, store.checkedIdsFor(s, t))
            } + listOf(
                Cert17mapstate.panelLine(s.panel.tilesChecked, s.panel.trailsChecked, s.panel.removeTilesChecked),
                Cert17mapstate.bboxLine(s.bbox?.south, s.bbox?.west, s.bbox?.north, s.bbox?.east, s.bbox?.zoom),
                Cert17mapstate.fitLine(s.fitArtifact?.id, s.fitArtifact?.name, s.fitArtifact?.type),
                Cert17mapstate.routeLine(s.routeState?.open, s.routeState?.status, s.routeState?.draftName),
            )

        /** 2.7e's own defaults, built the way readMap's private defaults() builds them. */
        private fun defaultsSnap(): MapStateStore.MapSnapshot = MapStateStore.MapSnapshot(
            GtMapState.TYPES.associateWith { MapStateStore.TypeState(GtMapState.typeDefault(it), emptyList()) },
            MapStateStore.PanelBoxes()
        )

        private fun snap(s: MapStateStore.MapSnapshot) = Cert17mapstate.snapOf(flat(s), flat(defaultsSnap()))

        override fun readMapAfter(mapKey: String, text: String, exists: Boolean): Cert17mapstate.Snap {
            write(mapKey + "_panel.json", text, exists); return snap(store.readMap(mapKey))
        }
        override fun readViewAfter(mapKey: String, text: String, exists: Boolean): String {
            write(mapKey + "_view.json", text, exists)
            val v = store.readView(mapKey); return v.mapType + "|" + v.offline
        }
        override fun roundTrip(mapKey: String, text: String): Cert17mapstate.Snap {
            write(mapKey + "_panel.json", text, true)
            store.saveMap(mapKey, store.readMap(mapKey))   // read it, write it back, read it again
            return snap(store.readMap(mapKey))
        }
        override fun rideKey(): String = store.rideKey()
        override fun typeDefaults(): String =
            GtMapState.TYPES.joinToString(";") { it + "=" + (if (it == "Trails") 1 else 0) }
    }

    fun run(ctx: Context, w: Writer) {
        val t0 = System.currentTimeMillis()
        val base = File(ctx.cacheDir, "gtcert17mapstate")
        base.deleteRecursively()
        val oldRoot = File(base, "old").also { it.mkdirs() }
        val newRoot = File(base, "new").also { it.mkdirs() }
        val cache = File(base, "cache").also { it.mkdirs() }
        val files = CertFiles()
        val mapKey = Cert17mapstate.MAP_KEY   // IPADTEST-2026-11-14: shared, so the iPad cannot drift from it

        val oldMap = LinkedHashMap<String, String>()
        val newMap = LinkedHashMap<String, String>()
        try {
            Cert17mapstate.run(OldSide(files, CertPaths(oldRoot.toOkioPath(), cache.toOkioPath())), mapKey) { k, v -> oldMap[k] = v }
        } catch (e: Exception) { w.write("MAPSTATE\tOLD-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }
        try {
            Cert17mapstate.run(Cert17mapstate.newSide(files, CertPaths(newRoot.toOkioPath(), cache.toOkioPath())), mapKey) { k, v -> newMap[k] = v }
        } catch (e: Exception) { w.write("MAPSTATE\tNEW-THREW\tDIFF\t${esc(e.toString())}\n"); w.flush(); return }

        var same = 0
        var diff = 0
        var expected = 0
        for (k in (oldMap.keys + newMap.keys).distinct()) {
            val o = oldMap[k] ?: "(missing)"
            val n = newMap[k] ?: "(missing)"
            val whyExp = Cert17mapstate.EXPECTED_DIFF[k]
            if (o == n) {
                same++; w.write("MAPSTATE\t$k\tSAME\t${esc(o)}\n")
                // a divergence that stopped diverging means the table is stale -- say so, never pass quietly
                if (whyExp != null) w.write("# MAPSTATE-NOTE\tEXPECTED_DIFF[$k] is STALE: OLD and NEW now agree ($whyExp)\n")
            } else if (whyExp != null) {
                expected++
                w.write("MAPSTATE\t$k\tEXPECTED\t${esc(o)} | ${esc(n)}  [$whyExp]\n")
                // the port's own answer, so the iPad still compares NEW against NEW here (the G1c convention)
                w.write("MAPSTATE\t$k|newonly\tSAME\t${esc(n)}\n")
            } else { diff++; w.write("MAPSTATE\t$k\tDIFF\t${esc(o)} | ${esc(n)}\n") }
        }
        w.write("# MAPSTATE done: $same SAME, $diff DIFF, $expected EXPECTED in ${(System.currentTimeMillis() - t0) / 1000} s\n")
        w.flush()
    }
}
