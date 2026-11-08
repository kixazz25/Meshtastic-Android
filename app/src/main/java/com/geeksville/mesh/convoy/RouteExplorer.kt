package com.geeksville.mesh.convoy

import android.database.sqlite.SQLiteDatabase
import com.geeksville.mesh.grouptrack.GtAppDb
import com.grouptrack.shared.route.GtRoute as Shared

/*
 * STEP13-2026-11-07 (2.8): RouteExplorer is now the Android face of the SHARED route builder
 * (grouptrack-shared commonMain, com.grouptrack.shared.route.GtRoute -- a line-for-line port of this file's 2.7e body,
 * ROUTEEXPLORE-2026-08-23). THE ALGORITHM LIVES THERE ONLY. 2.7e's own code is in git history (the certification runs it from
 * RouteExplorerCertOld.kt, TEST-ONLY; certified 153/153, run 2026-11-07_2009). Nothing here decides anything: each function hands the app's own SQLiteDatabase to the shared code as an
 * androidx SQLiteConnection (GtAppDb.wrap -- the same handle, the same cursors, values bound as text as rawQuery bound them) and
 * converts the answer back.
 *
 * ⭐ RouteModule.kt COMPILES UNCHANGED. It names RouteExplorer.Request / RouteShape / PinFeasibility / SelTable, and Kotlin has no
 *   typealias inside an object -- so those types are declared here, field for field and default for default as 2.7e had them, and
 *   converted at the door. ⚠ A field added to a shared type must be added here too (the converters below are the only place).
 *   lastStats is handed out as the shared object itself: RouteModule only reads its fields.
 *
 * ⭐ THE PLATFORM'S TWO THINGS (GtRoute.Env) are set once, here, when RouteExplorer is first touched -- before any call can reach the
 *   shared code (object initialisation is complete before any other thread sees RouteExplorer).
 */
object RouteExplorer {

    init {
        Shared.env = Shared.Env(
            nowIso = { java.time.Instant.now().toString() },
            // 2.7e wrote the org.json object it built; the shared code builds the same keys in the same order as JSON text, and
            // org.json reads it back to the same values (and writes 2.0 as 2, as 2.7e's own JSONObject did).
            writeDraft = { name, json -> RouteDraftStore.writeRawDraft(name, org.json.JSONObject(json)) },
        )
    }

    /** RELEASEF-2026-10-08 / AIFAILCHECK-2026-11-03: the aim ring's radius and the trailhead check distance -- see GtRoute. */
    const val AIM_RING_M = Shared.AIM_RING_M
    const val TRAILHEAD_CHECK_M = Shared.TRAILHEAD_CHECK_M

    // ── the types RouteModule names (2.7e's, field for field; defaults MUST equal GtRoute's) ──

    /** What the caller asks for. */
    data class Request(
        val anchorLat: Double,
        val anchorLon: Double,
        val name: String,
        val milesLow: Double,
        val milesHigh: Double,
        val mphLow: Double,
        val mphHigh: Double,
        // CODE RULE 1 (defaults kept from 2.7e -- see GtRoute.Request): maxRoutes and seed are not read by the search and no caller
        //  passes them; includePoints is omitted by the assess() caller, which passes its points separately.
        val maxRoutes: Int = 4,
        /** Must-visit points. Empty = explore mode. */
        val includePoints: List<Pair<Double, Double>> = emptyList(),
        val seed: Long = 20260823L,
    ) {
        internal fun shared() = Shared.Request(anchorLat, anchorLon, name, milesLow, milesHigh, mphLow, mphHigh,
            maxRoutes, includePoints, seed)
    }

    // CODE RULE 1 (2.7e's default): detail "" = a step with nothing to add.
    data class Progress(val step: String, val detail: String = "")

    data class Suggestion(
        val name: String,
        val miles: Double,
        val hoursLow: Double,
        val hoursHigh: Double,
        val featureCount: Int,
        val featureMix: String,
        val draftName: String,
    )

    data class SelRow(val use: String, val type: String, val pub: Int, val priv: Int, val typeOn: Boolean, val useOn: Boolean)

    /** PLANTABLE-2026-11-03: the plan's trail table. An empty one (no rows, total 0) = not counted yet (CODE RULE 1: that is what
     *  the defaults mean -- RouteModule's "not counted yet" state). */
    data class SelTable(val area: String = "", val rows: List<SelRow> = emptyList(), val restricted: Int = 0,
                        val restrictedOn: Boolean = true, val publicOn: Boolean = true, val privateOn: Boolean = false,
                        val searched: Int = 0, val total: Int = 0,
                        val pieces: Int = 0)   // RESTRICTEDNEVER-2026-11-03: trails with restricted pieces in the area (0 = none)

