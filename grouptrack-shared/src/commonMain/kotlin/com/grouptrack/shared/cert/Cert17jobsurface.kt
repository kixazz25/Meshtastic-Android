package com.grouptrack.shared.cert

import com.grouptrack.shared.state.GtJobSurface

/**
 * JOBSURF-2026-11-17 ⚠ TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released).
 *
 * The authoritative transcript of [GtJobSurface]. Because the rule itself is shared, this is NOT an
 * old-vs-new or droid-vs-mac comparison -- there is one implementation and nothing to disagree with
 * it. This exists for the two jobs that remain:
 *  1. PIN THE RULE. If anyone edits the decision, the transcript changes and every cert leg that
 *     reads it fails. The rule stops being editable by accident.
 *  2. GIVE THE RENDER SOMETHING TO BE JUDGED AGAINST. Each platform's screen is checked against these
 *     lines in acceptance testing -- the "screens rendered" column. A platform that draws nothing
 *     where the transcript says COLLAPSED is a defect, and now it is a NAMED one. *
 * ⚠⚠ JOBSURFPAIR-2026-11-17 -- ONE RULE, TWO RENDERS. DO NOT CHANGE ONE RENDER ALONE.
 *   rule      GtJobSurface          commonMain/state   -- the only place the decision lives
 *   pin       Cert17jobsurface      mode jobsurf17     -- edit the rule and this fails at once
 *   android   StateImportAccordion  HomeStatePickerScreen.kt (state: StateImportSurface)
 *   apple     JobSurfaceView        NOT BUILT YET -- the reserved name, so there is one thing to grep
 * An edit on one side must land on the other IN THE SAME RELEASE. Drift here is silent by nature: the
 * rule still passes its cert, both screens still work on their own device, and they disagree with each
 * other. Nothing in the build will say so -- only a rider on two platforms will notice, and only once.
 */
object Cert17jobsurface {

    /** Every input combination, in a fixed order. 8 lines. */
    fun table(): List<String> {
        val out = ArrayList<String>()
        for (job in listOf(false, true))
            for (other in listOf(false, true))
                for (rider in listOf(false, true))
                    out.add("table job=$job other=$other rider=$rider -> ${GtJobSurface.state(job, other, rider)}")
        return out
    }

    /**
     * The sequences, which is where the override's reset shows up. Each line is
     * "<step> -> <surface>", so a drift in WHEN the rule changes is as visible as a drift in WHAT it
     * returns.
     */
    fun sequences(): List<String> {
        val out = ArrayList<String>()

        // The sequence Fred described: an import is running and the rider opens another screen.
        run {
            var h = GtJobSurface.Holder()
            out.add("seq1 on the job screen -> ${h.surface(true, false)}")
            h = h.onDestinationChanged()
            out.add("seq1 another screen launched -> ${h.surface(true, true)}")
            h = h.tap()
            out.add("seq1 rider taps the header -> ${h.surface(true, true)}")
            h = h.onDestinationChanged()
            out.add("seq1 rider moves on again -> ${h.surface(true, true)}")
        }

        // The job finishes while collapsed: the surface goes, nothing lingers.
        run {
            var h = GtJobSurface.Holder()
            h = h.onDestinationChanged()
            out.add("seq2 collapsed while running -> ${h.surface(true, true)}")
            out.add("seq2 job finished -> ${h.surface(false, true)}")
        }

        // Expanded by tap, then navigating BACK to the job's own screen.
        run {
            var h = GtJobSurface.Holder()
            h = h.onDestinationChanged().tap()
            out.add("seq3 expanded by tap, other on top -> ${h.surface(true, true)}")
            h = h.onDestinationChanged()
            out.add("seq3 back on the job screen -> ${h.surface(true, false)}")
            out.add("seq3 and leaving collapses again -> ${h.surface(true, true)}")
        }

        // A tap while the job's own screen is up must not be able to collapse it.
        run {
            var h = GtJobSurface.Holder()
            h = h.tap()
            out.add("seq4 tapped on the job screen -> ${h.surface(true, false)}")
        }

        // No drift under repetition.
        run {
            var h = GtJobSurface.Holder()
            repeat(10) { h = h.onDestinationChanged() }
            out.add("seq5 ten navigations -> ${h.surface(true, true)}")
            repeat(10) { h = h.tap() }
            out.add("seq5 ten taps (even) -> ${h.surface(true, true)}")
            h = h.tap()
            out.add("seq5 one more tap (odd) -> ${h.surface(true, true)}")
        }
        return out
    }

    /**
     * THE INVARIANT, proved by exhaustion rather than asserted: a running job can never be ABSENT.
     * This is the one line of the rule that is a PROMISE to the rider -- something always stays
     * present -- so it is checked over every input rather than trusted to the `when`.
     */
    fun invariants(): List<String> {
        val running = HashSet<GtJobSurface.Surface>()
        for (other in listOf(false, true))
            for (rider in listOf(false, true)) running.add(GtJobSurface.state(true, other, rider))
        val idle = HashSet<GtJobSurface.Surface>()
        for (other in listOf(false, true))
            for (rider in listOf(false, true)) idle.add(GtJobSurface.state(false, other, rider))
        return listOf(
            "invariant a running job is never ABSENT -> ${!running.contains(GtJobSurface.Surface.ABSENT)}",
            "invariant a running job is only -> ${running.sortedBy { it.name }.joinToString(",")}",
            "invariant no job is always -> ${idle.sortedBy { it.name }.joinToString(",")}",
        )
    }

    fun all(): List<String> = table() + sequences() + invariants()
}
