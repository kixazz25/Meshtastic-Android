/*
 * ARTIFACTSVC-2026-11-19 (2.8b): the iPad's half of the share/export service.
 *
 * GtArtifactFiles (commonMain) owns the work -- build the GPX, name the file, write it, then hand
 * it over. Only two things are platform-bound and both are here:
 *     saveToMapFeatures   where the file goes
 *     email               how it leaves
 *
 * WHY UIActivityViewController AND NOT A mailto: URL. Fred 11-17: "share should become email",
 * and ConvoyArtifactOps.kt:291 recorded the reason as "the iPad has no Android share sheet, so
 * email is the one mechanism both platforms have." iOS DOES have a share sheet, and it is the
 * closer match: Android's share is Intent.createChooser, whose whole behaviour is "pick your mail
 * app" (EMAILHOUSE-2026-11-17). UIActivityViewController with the file URL is that same choice,
 * with Mail in it. A mailto: URL cannot carry an attachment at all, so it would deliver less.
 */
package com.grouptrack.shared.platform

import com.grouptrack.shared.artifact.GtArtifactSink
import com.grouptrack.shared.artifact.GtTrackFileReader
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.storage.GtStorage
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIModalPresentationFormSheet
import platform.UIKit.UIView
import platform.UIKit.UIViewController

private val log = gtLogger("GtIosArtifact")

/**
 * ⚠ THE FOLDER NAME IS A SECOND COPY. Android's is GtFileOpen.Family.MAP_FEATURES("GroupTrack Map
 * Features") -- an app-side enum, so it cannot be imported here. The two must agree or a file
 * exported on one platform lands somewhere the other does not look. Moving that enum to commonMain
 * is the fix and belongs with the app-side switchover, NOT today.
 */
private const val MAP_FEATURES_DIR = "GroupTrack Map Features"

class IosArtifactSink(private val storage: GtStorage = iosStorage()) : GtArtifactSink {

    override fun saveToMapFeatures(fileName: String, text: String): String? = try {
        val fs = okio.FileSystem.SYSTEM
        val dir = storage.root().toString().toPath() / MAP_FEATURES_DIR
        fs.createDirectories(dir)
        val target = dir / fileName
        fs.write(target) { writeUtf8(text) }
        log.i { "ARTIFACTSVC-2026-11-19: wrote " + target }
        target.toString()
    } catch (e: Throwable) {
        log.e { "ARTIFACTSVC-2026-11-19: saveToMapFeatures(" + fileName + ") failed: " + e.message }
        null
    }

    /**
     * ⚠ @OptIn kept on this ONE function, not the file -- GtVecMap.ios.kt's pattern, so the
     * cinterop surface stays visible where it is actually used.
     */
    override fun email(handle: String, fileName: String, subject: String, body: String): Boolean {
        // EMAILALL-2026-11-20: one attachment is the one-item case. Kept as its own member because
        // the interface declares it and its log line carries the file name.
        val ok = emailAll(listOf(handle), subject, body)
        if (ok) log.i { "ARTIFACTSVC-2026-11-19: share sheet presented for " + fileName }
        else log.e { "ARTIFACTSVC-2026-11-19: email(" + fileName + ") did not present" }
        return ok
    }

