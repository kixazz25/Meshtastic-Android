package com.grouptrack.shared.cert

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JOBSURFCERT-2026-11-17 -- mode jobsurf17. ⚠ TEST-ONLY (CODE RULE 3).
 *
 * ⭐ WHY THERE IS NO DROID LEG, SAID PLAINLY. Every other 17-series cert compares two
 * implementations -- old against new, or the Droid's report against the Mac's -- because two
 * implementations can disagree. [com.grouptrack.shared.state.GtJobSurface] is ONE implementation in
 * commonMain that both platforms call, so a droid leg would prove only that Kotlin/JVM and
 * Kotlin/Native agree about booleans. That is not a risk worth a cert mode, and running one anyway
 * would buy the appearance of coverage rather than coverage.
 *
 * What this leg actually does, which is the thing that CAN go wrong: it PINS THE RULE. The expected
 * transcript below is written out literally, so editing the decision -- adding a condition, changing
 * when auto-collapse re-arms, letting ABSENT through while a job runs -- fails here immediately
 * instead of showing up as a screen behaving oddly on one platform three weeks later.
 *
 * ⚠ THE RENDER IS NOT CERTIFIED HERE, and that is the open half. Android draws this from
 * StateImportAccordion; the iPad draws nothing yet. Each platform's screen is checked against these
 * lines in acceptance testing -- the "screens rendered" column of the functional compare. A platform
 * that shows nothing where this says COLLAPSED is a defect, and it is now a NAMED one.
 */
class GtCertJobSurf17MacTest {

    private val expected = listOf(
        "table job=false other=false rider=false -> ABSENT",
        "table job=false other=false rider=true -> ABSENT",
        "table job=false other=true rider=false -> ABSENT",
        "table job=false other=true rider=true -> ABSENT",
        "table job=true other=false rider=false -> EXPANDED",
        "table job=true other=false rider=true -> EXPANDED",
        "table job=true other=true rider=false -> COLLAPSED",
        "table job=true other=true rider=true -> EXPANDED",
        "seq1 on the job screen -> EXPANDED",
        "seq1 another screen launched -> COLLAPSED",
        "seq1 rider taps the header -> EXPANDED",
        "seq1 rider moves on again -> COLLAPSED",
        "seq2 collapsed while running -> COLLAPSED",
        "seq2 job finished -> ABSENT",
        "seq3 expanded by tap, other on top -> EXPANDED",
        "seq3 back on the job screen -> EXPANDED",
        "seq3 and leaving collapses again -> COLLAPSED",
        "seq4 tapped on the job screen -> EXPANDED",
        "seq5 ten navigations -> COLLAPSED",
        "seq5 ten taps (even) -> COLLAPSED",
        "seq5 one more tap (odd) -> EXPANDED",
        "invariant a running job is never ABSENT -> true",
        "invariant a running job is only -> COLLAPSED,EXPANDED",
        "invariant no job is always -> ABSENT",
    )

    @Test
    fun jobSurface() {
        println("JOBSURF17 mac: start")
        val got = Cert17jobsurface.all()
        var same = 0
        var bad = 0
        for (i in expected.indices) {
            val g = got.getOrNull(i)
            if (g == expected[i]) same++ else {
                bad++
                println("JOBSURF17 mac: DIFF line $i\n  expect=${expected[i]}\n  got   =$g")
            }
        }
        if (got.size != expected.size) {
            bad++
            println("JOBSURF17 mac: DIFF line count expect=${expected.size} got=${got.size}")
            for (extra in got.drop(expected.size)) println("JOBSURF17 mac: UNEXPECTED $extra")
        }
        println("JOBSURF17 mac: $same SAME, $bad DIFF -- done")
        assertEquals(0, bad, "the surface rule changed -- if that was deliberate, update this transcript and say so in the commit")
        assertTrue(same == expected.size, "not every transcript line was checked ($same of ${expected.size})")
    }
}