    data class PinFeasibility(
        val order: List<Int>,
        val totalMiles: Double,
        val legMiles: List<Double>,
        val snapFeet: List<Double>,
        val onFragment: List<Int>,
        val unreachable: List<Int>,
        val marginalMiles: List<Double>,
        val overMiles: Double,
        val underMiles: Double
    )

    /** ROUTEASSIST-2026-08-25A3: sealed, not a nullable end point (CODE RULE 1) -- see GtRoute.RouteShape. */
    sealed interface RouteShape {
        object Loop : RouteShape
        data class PointToPoint(val endLat: Double, val endLon: Double) : RouteShape
    }

    private fun RouteShape.shared(): Shared.RouteShape = when (this) {
        RouteShape.Loop -> Shared.RouteShape.Loop
        is RouteShape.PointToPoint -> Shared.RouteShape.PointToPoint(endLat, endLon)
    }

    /** AIFAIL-2026-11-03: what the last search worked on -- the shared object itself (RouteModule reads its fields). */
    val lastStats: Shared.SearchStats get() = Shared.lastStats

    // ── the calls: each a delegation ──

    /** PLANTABLE-2026-11-03: the trails in [req]'s search area by use and trail type, public / private, against FILTER. */
    fun selectionTable(db: SQLiteDatabase, req: Request): SelTable =
        Shared.selectionTable(GtAppDb.wrap(db), req.shared()).let { t ->
            SelTable(t.area, t.rows.map { r -> SelRow(r.use, r.type, r.pub, r.priv, r.typeOn, r.useOn) }, t.restricted,
                t.restrictedOn, t.publicOn, t.privateOn, t.searched, t.total, t.pieces)
        }

    /** AIFAIL-2026-11-03 v5: the trails in [req]'s search area by class, selected / left out. "" on a failure. */
    fun selectionBreakdown(db: SQLiteDatabase, req: Request): String =
        Shared.selectionBreakdown(GtAppDb.wrap(db), req.shared())

    /** RELEASEF-2026-10-08 TRAILHEADNEAR2: metres to the nearest trail FILTER shows, or null if none within [maxM].
     *  CODE RULE 1 (defaults kept from 2.7e): RouteModule omits both for the ordinary check and passes TRAILHEAD_CHECK_M, "" to
     *  ask whether a HIDDEN trail is there. ⚠ The [filter] default is evaluated HERE, on this package's TrailFilterState, exactly
     *  as 2.7e evaluated it. */
    fun nearestShownTrailMeters(db: SQLiteDatabase, lat: Double, lon: Double, maxM: Double = TRAILHEAD_CHECK_M,
                                filter: String = TrailFilterState.whereOrEmpty()): Double? =
        Shared.nearestShownTrailMeters(GtAppDb.wrap(db), lat, lon, maxM, filter)

    /** AIFAILCHECK-2026-11-03: miles of trail connected to the trail nearest the start (0 = none, -1 = failed). */
    fun connectedMiles(db: SQLiteDatabase, req: Request): Double =
        Shared.connectedMiles(GtAppDb.wrap(db), req.shared())

    /** ROUTEASSIST-2026-08-25A: what the rider's pins cost, right now. null = no network here to route on. */
    fun assess(
        spatialDb: SQLiteDatabase,
        req: Request,
        points: List<Pair<Double, Double>>,
        shape: RouteShape
    ): PinFeasibility? =
        Shared.assess(GtAppDb.wrap(spatialDb), req.shared(), points, shape.shared())?.let { p ->
            PinFeasibility(p.order, p.totalMiles, p.legMiles, p.snapFeet, p.onFragment, p.unreachable,
                p.marginalMiles, p.overMiles, p.underMiles)
        }

    /** NEWENGINE-2026-08-26: the AI route search. CODE RULE 1 (2.7e's default): RouteModule always passes [onProgress]; null =
     *  run without a progress line (the certification), which changes nothing but the Progress calls. */
    fun explore(
        spatialDb: SQLiteDatabase,
        req: Request,
        onProgress: ((Progress) -> Unit)? = null,
    ): List<Suggestion> =
        Shared.explore(GtAppDb.wrap(spatialDb), req.shared(),
            onProgress?.let { cb -> { p: Shared.Progress -> cb(Progress(p.step, p.detail)) } }
        ).map { s -> Suggestion(s.name, s.miles, s.hoursLow, s.hoursHigh, s.featureCount, s.featureMix, s.draftName) }
}
