package com.grouptrack.shared.comm

import com.grouptrack.comm.CommMenuItem
import com.grouptrack.comm.CommMenus
import com.grouptrack.comm.CommResult
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.platform.GtBluetoothSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * IOSGRP2-2026-11-22: the iOS half of G - CommMenus, which did not exist. Measured 11-22:
 * not one implementation anywhere in iosMain, only comments referring to the interface.
 *
 * Fred 13:08: the iOS Bluetooth settings panel is called FROM OUR MENU, the way
 * MeshCommMenus opens Meshtastic's screens. So GroupTrack asks by id and never names an
 * OS screen; this class is the only place that knows the id means Bluetooth.
 *
 * Fred 12:59: ONE entry covers turning Bluetooth on, turning it off AND forgetting a
 * radio, because iOS does all three on the same page. Three entries pointing at one page
 * would be three times the noise.
 *
 * Mirrors MeshCommMenus: ids in the companion, [CommResult.NotSupported] for any id this
 * side does not offer.
 */
class GtIosCommMenus(private val bluetooth: GtBluetoothSettings) : CommMenus {

    private val log = gtLogger("GtIosCommMenus")

    override fun items(): List<CommMenuItem> = listOf(
        CommMenuItem(id = BLUETOOTH_SETTINGS, label = "iOS Bluetooth", group = "ios"),
    )

    /**
     * ⛔ ALWAYS NULL, AND THAT IS A TRUE ANSWER, NOT A STUB. Android's openMenu tracks a
     * comm-side screen drawn over GroupTrack inside its own shell. iOS Settings is a
     * SEPARATE APP: nothing is ever open "over" GroupTrack, so the contract's own meaning
     * of null -- "none (GroupTrack's own screens)" -- is exactly right.
     */
    override val openMenu: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

    /**
     * ⛔ NOT SUPPORTED, and truthfully so: iOS Settings is not GroupTrack's to close. The
     * rider comes back with the app switcher. Reporting Ok would claim an act that never
     * happened.
     */
    override fun close(id: String): CommResult = CommResult.NotSupported

    override fun open(id: String): CommResult = when (id) {
        BLUETOOTH_SETTINGS ->
            // THE ONE PLATFORM ACT, through the declared service -- never a UIKit call from
            // a panel. opensBluetoothDirectly is false on iOS, so the words on screen must
            // not promise the Bluetooth switch; that is the panel's job, not this one's.
            if (bluetooth.open()) {
                CommResult.Ok
            } else {
                log.i { "IOSGRP2-2026-11-22: iOS would not open Settings" }
                CommResult.Failed("SETTINGS_REFUSED")
            }
        else -> CommResult.NotSupported
    }

    companion object { const val BLUETOOTH_SETTINGS = "bluetooth-settings" }
}
