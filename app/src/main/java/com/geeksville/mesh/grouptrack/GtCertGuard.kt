package com.geeksville.mesh.grouptrack

import android.util.Log

/**
 * STEP15A-2026-11-08 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released): CERT MODE for the jobs that must not
 * run while a certification run has the database handles (and, from 15d, the GroupTrack folders) swapped onto copies -- the 11-08
 * lesson: a job that reads SpatialDbManager's handles at two moments, or opens the databases BY PATH, or writes the GroupTrack folders,
 * can act on the copies or the live files at the wrong moment (DbAudit's repair deleted rows that way).
 * Extends 11-08 STEP14C-G (GtCertRun.active, checked by DbAudit.repairNow) to every such job: each calls
 *     if (GtCertGuard.skip("<job>")) return <its "did nothing" value>
 * first, and logs ONE line under the cert tag when it stands down.
 * ⚠ Only at JOB / TRIGGER points -- never inside a function the certification itself exercises (resolveTrackAdd, syncTracksFromFiles,
 *   RiderTrailWriter.scanAll ...), or the run would skip its own OLD side.
 */
object GtCertGuard {
    fun skip(job: String): Boolean {
        if (!GtCertRun.active) return false
        Log.i("CertRun", "STEP15A cert mode: $job not run")
        return true
    }
}
