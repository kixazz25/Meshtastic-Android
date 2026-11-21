package com.grouptrack.shared.platform

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.db.gtOpenDatabase
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.net.GtNetworkGenerator
import com.grouptrack.shared.net.GtProfileStore
import com.grouptrack.shared.ride.GtRideEnv
import com.grouptrack.shared.ride.GtRideStore
import com.grouptrack.shared.ride.RideMe
import com.grouptrack.shared.text.GtLocalTime
import com.grouptrack.shared.text.GtTime
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate

/**
 * IOSRIDEENV-2026-11-21: the iPad's [GtRideEnv] -- the iOS ACTUAL of an existing shared contract, in the
 * same family as [IosRideFiles], [IosRideSpatial], [IosRideNetworks], [IosSettings] and [IosClock].
 *
 * NOTHING HERE IS A NEW SERVICE. Every member is filled by a service that already exists and is
 * certified; this file only wires them. `GtRideStore.checkIn` (ride/GtRides.kt:663) has been shared and
 * certified since step 14 and was unreachable for one reason: `GtRideStore.env` (:489) had no iOS value.
 *
 * ⚠ WHY NOBODY NOTICED: [GtRideEnv] is a class of function-typed properties with no member functions, so
 * the generated service index files it as a data holder rather than a service, and a service query can
 * never return it. Every Env-pattern class is hidden the same way.
 */
private val log = gtLogger("GtIosRideEnv")

private var spatialConn: SQLiteConnection? = null
private var dataConn: SQLiteConnection? = null

/** The two databases DBPROBE certified the iPad builds for itself, opened once and kept. */
private fun conn(name: String, current: SQLiteConnection?): SQLiteConnection? {
    if (current != null) return current
    return try {
        gtOpenDatabase(iosStorage().root().toString() + "/" + name, false)
    } catch (e: Throwable) {
        // CODE RULE 1: null = not open, which is exactly what GtRideEnv documents for these two.
        log.i { "IOSRIDEENV-2026-11-21: " + name + " not open -- " + e.message }
        null
    }
}

private fun spatialDb(): SQLiteConnection? {
    spatialConn = conn("grouptrack_spatial.db", spatialConn); return spatialConn
}

private fun dataDb(): SQLiteConnection? {
    dataConn = conn("grouptrack_data.db", dataConn); return dataConn
}

/** Android's nowUtc(), byte for byte: GtTime.formatUtcSeconds IS SimpleDateFormat's UTC rendering. */
private fun nowUtc(): String = GtTime.formatUtcSeconds(IosClock.epochMs())

/** The device's local date, the same NSCalendar components [IosClock.localNow] reads. */
private fun today(): String = GtTime.local(IosClock.localNow(), "yyyy-MM-dd")

/**
 * Android: Instant.ofEpochMilli(ms).atZone(systemDefault()).toLocalDate().toString().
 * The local zone matters, so this uses NSCalendar on the given date -- the same pattern
 * [IosClock.localNow] already uses for now.
 */
private fun epochMsToLocalDate(ms: Long): String {
    // IOSRECFIX-2026-11-21: NSDate has no timeIntervalSince1970 constructor in Kotlin/Native -- only
    // timeIntervalSinceReferenceDate. Foundation's reference date is 2001-01-01T00:00:00Z,
    // which is NSTimeIntervalSince1970 = 978300800 s after the epoch.
    val d = NSDate(timeIntervalSinceReferenceDate = ms / 1000.0 - 978300800.0)
    val c = NSCalendar.currentCalendar
    val u = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay
    val p = c.components(u, fromDate = d)
    return GtTime.local(GtLocalTime(p.year.toInt(), p.month.toInt(), p.day.toInt(), 0, 0, 0), "yyyy-MM-dd")
}

/** GtProfileStore is already the iPad's profile reader; the RideMe mapping is GtRidesAndroid.kt:46-48. */
private fun profile(): RideMe? =
    GtProfileStore(::dataDb, ::nowUtc, IosIds::newId).load()?.let { p ->
        RideMe(p.userId, p.firstName, p.lastName, p.callsign, p.vehicleType, p.team, p.email)
    }

/**
 * Set [GtRideStore.env] for the iPad. Idempotent and cheap: assigning the same shape twice is harmless,
 * and `lateinit`'s isInitialized cannot be read from outside its own class, so there is nothing to test.
 * Called before the first ride-store use rather than at startup, so a failure to open a database shows
 * up where the rider is, not behind the splash.
 */
fun installIosRideEnv() {
    GtRideStore.env = GtRideEnv(
        spatialDb = ::spatialDb,
        dataDb = ::dataDb,
        nowUtc = ::nowUtc,
        today = ::today,
        epochMsToLocalDate = ::epochMsToLocalDate,
        newId = IosIds::newId,
        profile = ::profile,
        networks = IosRideNetworks(db = ::dataDb),
        defaultChannel = GtNetworkGenerator.DEFAULT_CHANNEL,
        spatial = IosRideSpatial(spatialDb() ?: error("IOSRIDEENV-2026-11-21: the spatial database is not open"), dataDb()),
        packageText = { json -> GtOrgJson.pretty(GtOrgJson.parse(json), 2) },
    )
}
