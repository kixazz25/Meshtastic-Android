package com.grouptrack.shared.platform

import com.grouptrack.shared.log.gtLogger
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * BTSETTINGS-2026-11-21: the iPad's half of [GtBluetoothSettings].
 *
 * ⛔ iOS CANNOT OPEN THE BLUETOOTH PANE AND CANNOT TOGGLE THE RADIO. There is no public API for either.
 * The App-Prefs: URL that appears in older answers is private API and gets an app rejected, so it is not
 * used here. What iOS can legitimately do is open the APP'S OWN settings page
 * (UIApplicationOpenSettingsURLString), from which the rider reaches Bluetooth themselves.
 *
 * That is why [opensBluetoothDirectly] is false: a caller must say "turn Bluetooth on in Settings"
 * rather than "here is the Bluetooth switch". Reporting the limitation is the service's job; pretending
 * the platforms match would put a wrong sentence on the rider's screen.
 */
class IosBluetoothSettings : GtBluetoothSettings {

    private val log = gtLogger("GtIosBluetoothSettings")

    override val opensBluetoothDirectly: Boolean = false

    override fun open(): Boolean {
        val url = NSURL(string = UIApplicationOpenSettingsURLString)
        val app = UIApplication.sharedApplication
        return if (app.canOpenURL(url)) {
            app.openURL(url)
            true
        } else {
            // Said out loud: a silent false would look like the rider simply did not tap.
            log.i { "BTSETTINGS-2026-11-21: iOS refused the app settings URL" }
            false
        }
    }
}
