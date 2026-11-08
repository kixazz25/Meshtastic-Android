package com.geeksville.mesh.convoy

import com.grouptrack.shared.ride.GtRideApply as Shared

/*
 * STEP14-2026-11-08 (2.8): RideApplySource is now the Android face of the SHARED ride list (com.grouptrack.shared.ride.GtRideApply --
 * a line-for-line port of this file's 2.7e body, RIDESEAM-2026-09-24 / DATEFILTER-2026-09-24). 2.7e's code is in git history.
 * RideForApply is top-level, so it is a typealias of the shared class (same fields, same constructor): ConvoyApplyRadioScreen and
 * the other readers compile unchanged.
 */

typealias RideForApply = com.grouptrack.shared.ride.RideForApply

object RideApplySource {

    init { GtRidesAndroid.ready() }

    /** DATEFILTER-2026-09-24 (Fred): today or later by default; with includeRecent, the last 30 days too. Unreadable dates are kept. */
    fun isCurrent(date: String, includeRecent: Boolean): Boolean = Shared.isCurrent(date, includeRecent)

    /** Every stored ride with a complete network block, earliest date first. Incomplete files are skipped.
     *  CODE RULE 1 (2.7e's default): includeRecent = false is the Apply list (today or later). */
    fun loadAll(context: android.content.Context, includeRecent: Boolean = false): List<RideForApply> =
        Shared.loadAll(GtRidesAndroid.Files(context), includeRecent)
}
