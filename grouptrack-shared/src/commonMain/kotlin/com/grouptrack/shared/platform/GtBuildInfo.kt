package com.grouptrack.shared.platform

/**
 * BUILDINFO-2026-11-20: WHICH BUILD IS ON THE GLASS, and what it is running on.
 *
 * Its own socket because it has two unrelated consumers, both found by walking A.MENU on 2026-11-20:
 *
 *  - THE SETTINGS BUILD STAMP. Android draws it at the bottom of its settings screen
 *    (ConvoySettingsScreen:430-447): "GroupTrack OG Rel. <release> <built> (<flavor> <build>)" and
 *    "Map HTML: <version>". HTMLVER-2026-08-13B records why it is there and not decoration -- a
 *    stale bundled asset date "cost an evening of bisecting on 08-11 because it could not be seen".
 *    The iPad has no such stamp at all, and a SIDELOADED build has no store version either, so
 *    knowing what is on the glass matters more there, not less.
 *
 *  - ANY DIAGNOSTIC THE RIDER SENDS US. TrailCheck.kt:40-42 puts the app version and the device
 *    line into the JSON the rider emails. A report that cannot say what produced it is worth less.
 *
 * ⛔ NEITHER MEMBER MAY THROW. A build stamp that crashes the settings screen is strictly worse
 * than an unknown version, so an implementation that cannot read its own metadata returns a string
 * that SAYS so. Android already treats it that way: TrailCheck wraps VERSION_NAME in try/catch and
 * falls back to "?". Returning "?" is a real answer here, not a swallowed failure -- it is the
 * honest statement "this build does not know its own version".
 */
interface GtBuildInfo {

    /**
     * The app's own version, as a rider would read it off a screen.
     * Android: BuildConfig.VERSION_NAME. iOS: CFBundleShortVersionString (CFBundleVersion).
     */
    fun appVersion(): String

    /**
     * What it is running on, one line, for a report or a stamp.
     * Android: MANUFACTURER + " " + MODEL + " / Android " + VERSION.RELEASE.
     */
    fun device(): String
}
