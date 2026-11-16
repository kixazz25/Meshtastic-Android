package com.grouptrack.shared.platform

import com.grouptrack.shared.imports.GtImportPlatform
import com.grouptrack.shared.log.gtLogger
import okio.FileSystem
import okio.Path

/**
 * CERTPATHS-2026-11-16: the iPad's GtImportPlatform -- the EXPORT and ASSET half of seam 2.
 *
 * Fred 11-16 09:50: "the key variant is an export file downloaded. that is going to be the real test."
 * Fred 11-16 09:27: "we are not changing the android target for exports. they remain in download, in
 *   fact I would make the case the the iPad version should duplicate the android solution. lets not
 *   introduce a implementation specific difference when we do not have to."
 *
 * WHAT IS REAL HERE, AND WHAT IS NOT. Two of the four members are implemented; two are not, and the
 * reason is recorded rather than hidden behind a bare throw:
 *   readAsset        REAL -- 7 shared call sites (osm_layers.json, trail_sources.json, the schema SQL)
 *   saveToDownloads  REAL -- 2 shared call sites, GtGpxImport:153, the export Fred named
 *   enqueueCorridor  NOT WIRED -- needs the iOS download queue. GtQueuePlatform has NO implementation
 *                    on EITHER platform yet, so the queue is not reached through the socket at all.
 *                    That is the download-queue seam, not this one.
 *   onTrailsChanged  NOT WIRED -- Android sets RiderTrailWriter.trailsChanged, a UI flow the iPad app
 *                    does not have yet. It exists to make the map reload; there is no map to reload.
 *
 * ⚠ BOTH UNWIRED MEMBERS ARE ON THE IMPORT PATH, NOT THE EXPORT PATH (GtGpxImport:345/:486 and
 * GtRiderTrails:282/:440). So the export test Fred asked for runs without them. iOS GPX IMPORT does
 * not, and must not be claimed as working until they are.
 *
 * COLLISION NAMING IS AN EXPECTED DIVERGENCE, NOT A DEFECT TO CHASE. ImportConverter.saveToDownloads
 * has two branches that already disagree with each other: below SDK 29 it renames a collision to
 * "${'$'}{nameWithoutExtension}_${'$'}n.gpx"; at SDK 29+ (what the app actually targets) MediaStore does its
 * own dedup and the function returns whatever DISPLAY_NAME MediaStore handed back. No file-system copy
 * can reproduce MediaStore's naming, so this mirrors the pre-29 branch -- a deliberate, recorded
 * choice. The NON-colliding case is strictly comparable: same name, same bytes, all three legs.
 *
 * The Foundation calls are copied from code that compiles (GtIosEnv.kt) -- not from Apple's
 * documentation. Writing Foundation calls from documentation is what broke the iOS build on 11-15.
 */
class IosImportPlatform(
    private val paths: IosImportPaths = IosImportPaths(),
    private val fs: FileSystem = FileSystem.SYSTEM,
) : GtImportPlatform {

    private val log = gtLogger("GtIosImport")

    /**
     * Android: app.assets.open(name).bufferedReader().use { it.readText() }.
     * iOS: the same file, shipped in the app bundle.
     * ⚠ MUST THROW when the asset is missing, exactly as Android's assets.open does -- GtHelpLinks.load
     * and GtOsmLayers both wrap this in runCatching and depend on the throw to fall back.
     */
    override fun readAsset(name: String): String = iosBundleAsset(name)

    /**
     * Android: the converted file copied into public Download, returning the NAME it got.
     * iOS: Documents/Download, which is the rider-visible folder once the app target sets
     * UIFileSharingEnabled -- the same shape and the same folder name, per Fred 09:27.
     * Returns the final name, as Android does (it may differ from the input on a collision).
     */
    override fun saveToDownloads(file: Path): String {
        val dir = paths.groupTrackRoot().parent?.let { it / "Download" }
            ?: throw IllegalStateException("CERTPATHS-2026-11-16: no parent for the GroupTrack root")
        fs.createDirectories(dir)

        val name = file.name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name

        var target = dir / name
        var n = 2
        while (fs.exists(target)) {
            target = dir / (stem + "_" + n + ".gpx")   // the pre-29 branch, mirrored deliberately
            n++
        }
        // ⛔ okio's own copy. The first draft of this line was
        //    k.write(Buffer().also { s.read(it, Long.MAX_VALUE) }, 0)
        // which writes ZERO bytes (write's second argument is the byte count) and would have left an
        // EMPTY file in Download while still returning a name -- an export that looks like it worked.
        // Caught 11-16 09:54 before the first compile.
        fs.copy(file, target)
        log.i { "CERTPATHS-2026-11-16 export -> Download/" + target.name }
        return target.name
    }

    /** ⛔ NOT WIRED -- the iOS download queue does not exist (GtQueuePlatform: no impl either side). */
    override fun enqueueCorridor(geomHash: String, slot: String, replaceExisting: Boolean): Int =
        throw UnsupportedOperationException(
            "CERTPATHS-2026-11-16: enqueueCorridor needs the iOS download queue (GtQueuePlatform is " +
                "unimplemented on both platforms). Reached only from the GPX IMPORT path " +
                "(GtGpxImport:345/:486), never from export."
        )

    /** ⛔ NOT WIRED -- Android sets RiderTrailWriter.trailsChanged so the map reloads; no iPad map yet. */
    override fun onTrailsChanged() {
        log.w { "CERTPATHS-2026-11-16: onTrailsChanged ignored -- the iPad has no map to reload yet" }
    }
}
