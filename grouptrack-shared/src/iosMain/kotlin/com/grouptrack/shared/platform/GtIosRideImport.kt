package com.grouptrack.shared.platform

import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.ride.RideImportPlatform

/**
 * IOSRIDEIMP-2026-11-17: RideImportPlatform on the iPad. All three members are PLATFORM ABSENCES, and
 * each one is handled differently on purpose -- "not available here" is not one behaviour.
 *
 * ⚠ NONE OF THEM IS A BARE NO-OP. A silent no-op is the failure mode this project keeps paying for
 * (GtHelpLinks and GtMapSpec both catch-and-fall-back; the vecmap server answers a miss with an empty
 * HTTP 200), so each member either KEEPS the work for a consumer that is coming, or says in the log
 * exactly what did not happen and why.
 */
class IosRideImportPlatform : RideImportPlatform {

    private val log = gtLogger("GtIosRideImport")

    /**
     * 2.7e: MapSourceManager.init(context.applicationContext) -- "real sources, even from cold"
     * (MAPINIT-2026-09-24).
     *
     * ⭐ A LOGGED NO-OP, and here that is correct rather than lazy: there is no MapSourceManager on the
     * iPad (the registry is the same piece GtRecipeEnv.slotSources refuses over), and nothing in the
     * import path reads a slot -- the corridor's live path is the VECTOR one, whose enqueue ignores
     * `slot` entirely and queues VEC + DEM itself (IOSCORRIDOR-2026-11-17). So no caller is deprived of
     * anything this call would have provided.
     * ⛔ WHAT WOULD MAKE THIS WRONG: porting the registry (slotSources) without coming back here. At
     * that point this must initialise it, and a no-op becomes a real defect -- an import that reads
     * stale or empty sources and succeeds anyway.
     */
    override fun initMapSources() {
        log.i { "IOSRIDEIMP-2026-11-17: initMapSources is a no-op -- no map-source registry on iOS yet" }
    }

    /**
     * 2.7e: RideImportLauncher.offer(File(gpxPath)) -- hands the staged GPX to the EXISTING import panel
     * (RIDEIMPORT2-2026-09-24: "reuse is everything"), which ui/Main.kt then shows with the file already
     * listed and ticked, no picker.
     *
     * ⭐ THE WORK IS KEPT, NOT DROPPED. [IosRideImportLauncher] is the same one-field holder, so when the
     * iPad grows that screen it reads the same handoff Android reads. Until then the WARN is the point:
     * a ride was imported and staged, and no surface has shown it to the rider. That is a real gap and
     * it says so on every occurrence rather than once in a design note.
     */
    override fun offer(gpxPath: String) {
        IosRideImportLauncher.offer(gpxPath)
        log.w {
            "IOSRIDEIMP-2026-11-17: offer(" + gpxPath + ") held -- the staged ride GPX is waiting in " +
                "IosRideImportLauncher.pending, and NO iPad screen consumes it yet, so the rider will " +
                "not be shown the import. Android's ui/Main.kt reads the equivalent holder."
        }
    }

    /**
     * 2.7e: getLaunchIntentForPackage + startActivity (NEW_TASK | CLEAR_TOP | SINGLE_TOP).
     *
     * ⭐ EMPTY ON PURPOSE AND PERMANENTLY, which makes it different from the other two. iOS does not let
     * an application bring ITSELF to the foreground -- there is no API, by design, and there is no
     * equivalent coming. This is not a seam waiting to be closed; it is a documented divergence, and the
     * log line exists so that a rider report of "the app did not come forward on the iPad" resolves to
     * this line instead of a search.
     */
    override fun bringForward() {
        log.i { "IOSRIDEIMP-2026-11-17: bringForward ignored -- iOS has no way for an app to foreground itself" }
    }
}

/**
 * IOSRIDEIMP-2026-11-17: 2.7e RideImportLauncher, on the iPad -- the one-field handoff between the file
 * receiver and the import screen.
 *
 * ⚠ [pending] is null BY DESIGN, which is 2.7e's own CODE RULE 1 note on this object: there is usually
 * no ride waiting to be imported. A path rather than a File because iOS has no java.io.File.
 * ⚠ NOT observable yet. Android's holder is Compose state, so the panel recomposes the moment a ride is
 * offered. Whatever reads this on the iPad must drive a SwiftUI observable, or an import will sit here
 * unseen -- the same shape of bug GtJobSurface.Holder was made immutable to avoid.
 */
object IosRideImportLauncher {
    var pending: String? = null
        private set

    fun offer(path: String) { pending = path }
    fun clear() { pending = null }
}
