package com.grouptrack.shared.platform

/**
 * SCREENCAP-2026-11-20: capture what the rider is looking at, as a file.
 *
 * Its own socket because three unrelated consumers need the same thing: the ISSUE control on every
 * panel (ISSUE-2026-10-24, through GtPanel's header, next to HELP), the ride-statistics email -- which
 * puts the map screenshot FIRST of five attachments -- and any future report a rider sends us.
 *
 * ANDROID IS THE SPECIFICATION (GtIssue.kt:115-127): PixelCopy on the window's decorView, JPEG at
 * quality 85, named `issue_<stamp>_<n>.jpg` and APPENDED to a list, because the CAPTURE bar lets a
 * rider walk several screens into one report. Hence [capture] taking a file name and [clear] starting
 * a fresh report rather than a single "take a screenshot" call.
 *
 * ⛔ NEITHER MEMBER MAY THROW. A capture that crashes the app loses the report AND the thing being
 * reported. [capture] returns null when it could not produce a file, and says why in the log.
 *
 * The handle it returns is a platform handle for a written file -- a path on iOS -- so it feeds
 * [GtArtifactSink.emailAll] directly with no conversion.
 */
interface GtScreenCapture {

    /**
     * Capture the current screen into [fileName] inside this service's own folder.
     * Returns a platform handle for the written file, or null if nothing could be captured.
     *
     * ⚠ Call it from the MAIN thread: both platforms read live view state.
     */
    fun capture(fileName: String): String?

    /**
     * Empty the folder, as Android does when a report starts
     * (GtRideStatsSend: `mkdirs(); listFiles()?.forEach { it.delete() }`).
     * Returns how many files were removed, so a caller can log it rather than assume.
     */
    fun clear(): Int
}
