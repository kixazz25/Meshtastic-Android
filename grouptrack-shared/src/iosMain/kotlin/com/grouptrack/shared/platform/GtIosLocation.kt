package com.grouptrack.shared.platform

import com.grouptrack.shared.log.gtLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLHeading
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.darwin.NSObject

/**
 * IOSLOC-2026-11-19: the iPad's own position and course.
 *
 * Two launch points need it and nothing in iosMain had any CoreLocation at all:
 *   I.CENTRE ON ME -- setView(lat, lon, GtConfig.MAP_CART_ZOOM), as GroupTrackScreen.kt:3142
 *   F.Map view "My dir" -- the page's gtMyHeading(heading, speed), as GroupTrackScreen.kt:486
 *     (VECHEADING-2026-10-30: "my course + speed to the page every tick")
 *
 * ⚠ COURSE FIRST, HEADING SECOND. Android feeds the CART's heading_deg -- the node's reported
 * course over ground. The iPad has no cart, so CLLocation.course is the true analogue, and it is
 * what heading-up on a moving vehicle should follow. CLHeading.trueHeading (the magnetometer) is
 * the standing-still fallback. CoreLocation reports both as NEGATIVE when invalid, so both are
 * rejected on that test rather than trusted.
 *
 * ⚠ SPEED IS METRES PER SECOND from CoreLocation and MPH at the page (node.speed_mph).
 *
 * The state lives in the delegate, not in this object: a nested class cannot reach an outer
 * object's private members, and routing it through the delegate keeps one owner for all of it.
 */
@OptIn(ExperimentalForeignApi::class)
internal object GtIosLocation {

    private val log = gtLogger("GtIosLocation")
    private var mgr: CLLocationManager? = null
    private var del: Del? = null

    /** Called whenever a fresh course/heading or speed arrives: (degrees, mph). */
    var onHeading: ((Double, Double) -> Unit)?
        get() = del?.onHeading
        set(v) { del?.onHeading = v }

    /** Idempotent: the first call asks for permission and starts the updates. */
    fun start() {
        if (mgr != null) return
        val d = Del(log)
        val m = CLLocationManager()
        m.delegate = d
        m.desiredAccuracy = kCLLocationAccuracyBest
        m.requestWhenInUseAuthorization()
        m.startUpdatingLocation()
        // The simulator has no magnetometer, so this is usually false there -- course still works.
        if (CLLocationManager.headingAvailable()) m.startUpdatingHeading()
        else log.i { "IOSLOC-2026-11-19: no heading hardware -- course only (expected on the simulator)" }
        del = d
        mgr = m
        log.i { "IOSLOC-2026-11-19: started" }
    }

    /**
     * IOSREADOUT-2026-11-19: the GPS lamp, as ConvoyViewModel.gpsLamp() gives Android.
     *
     * ⚠ Android is green at 4+ SATELLITES (TabletPosition.satsInFix()). CoreLocation exposes NO
     * satellite count, so this judges horizontalAccuracy and SHOWS it -- the number on screen is
     * the number being judged, rather than a count the platform cannot supply.
     * 50 m is the threshold: a usable road/trail fix, which is what 4 satellites buys.
     */
    fun lamp(): Pair<Boolean, String> {
        val d = del
        val acc = d?.accuracy ?: -1.0
        return if (d == null || !d.fixed || acc < 0.0) Pair(false, "NO MY DEVICE GPS")
        else Pair(acc <= 50.0, "MY DEVICE GPS \u00b1" + acc.toInt() + "m")
    }

    /** [lat, lon], or null when there is no fix yet -- the caller must say so, never guess one. */
    fun here(): DoubleArray? = del?.let { if (it.fixed) doubleArrayOf(it.lat, it.lon) else null }

    private class Del(private val log: co.touchlab.kermit.Logger) : NSObject(), CLLocationManagerDelegateProtocol {
        var lat = 0.0
        var lon = 0.0
        var fixed = false
        var courseDeg = -1.0
        var headingDeg = -1.0
        var speedMph = 0.0
        var accuracy = -1.0   // IOSREADOUT-2026-11-19: metres; CoreLocation gives negative when invalid
        var onHeading: ((Double, Double) -> Unit)? = null

        private fun push() {
            val h = if (courseDeg >= 0.0) courseDeg else headingDeg
            if (h >= 0.0) onHeading?.invoke(h, speedMph)
        }

        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val loc = didUpdateLocations.lastOrNull() as? CLLocation ?: return
            loc.coordinate.useContents { lat = latitude; lon = longitude }
            fixed = true
            accuracy = loc.horizontalAccuracy
            courseDeg = if (loc.course >= 0.0) loc.course else -1.0
            // CLLocation.speed is m/s and negative when invalid; the page wants mph.
            speedMph = if (loc.speed >= 0.0) loc.speed * 2.236936 else 0.0
            push()
        }

        override fun locationManager(manager: CLLocationManager, didUpdateHeading: CLHeading) {
            headingDeg = if (didUpdateHeading.trueHeading >= 0.0) didUpdateHeading.trueHeading else -1.0
            push()
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            log.i { "IOSLOC-2026-11-19: " + didFailWithError.localizedDescription }
        }
    }
}
