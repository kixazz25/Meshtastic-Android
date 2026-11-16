package com.grouptrack.shared.platform

import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.ride.RideFiles
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * GTIOSRIDEFILES-2026-11-16: the iPad's RideFiles. Mirrors GtRidesAndroid's 15 members.
 *
 * Every member is a path or file operation, so the port is mechanical -- but java.io.File's RETURN
 * CONVENTIONS are not okio's, and four of them would be wrong if translated by shape instead of by
 * behaviour. Each is measured against the Android original, not assumed:
 *
 *  1. mkdirs  -- File.mkdirs() returns TRUE ONLY IF IT CREATED SOMETHING; it returns false when the
 *     directory already exists. okio's createDirectories is silently idempotent and returns Unit, so
 *     the exists-check is explicit here. A bare createDirectories + "return true" would report a
 *     creation that did not happen.
 *  2. list    -- returns List<String>? and NULL IS MEANINGFUL: "not a folder / not readable", exactly
 *     File.listFiles()' null, which 2.7e returned early on (CODE RULE 1, stated in the interface).
 *     okio's list THROWS there, so the throw becomes null rather than propagating.
 *  3. rename  -- File.renameTo RETURNS FALSE on failure and throws nothing. This codebase has the scar:
 *     SWEEPMOVE-2026-08-21H recorded renameTo returning false on FUSE-mounted external storage when the
 *     move crossed into a subdirectory. okio's atomicMove throws, so it is caught and returned as false.
 *  4. delete / length / isFile -- File returns false / 0L / false for a MISSING file rather than
 *     throwing. okio throws or returns null, so each is defended to the Android answer.
 *
 * ⚠ list() KEEPS THE PLATFORM'S OWN ORDER, as the interface says ("every entry, in the platform's own
 * order"). okio's list is sorted where File.listFiles() guarantees nothing -- the same accepted
 * divergence recorded for GtFiles under owner decision 11.
 *
 * ⚠ sibling() -- Android does File(File(path).parentFile, name), and parentFile is NULL for a bare
 * name, which java.io.File treats as "no parent" and resolves relative. okio's parent is nullable too,
 * so the null case falls back to the bare name rather than throwing.
 */
class IosRideFiles(
    private val paths: GtImportPaths = IosImportPaths(),
    private val fs: FileSystem = FileSystem.SYSTEM,
) : RideFiles {

    /** GroupTrackStorage.dir("rides") -- NOT created, exactly where 2.7e asked. */
    override fun ridesDir(): String = paths.dir("rides").toString()

    /** File(context.filesDir, "gpx_staging") -- where the import panel stages picked files. */
    override fun stagingDir(): String = (paths.filesDir() / "gpx_staging").toString()

    override fun child(dir: String, name: String): String = (dir.toPath() / name).toString()

    /** File(File(path).parentFile, name).path -- parentFile may be null; see the class note. */
    override fun sibling(path: String, name: String): String {
        val parent = path.toPath().parent ?: return name
        return (parent / name).toString()
    }

    override fun name(path: String): String = path.toPath().name

    /** ⛔ null = not a folder / not readable, exactly File.listFiles()' null (CODE RULE 1). */
    override fun list(dir: String): List<String>? = try {
        fs.list(dir.toPath()).map { it.name }
    } catch (e: Exception) {
        null
    }

    override fun isFile(path: String): Boolean =
        try { fs.metadataOrNull(path.toPath())?.isRegularFile == true } catch (e: Exception) { false }

    override fun exists(path: String): Boolean =
        try { fs.exists(path.toPath()) } catch (e: Exception) { false }

    /** File.delete(): false when the file is absent or cannot be removed -- never a throw. */
    override fun delete(path: String): Boolean =
        try { fs.delete(path.toPath()); true } catch (e: Exception) { false }

    /** File.mkdirs(): TRUE ONLY IF IT CREATED SOMETHING. See note 1. */
    override fun mkdirs(path: String): Boolean {
        val p = path.toPath()
        return try {
            if (fs.exists(p)) false
            else { fs.createDirectories(p); fs.exists(p) }
        } catch (e: Exception) { false }
    }

    override fun readText(path: String): String = fs.read(path.toPath()) { readUtf8() }

    override fun writeText(path: String, text: String) {
        fs.write(path.toPath()) { writeUtf8(text) }
    }

    /** File.renameTo(): FALSE on failure, no throw. See note 3. */
    override fun rename(from: String, to: String): Boolean =
        try { fs.atomicMove(from.toPath(), to.toPath()); true } catch (e: Exception) { false }

    /** File.length(): 0L for a missing file. */
    override fun length(path: String): Long =
        try { fs.metadataOrNull(path.toPath())?.size ?: 0L } catch (e: Exception) { 0L }

    /** context.assets.open(name) -- through the ONE bundle reader, shared with the asset socket. */
    override fun readAsset(name: String): String = iosBundleAsset(name)
}
