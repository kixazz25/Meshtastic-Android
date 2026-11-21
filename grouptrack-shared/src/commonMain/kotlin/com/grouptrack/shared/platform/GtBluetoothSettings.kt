package com.grouptrack.shared.platform

/**
 * BTSETTINGS-2026-11-21 (2.8b, D - GRP Awareness): take the rider to where Bluetooth is turned on.
 *
 * THE ONE PLATFORM ACT IN D. Everything else GRP Awareness needs is a comm-module API call -- the radio
 * list, the scan, the scan's reason (BLUETOOTH_OFF / PERMISSION_MISSING, which `CommLink` reports live),
 * connect, pair, forget, the networks, and the ride apply. Only this act belongs to the platform, which
 * is why Android carries it as an Intent inline in GrpAwareness.kt:264 and why that one line is what
 * stops GrpAwarenessPanel compiling in commonMain.
 *
 * ⛔ THE TWO PLATFORMS CANNOT DO THE SAME THING, and that belongs here rather than in the panel:
 *  - Android opens the Bluetooth settings screen directly (Settings.ACTION_BLUETOOTH_SETTINGS).
 *  - iOS has NO public way to open the Bluetooth pane or to switch the radio on. The App-Prefs: deep
 *    link is private API and fails App Review, so the honest act is the app's OWN settings page.
 * [open] therefore reports what it managed, and [opensBluetoothDirectly] lets a caller word its message
 * truthfully -- "turn Bluetooth on in Settings" rather than promising a screen it cannot show.
 */
interface GtBluetoothSettings {

    /**
     * Take the rider to where Bluetooth can be turned on. Returns false when the platform refused or
     * had nowhere to go -- a caller should then say so rather than assume a screen appeared.
     */
    fun open(): Boolean

    /**
     * True where [open] lands on the Bluetooth control itself (Android), false where it can only reach
     * a settings page from which the rider navigates (iOS). Not cosmetic: it decides whether the words
     * on screen can promise the Bluetooth switch.
     */
    val opensBluetoothDirectly: Boolean
}