    /**
     * EMAILALL-2026-11-20: the multi-attachment send, and the ONE place the presentation lives --
     * the single-file send above now goes through it, so the two cannot drift.
     *
     * ⚠ The [subject] is ACCEPTED AND DROPPED, as it was for the single-file send.
     * UIActivityViewController takes no subject from an item list; carrying one needs a
     * UIActivityItemSource implementing activityViewController(_:subjectForActivityType:). Android
     * sets EXTRA_SUBJECT. A REAL DELTA, named here rather than left to be discovered.
     */
    @OptIn(ExperimentalForeignApi::class)
    override fun emailAll(handles: List<String>, subject: String, body: String): Boolean = try {
        if (handles.isEmpty()) {
            // CODE RULE 1: an empty list is a CALLER error, not "no mail app". Said, not swallowed.
            log.e { "EMAILALL-2026-11-20: no attachments given -- nothing presented" }
            return false
        }
        val items = ArrayList<Any>(handles.size + 1)
        items.add(body)
        handles.forEach { items.add(NSURL.fileURLWithPath(it)) }
        val vc = UIActivityViewController(items, null)
        val root = UIApplication.sharedApplication.keyWindow?.rootViewController
        if (root == null) {
            log.e { "EMAILALL-2026-11-20: no root view controller -- cannot present the share sheet for " + handles.size + " file(s)" }
            false
        } else {
            // ⚠ iPad: UIActivityViewController defaults to a POPOVER, and a popover with no
            // anchor raises NSInvalidArgumentException on present. The usual fix is to set
            // popoverPresentationController.sourceView, but that property is not in Kotlin/Native's
            // UIKit bindings here (measured: unresolved, 15:57 build). Asking for a FORM SHEET
            // instead removes the requirement rather than working around it -- a form sheet needs no
            // source rect, and it is a reasonable presentation for a share sheet on a 13-inch iPad.
            vc.setModalPresentationStyle(UIModalPresentationFormSheet)
            root.presentViewController(vc, animated = true, completion = null)
            log.i { "EMAILALL-2026-11-20: share sheet presented with " + handles.size + " attachment(s)" }
            true
        }
    } catch (e: Throwable) {
        log.e { "EMAILALL-2026-11-20: emailAll(" + handles.size + " file(s)) failed: " + e.message }
        false
    }

    /**
     * EMAILTEXT-2026-11-21: the body alone, no attachment. A SECOND service built BESIDE emailAll,
     * which is left exactly as it was -- Fred 07:31: "build the second service[,] flag the services to
     * be evaluated when we have completed this loop and tested [a]nd certified code. not before."
     * Whether the three email methods should later collapse into one is C28-26367, and it waits for a
     * certified baseline and a count of each one's consumers.
     *
     * Android does this inline today for the trail check: ACTION_SEND, type "text/plain",
     * EXTRA_SUBJECT and EXTRA_TEXT, through createChooser (ConvoySettingsScreen.kt:248-253).
     *
     * ⚠ The SUBJECT is dropped, as it is for email and emailAll -- UIActivityViewController takes none.
     * A caller that needs it visible puts it in the body's first line. This service invents no
     * fallback: composing the body is the caller's business, not the platform's.
     * ⚠ Same popover rule as emailAll: on iPad UIActivityViewController defaults to a POPOVER, and a
     * popover with no anchor raises NSInvalidArgumentException on present. sourceView is not in the
     * Kotlin/Native bindings, so the presentation style is forced to a form sheet instead.
     */
    @OptIn(ExperimentalForeignApi::class)
    override fun emailText(subject: String, body: String): Boolean = try {
        val root = UIApplication.sharedApplication.keyWindow?.rootViewController
        if (root == null) {
            log.e { "EMAILTEXT-2026-11-21: no root view controller -- cannot present the share sheet" }
            false
        } else {
            val vc = UIActivityViewController(listOf(body), null)
            vc.modalPresentationStyle = UIModalPresentationFormSheet
            root.presentViewController(vc, animated = true, completion = null)
            log.i { "EMAILTEXT-2026-11-21: presented the body alone (" + body.length + " chars); iOS drops the subject" }
            true
        }
    } catch (e: Throwable) {
        log.e { "EMAILTEXT-2026-11-21: " + (e.message ?: "failed") }
        false
    }
}

/**
 * A track's recorded .gpx, by geom_hash. MYTRACKSDB-2026-09-12: the file written at record time IS
 * the export -- it is never rebuilt, because rebuilding re-rounds every coordinate.
 * Android reads <ConvoyTrackOps.tracksDir()>/<geom_hash>.gpx; the iPad's tracks live under the
 * GroupTrack root in my_tracks, which is what the pulled baseline carries.
 */
class IosTrackFiles(private val storage: GtStorage = iosStorage()) : GtTrackFileReader {
    override fun readTrackGpx(geomHash: String): String? = try {
        val fs = okio.FileSystem.SYSTEM
        val p: Path = storage.root().toString().toPath() / "my_tracks" / (geomHash + ".gpx")
        if (!fs.exists(p)) {
            log.e { "ARTIFACTSVC-2026-11-19: no track file at " + p }
            null
        } else fs.read(p) { readUtf8() }
    } catch (e: Throwable) {
        log.e { "ARTIFACTSVC-2026-11-19: readTrackGpx(" + geomHash + ") failed: " + e.message }
        null
    }
}
