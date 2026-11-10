package com.geeksville.mesh.convoy

import com.grouptrack.shared.osm.GtPbfTags
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * OsmPbfTagReader -- reads a Geofabrik .osm.pbf and writes the interim tag table the classifier needs (PBFTAGS-2026-08-30).
 *
 * STEP15G-SWITCH-2026-11-10: THE FRONT DOOR of the certified shared GtPbfTags (2.8 step 15g, 57cd2a8a7: the Utah tag pass 428,801 rows,
 * OLD = NEW = iPad). The public API is 2.7e's, unchanged; the 2.7e file (the format notes, the two silent-failure traps, the proven
 * Python run) is in git history (57cd2a8a7) and in the TEST-ONLY OsmPbfTagReaderCertOld.
 */
object OsmPbfTagReader {
    const val TABLE = GtPbfTags.TABLE
    val WIDE: Set<String> get() = GtPbfTags.WIDE

    /** Read [pbf] and write [out] (deleted first). Returns the row count, or -1 on failure. [onProgress] (waysWalked, rowsWritten). */
    fun build(pbf: File, out: File, onProgress: ((Long, Int) -> Unit)? = null): Int =
        GtPbfTags.build(FileSystem.SYSTEM, pbf.toOkioPath(), out.toOkioPath(), { System.currentTimeMillis() }, onProgress)
}
