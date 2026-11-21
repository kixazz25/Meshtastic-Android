package com.grouptrack.shared.platform

import com.grouptrack.shared.concurrent.GtLock

/**
 * ASSETVER-2026-11-21 -- WHICH VERSION OF A BUNDLED ASSET IS ACTUALLY LOADED.
 *
 * Fred 11-21 07:06, asking for this rather than the host-local var it replaced: "was the html item
 * built and placed in common services so we cataloged another method in case it is needed again."
 *
 * ⭐ THE NEED. A bundled web asset carries its own version INSIDE it, so the only honest way to know
 * which copy is running is to let the asset say so once it loads. Android has done this for the map
 * page since HTMLVER-2026-08-13B: ConvoyConfig.MAP_HTML_VERSION starts at "not reported" and
 * GroupTrackScreen.kt:1025 assigns it from the page. That cost an evening of bisecting on 08-11, when
 * a stale bundled asset could not be SEEN. On a SIDELOADED build with no store version, it is the
 * only way to tell which copy is in your hands.
 *
 * ⭐ WHY A REGISTRY AND NOT ONE STRING. The map page is the first bundled asset to report, not the
 * last -- the help HTML and the manual are bundled too. One hardcoded field would have to be built
 * again for the second asset; a key costs nothing. MAP_HTML is the only key WIRED today.
 *
 * ⛔ NOBODY REPORTS YET. of(MAP_HTML) returns NOT_REPORTED until the vecmap page reports back
 * (C28-26363 MAPHTMLREPORT, parked to J-Map). That is not a stub and not a defect: it is exactly what
 * Android shows before a map has been opened, and ConvoyConfig's own default says the same.
 * ⚠ So NOT_REPORTED is never evidence that the wire works. A VERSION STRING is.
 *
 * THREADING: a page callback is not guaranteed to be the UI thread, so every access goes through
 * GtLock (GTLOCK-2026-11-20, reentrant on both platforms). This is that lock's first consumer.
 */
object GtAssetVersion {

    /** What every asset reads as until it reports. The same words Android uses (ConvoyConfig.kt:30). */
    const val NOT_REPORTED: String = "not reported"

    /** The vecmap page. The only key wired today. */
    const val MAP_HTML: String = "map_html"

    private val lock = GtLock()
    private val reported = mutableMapOf<String, String>()

    /**
     * The asset reporting its own version, once, as it loads. Trimmed, because the value comes off a
     * page and arrives with whitespace -- Android trims it the same way before assigning.
     *
     * An EMPTY or blank version is NOT a report: it clears the entry rather than storing "", so
     * of() keeps answering NOT_REPORTED instead of a blank line on the settings screen. An asset that
     * cannot name its version has told us nothing, and the screen should say so.
     */
    fun report(asset: String, version: String) {
        val clean = version.trim()
        lock.withLock {
            if (clean.isEmpty()) reported.remove(asset) else reported[asset] = clean
        }
    }

    /** The version that asset reported, or NOT_REPORTED. Never null -- the screen always has a line. */
    fun of(asset: String): String = lock.withLock { reported[asset] ?: NOT_REPORTED }

    /** Every asset that has reported, sorted. For a diagnostics screen or an ISSUE report. */
    fun reportedAssets(): List<String> = lock.withLock { reported.keys.sorted() }

    /** Drops every report. For tests only -- nothing in the app clears a version it has learned. */
    fun resetForTest() { lock.withLock { reported.clear() } }
}
