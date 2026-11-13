package com.geeksville.mesh.grouptrack

/**
 * STEP17G2F3-2026-11-13 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released):
 * the clock the generated 2.7e copies read instead of System.currentTimeMillis().
 *
 * ⛔ RULE 1, FROM THE 11-13 SIMULATION. repairNow decides a track file's fate with
 *     recent = System.currentTimeMillis() - 2 * 3_600_000L
 * and the simulation showed a FORTY-FIVE SECOND gap between two runs flipping a file sitting near
 * that boundary from "changed in the last 2 hours" to "a real track". The Droid run and the iPad
 * run are minutes apart, so a live clock on either side is a guaranteed DIFF for a reason that has
 * nothing to do with the port.
 *
 * ⭐ Substituting the clock is not weakening the test -- it is the test. Both sides are handed the
 * same instant and the same file timestamps, so the boundary case becomes a DECISION that must come
 * out the same, instead of a race that happens to.
 */
object GtHkCertClock {
    @Volatile private var fixed: Long = 0L
    fun set(v: Long) { fixed = v }
    fun now(): Long = fixed
}
