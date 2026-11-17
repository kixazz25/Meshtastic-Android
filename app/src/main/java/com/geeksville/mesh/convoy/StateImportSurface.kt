package com.geeksville.mesh.convoy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.grouptrack.shared.state.GtJobSurface

/**
 * JOBSURF-2026-11-17: Android's half of the surface rule. The RULE is
 * [com.grouptrack.shared.state.GtJobSurface] in commonMain -- this holds only the two inputs Android
 * can see and keeps them in Compose state so the overlay recomposes.
 *
 * ⚠ A SINGLETON ON PURPOSE, and it has to be. The import OUTLIVES EVERY SCREEN
 * (IMPORTOWNER-2026-09-03: the controller owns the job in its own CoroutineScope), so its surface
 * cannot be remembered inside any composable that the rider can navigate away from -- which is the
 * precise bug being fixed. The state lives where the job lives. *
 * ⚠⚠ JOBSURFPAIR-2026-11-17 -- ONE RULE, TWO RENDERS. DO NOT CHANGE ONE RENDER ALONE.
 *   rule      GtJobSurface          commonMain/state   -- the only place the decision lives
 *   pin       Cert17jobsurface      mode jobsurf17     -- edit the rule and this fails at once
 *   android   StateImportAccordion  HomeStatePickerScreen.kt (state: StateImportSurface)
 *   apple     JobSurfaceView        NOT BUILT YET -- the reserved name, so there is one thing to grep
 * An edit on one side must land on the other IN THE SAME RELEASE. Drift here is silent by nature: the
 * rule still passes its cert, both screens still work on their own device, and they disagree with each
 * other. Nothing in the build will say so -- only a rider on two platforms will notice, and only once. *
 * ⭐ RULEMOD-2026-11-17 (Fred, deferred -- NOT today) -- THE SHORTFALL, AND THE PREFERRED SHAPE.
 * THE SHORTFALL: rules live wherever the code that obeys them happens to live, in both modules. This
 * one is in commonMain/state because a surface is state-ish; queue SELECTION is inside a 1354-line
 * Android class and again inside a 276-line iOS one; the two recovery models are documented in three
 * places. Nothing is wrong with any single location, and there is no list. So a rule gets changed by
 * someone who cannot see what else obeys it, which is how platforms drift while every test passes.
 * THE PREFERRED IMPLEMENTATION: ONE shared rule module both platforms call, rules only, no I/O.
 * ADMISSION CRITERION, so it does not become a junk drawer named `rules` -- all four clauses, or it
 * stays where it is:
 *   1. it is a DECISION, not a mechanism       2. no I/O and no platform dependency
 *   3. provable by transcript                  4. its violation is SILENT
 * By that test: [GtJobSurface] belongs (pure decision, 24-line transcript, and when it breaks both
 * screens still work and merely disagree). Queue SELECTION belongs -- stable priority, the 1..4 clamp,
 * the drain. The queue RUNTIME does not: it writes files. The okio return conventions do not either --
 * that is a platform adapter contract, a different animal, already covered by iosplat17.
 * ⚠ UNTIL THE MODULE EXISTS this note is the list. Add a rule here when you add one anywhere, or
 * the next person inherits the same invisible problem with more places to look.
 */
object StateImportSurface {

    /**
     * Set by [HomeStatePickerScreen] while it is composed. This is "the job's own screen is on top",
     * asked of the screen itself rather than inferred from a route string -- the picker is nested
     * inside the authority gate's NeedTrailData state, so no single route name identifies it.
     */
    var pickerVisible by mutableStateOf(false)

    private var holder by mutableStateOf(GtJobSurface.Holder())

    /** The rider tapped the collapsed header. */
    fun tap() { holder = holder.tap() }

    /** Any navigation. Re-arms auto-collapse, so one tap does not disable it for the whole run. */
    fun onDestinationChanged() { holder = holder.onDestinationChanged() }

    fun surface(jobRunning: Boolean): GtJobSurface.Surface = holder.surface(jobRunning, !pickerVisible)
}
