package com.geeksville.mesh.grouptrack

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.route.GtRoute
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToLong

/**
 * AIFLOW-2026-11-23 (2.8b): the AI route flow's STATE, in shared code, so BOTH platforms run the
 * one flow with the one set of panels.
 *
 * \u26d4 THIS IS NOT A SECOND FLOW. Android's RouteModule holds these same values by DELEGATION
 * (`val aiStep get() = GtAiFlow.step`), so there is exactly one copy of each and Android's call
 * sites, behaviour and step numbering are unchanged. The panels [GtAiLaunchPanel] and
 * [GtAiStepPanel] are already shared (AISTEPSHARED-2026-11-23); this is the state they draw from.
 *
 * WHAT IS HERE: the launch step, the rider's distance and speed answers, and the ONE evaluation the
 * launch panel precedes. Nothing else of the route process moves -- the map steps (trailhead, pins,
 * assess, search) stay in RouteModule, because they drive the map.
 */
object GtAiFlow {

    /** The step values RouteModule has always used; it now takes them from here. */
    const val STEP_LAUNCH = -3
    const val STEP_WELCOME = -2
    const val STEP_DISTANCE = -1
    const val STEP_NONE = 0

    private val log = gtLogger("GtAiFlow")

    val step = mutableIntStateOf(STEP_NONE)
    val skipOverview = mutableStateOf(false)

    // RouteModule.kt:207-210's defaults, unchanged.
    val miLow = mutableIntStateOf(55)
    val miHigh = mutableIntStateOf(80)
    val mphLow = mutableIntStateOf(12)
    val mphHigh = mutableIntStateOf(18)

    /** The row's action: raise the launch panel and stop. */
    fun launch() { step.intValue = STEP_LAUNCH }

    /** CANCEL on any of the panels. */
    fun cancel() { step.intValue = STEP_NONE }

    /**
     * THE EVALUATION THE LAUNCH PANEL PRECEDES (Fred 12:11: "it either launches ai planning or the
     * compare function. it just preceded the existing eval").
     * Returns TRUE when a prior AI batch is open, which is the caller's cue to say so -- the compare
     * table is Android's today, so the iPad reports it rather than pretending.
     */
    fun eval(): Boolean {
        if (hasOpenBatch()) { step.intValue = STEP_NONE; return true }
        step.intValue = STEP_WELCOME
        return false
    }

    /** The duration label, as RouteModule.kt computes it at its own call site (\u00b1 the rounding of
     *  a label: Java's Math.round vs Kotlin's roundToLong differ only on an exact .5). */
    fun hoursText(): String {
        val lo = (miLow.intValue.toDouble() / mphHigh.intValue.toDouble().coerceAtLeast(1.0)).roundToLong()
        val hi = (miHigh.intValue.toDouble() / mphLow.intValue.toDouble().coerceAtLeast(1.0)).roundToLong()
        return "" + lo + " to " + hi + " hours"
    }

    /**
     * RouteDraftStore.hasOpenBatch (:153-168) over the FOUR JSON PRIMITIVES (ROUTEJSONPRIM-2026-11-23),
     * so it answers on both platforms: read the batch, count the drafts it names that still exist, and
     * clear a batch that names none (BATCHHEAL-2026-10-07).
     * \u26a0 Android still runs its own copy in RouteDraftStore -- that one is the frozen, proven path
     * and is not retouched mid-certification. Flagged for consolidation after certification.
     */
    fun hasOpenBatch(): Boolean {
        val text = GtRoute.env.jsonRead(BATCH) ?: return false
        val b = (runCatching { GtOrgJson.parse(text) }.getOrNull()) as? JsonObject ?: run {
            log.w { "batch unreadable, ignoring" }
            return false
        }
        val arr = b["routes"] as? JsonArray
        var live = 0
        if (arr != null) for (i in 0 until arr.size) {
            val n = (arr.getOrNull(i) as? JsonObject)?.let { GtOrgJson.optString(it, "name") }.orEmpty()
            if (n.isNotEmpty() && GtRoute.env.jsonRead(n + ".json") != null) live++
        }
        if (live == 0) {
            log.w { "BATCHHEAL: open batch '" + GtOrgJson.optString(b, "batchName") + "' names no surviving drafts -- cleared" }
            GtRoute.env.jsonDelete(BATCH)
            return false
        }
        return true
    }

    /** RouteDraftStore BATCH_DIR (:95) + BATCH_FILE (:94), under the route json root. */
    private const val BATCH = "batch/open_batch.json"
}
