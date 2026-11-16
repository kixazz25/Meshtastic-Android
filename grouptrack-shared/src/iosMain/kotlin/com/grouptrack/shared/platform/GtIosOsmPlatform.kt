package com.grouptrack.shared.platform

import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.osm.GtOsmPlatform
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber

/**
 * GTIOSOSM-2026-11-16: the iPad's GtOsmPlatform.
 *
 * FIVE MEMBERS: TWO DO REAL WORK, THREE HAVE NOTHING TO DO -- and "nothing to do" is not the same as
 * "not implemented". Per SVCFIRST-2026-11-16 an absent service must throw and name itself; a member
 * whose Android body has no iOS counterpart to perform is EMPTY ON PURPOSE, and the reason is recorded
 * here so a later reader cannot mistake one for the other.
 *
 *   onOsmRoot            EMPTY  -- the interface itself says so: "The iPad has no legacy folder
 *                                  (nothing to do)". Android rescues files stranded in the old
 *                                  getExternalFilesDir()/osm; that directory has never existed on iOS.
 *   discardLegacy        EMPTY  -- same reason. Android sweeps File(legacyRootDir(), slug), and
 *                                  legacyRootDir() is getExternalFilesDir(null)/osm. No legacy root,
 *                                  nothing stranded, nothing to sweep.
 *   usableSpace          REAL   -- the extract's free-space gate.
 *   sweepDownloadedZips  REAL    -- but over a SMALLER SET than Android. See below.
 *   foreground           EMPTY  -- and this one is BEHAVIOURALLY IDENTICAL to Android, measured
 *                                  11-16: GtOsmAndroid builds a Notification and a ForegroundInfo and
 *                                  then DISCARDS it -- @Suppress("UNUSED_VARIABLE") val info = ... with
 *                                  the note "INPROCESS-2026-10-02: setForegroundAsync is a no-op shim
 *                                  -- the import screen shows progress." The only Android side effect
 *                                  lost here is creating a notification channel nothing ever posts to.
 *                                  When Phase 5 brings UNUserNotificationCenter this is where progress
 *                                  would go, and not before.
 *
 * ⚠ sweepDownloadedZips SEES LESS THAN ANDROID, AND THAT IS A SANDBOX LIMIT, NOT A PORT DEFECT.
 * Android deletes the BROWSER's copies of the state zip out of the public Downloads folder. iOS has no
 * public Downloads folder an app may enumerate: Safari's downloads land in the user's Files provider,
 * outside this app's container, and no API reaches them. So this sweeps Documents/Download -- the
 * directory GroupTrack itself writes through IosImportPlatform.saveToDownloads, resolved the same way
 * (the root's parent / "Download") so both agree on one place. A zip the user downloaded in Safari is
 * invisible to us and stays on the device. The regex, the per-file logging and the summary line are
 * Android's, unchanged, so the log reads the same for the files we CAN see.
 *
 * ⚠ usableSpace REPORTS VOLUME FREE SPACE, where Android's File.usableSpace reports what is available
 * to the app after quotas. On iOS there is no per-app quota of that kind, so the volume figure is the
 * closest true analogue. Failure returns 0L, matching java.io.File.usableSpace's own behaviour on an
 * unreadable path -- and 0 fails the free-space gate CLOSED, which is the safe direction for a gate
 * that decides whether an extract may start.
 */
class IosOsmPlatform(
    private val paths: GtImportPaths = IosImportPaths(),
    private val fs: FileSystem = FileSystem.SYSTEM,
) : GtOsmPlatform {

    private val log = gtLogger("GtIosOsm")

    /** EMPTY ON PURPOSE -- no legacy getExternalFilesDir()/osm has ever existed on iOS. */
    override fun onOsmRoot(root: Path) {
        // nothing to rescue; see the class note
    }

    /** EMPTY ON PURPOSE -- no legacy root, so nothing is stranded under it for [slug]. */
    override fun discardLegacy(slug: String) {
        // nothing to sweep; see the class note
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun usableSpace(dir: Path): Long {
        val attrs = try {
            NSFileManager.defaultManager.attributesOfFileSystemForPath(dir.toString(), null)
        } catch (e: Exception) {
            log.w { "usableSpace: ${dir} unreadable: ${e.message}" }
            null
        } ?: return 0L
        val free = attrs[NSFileSystemFreeSize] as? NSNumber ?: return 0L
        return free.longLongValue
    }

    /** 2.7e OsmExtractWorker's download sweep, over the only Download directory iOS lets us see. */
    override fun sweepDownloadedZips(slug: String) {
        val dl = paths.groupTrackRoot().parent?.let { it / "Download" } ?: return
        if (!fs.exists(dl)) {
            log.i { "downloads swept for $slug: 0 file(s), 0 MB" }   // Android's summary line, same shape
            return
        }
        val zipPat = Regex(
            "^" + Regex.escape(slug) +
                "-(?:latest|\\d{6,8})-free\\.gpkg(?:\\s*\\(\\d+\\))?" +
                "\\.zip(?:\\s*\\(\\d+\\))?$",
            RegexOption.IGNORE_CASE
        )
        var removed = 0
        var freedMb = 0L
        val entries = try { fs.list(dl) } catch (e: Exception) { emptyList() }
        for (f in entries) {
            val name = f.name
            if (!zipPat.matches(name)) continue
            val md = try { fs.metadataOrNull(f) } catch (e: Exception) { null }
            if (md?.isDirectory == true) continue
            val mb = (md?.size ?: 0L) / 1048576L
            val ok = try { fs.delete(f); true } catch (e: Exception) { false }
            if (ok) {
                removed++; freedMb += mb
                log.i { "deleted download $name ($mb MB)" }
            } else {
                log.w { "could not delete download $name" }
            }
        }
        log.i { "downloads swept for $slug: $removed file(s), $freedMb MB" }
    }

    /**
     * EMPTY ON PURPOSE, and identical in effect to Android -- GtOsmAndroid builds the notification and
     * throws the ForegroundInfo away (INPROCESS-2026-10-02). Progress is the import screen's job.
     */
    override fun foreground(worker: String, text: String) {
        // no foreground promotion on iOS, and none on Android either; see the class note
    }
}
