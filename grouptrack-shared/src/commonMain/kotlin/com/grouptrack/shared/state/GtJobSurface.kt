package com.grouptrack.shared.state

/**
 * JOBSURF-2026-11-17: WHERE A RUNNING JOB'S SURFACE GOES. Fred, 11-17:
 *   "the screen of state download does not start visible if anything else is visible. job must always
 *    stay up with new screen or process over the top. we can minimize screen with a step x of xx
 *    counter but something must stay present if anything else is allowed to be selected."
 * and, on why this is shared rather than written twice:
 *   "I do not want to end up with a functional iPad app with an android that does not correspond in
 *    behavior."
 *
 * ⭐ WHY THIS IS FOUR LINES IN commonMain AND NOT A SCREEN ON EACH PLATFORM. Two hand-written
 * accordions are two opinions about when to collapse, and they drift the first time either one is
 * touched. With the decision here, correspondence is true BY CONSTRUCTION: both platforms call this
 * function, so there is no second rule to disagree with. What is left to verify is the RENDER -- does
 * each platform actually draw the state this returns -- which is the "screens rendered" column of the
 * functional compare, and [com.grouptrack.shared.cert.Cert17jobsurface] emits the transcript it is
 * judged against.
 *
 * ⚠ WHAT EACH PLATFORM DIVERGED FROM, BEFORE THIS. Neither matched the rule:
 *  - Android: the picker is a nav destination and the gate POPS ITSELF (popUpTo inclusive), so on
 *    proceed the surface is DESTROYED while the import runs on in HomeStateImportController's own
 *    scope (IMPORTOWNER-2026-09-03). The job survived; its display did not.
 *  - iOS: another selection would have TERMINATED the job outright -- the platform difference this
 *    whole item exists to close.
 *
 * ⛔ THIS DOES NOT COVER LEAVING THE APP. Launching another application backgrounds GroupTrack and
 * there is no surface of ours on screen at all. Android survives that because the import is
 * in-process with the window held awake; iOS SUSPENDS. That is the background-execution item (the
 * idle timer plus the resumability that already exists), and no arrangement of a collapsed panel
 * fixes it. Said out loud because "the accordion is in" must not come to mean the platforms
 * correspond when the rider switches apps. They do not, yet. *
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
object GtJobSurface {

    /** What the rider sees of a job. ABSENT is reachable ONLY when no job is running. */
    enum class Surface { ABSENT, COLLAPSED, EXPANDED }

    /**
     * The whole rule.
     *
     * @param jobRunning  a unit of work is live (Android: HomeStateImportController.isImporting).
     * @param otherOnTop  something other than this job's own screen is the current destination.
     * @param riderExpanded the rider has tapped the collapsed header open. See [Holder] for why this
     *        is an input rather than state in here: the reset is the half a truth table cannot say.
     */
    fun state(jobRunning: Boolean, otherOnTop: Boolean, riderExpanded: Boolean): Surface = when {
        !jobRunning -> Surface.ABSENT       // no job, no chrome -- the converse matters too
        !otherOnTop -> Surface.EXPANDED     // the job's own screen has no collapsed form at all
        riderExpanded -> Surface.EXPANDED   // the rider asked for it back
        else -> Surface.COLLAPSED           // present, minimized: step x of xx
    }

    /**
     * The rider's override and -- the part that actually needs a type -- ITS RESET.
     *
     * ⭐ WHY THE OVERRIDE CLEARS ON EVERY DESTINATION CHANGE. Without it, one tap disables
     * auto-collapse for the rest of the run, and it would be easy for one platform to clear it and
     * the other not to: a correspondence bug that no truth table would catch, because it is about a
     * SEQUENCE. So the reset lives here, beside the rule, and the cert drives it as a sequence.
     * ⭐ JOBSURFIMM-2026-11-17: IMMUTABLE, and that is a render requirement, not a style preference.
     * Compose recomposes on REASSIGNMENT of a state value, not on mutation inside an object, so a
     * mutable holder would draw the old surface until something else happened to recompose -- and
     * SwiftUI's observation works the same way, so the identical workaround would be needed twice.
     * Returning a new Holder means `holder = holder.tap()` in a Compose state and the same in an
     * ObservableObject, with no platform-side cleverness in either. It also retires the thread-safety
     * question: there is nothing to mutate.
     */
    data class Holder(val riderExpanded: Boolean = false) {

        /** Call on every destination change. Re-arms auto-collapse. */
        fun onDestinationChanged(): Holder = copy(riderExpanded = false)

        /** The rider tapped the header. */
        fun tap(): Holder = copy(riderExpanded = !riderExpanded)

        fun surface(jobRunning: Boolean, otherOnTop: Boolean): Surface =
            state(jobRunning, otherOnTop, riderExpanded)
    }
}
