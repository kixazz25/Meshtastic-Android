package com.geeksville.mesh.grouptrack

import com.grouptrack.shared.settings.GtGroupMode

/**
 * HUDVIEW-2026-11-21 (2.8b): the HUD's view mode, SHARED.
 *
 * Fred 11-21 15:56: "three option on the toggle: my cart, group and hide." The View button cycles three
 * of these; [NODE] and [COLLAPSED] are reached by other paths (a marker tap, the pill).
 *
 * TRANSCRIBED from Android, which held all of this inline: the enum at ConvoyViewModel.kt:28-34, the
 * cycle at GroupTrackScreen.kt:3196, the labels at :3072, the guard at ConvoyViewModel.kt:717, the
 * default at :106. Nothing here is new behaviour.
 *
 * It is shared rather than re-made on the iPad because a second cycle and a second set of labels would
 * be two things to keep in step forever. Android still runs its own inline copy today; pointing it here
 * is a separate step, so this file changes no Android behaviour.
 */
enum class GtHudView {
    GROUP,      // UNITS / ACTIVE / LOST / SPAN / LEAD / TAIL
    MY_CART,    // Speed / Heading / Battery / Altitude / Proximity
    NODE,       // all 22 properties for a tapped marker
    COLLAPSED,  // the pill: node count + LOST count, map maximised
    HIDDEN,     // HUDHIDE-2026-11-01: "View: No HUD" -- no HUD AND no GPS line; CONTACT LOST still shows
}

object GtHudViews {

    /** GroupTrackScreen.kt:3072, branch for branch -- anything that is not MY_CART or HIDDEN reads Group. */
    fun label(view: GtHudView): String = when (view) {
        GtHudView.MY_CART -> "View: My Cart"
        GtHudView.HIDDEN -> "View: No HUD"
        else -> "View: Group"
    }

    /**
     * The View button's cycle: Group -> My Cart -> No HUD -> Group (GroupTrackScreen.kt:3196).
     * NODE and COLLAPSED fall to GROUP, which is Android's own `else` branch.
     */
    fun nextFromButton(view: GtHudView): GtHudView = when (view) {
        GtHudView.GROUP -> GtHudView.MY_CART
        GtHudView.MY_CART -> GtHudView.HIDDEN
        else -> GtHudView.GROUP
    }

    /**
     * ConvoyViewModel.kt:717 (GRPMODE-2026-10-13): asking for GROUP while group awareness is OFF does
     * not switch -- it raises the activate prompt and stays on MY CART. [GtGroupMode.askToActivate] is
     * already shared, so the rule is complete here rather than half in each host.
     */
    fun applyRequest(requested: GtHudView, groupOn: Boolean): GtHudView {
        if (requested == GtHudView.GROUP && !groupOn) {
            GtGroupMode.askToActivate()
            return GtHudView.MY_CART
        }
        return requested
    }

    /**
     * Whether the GPS line draws: on a ride AND not hidden. [GtHudView.HIDDEN] takes the GPS line with
     * it -- the enum's own rule, and the easy half to forget, because the ride gate alone looks right.
     */
    fun showGps(view: GtHudView, onRide: Boolean): Boolean = onRide && view != GtHudView.HIDDEN
}
