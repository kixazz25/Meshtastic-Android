package com.geeksville.mesh.convoy

import android.content.Context
import com.geeksville.mesh.grouptrack.GtHkAndroid
import com.grouptrack.shared.startup.GtStartupHousekeeping

// SWITCH17G2F-2026-11-13 (2.8 step 17, G2f): front door to the shared GtStartupHousekeeping
// (certified by hk17g2f1 and hk17g2f4 in the cert runner -- 4 + 16 keys, 0 DIFF, both devices, twice).
// Every public name of the 2.7e object is kept: ConvoyAuthorityGateScreenV2 is unchanged, and it still
// reads StartupHousekeeping.Result as its Compose state.
//
// ⛔ THE RULES MOVED, THEY DID NOT GO. The 09-03 design -- one job, one thread, four steps in order,
// the gate not answering until it returns, and the ALTER only ever on an EMPTY table -- now lives in
// GtStartupHousekeeping, with the 09-03 log that forced it. Read that file before adding a job.
//
// ⚠ @Synchronized IS NOT REPRODUCED HERE, AND THAT IS DELIBERATE. In 2.7e the KDoc saying "BLOCKING,
// AND DELIBERATELY SO" was followed by @Synchronized, then a SECOND KDoc, then everyLaunch -- so the
// annotation bound to the NO-OP while run(), which deletes every trail, had no lock at all. The real
// wart is run() being unsynchronized, and it is preserved: run() below has no lock either. A lock on
// a one-line no-op has no observable behaviour, and writing it here would only mislead the next
// reader. Recorded against 2.7f (living master) for triage after 2.8 first gen.
object StartupHousekeeping {

    /** What the job decided, for the gate to render and act on. ⚠ The gate holds this type. */
    data class Result(
        val ran: Boolean,
        val cleared: Int,
        val needsReload: Boolean,
        val error: String? = null,
    )

    /** EVERYLAUNCH-2026-09-08, emptied HOUSEKEEPING-2026-09-12: a deliberate no-op. The gate still calls it. */
    fun everyLaunch(ctx: Context) {
        GtStartupHousekeeping.everyLaunch(GtHkAndroid(ctx))
    }

    /**
     * THE startup container. One call, every launch, before any map loads.
     * ⛔ BLOCKING, AND DELIBERATELY SO -- the caller must not proceed until this returns.
     */
    fun run(ctx: Context): Result {
        if (com.geeksville.mesh.grouptrack.GtCertGuard.skip("StartupHousekeeping.run")) return Result(ran = false, cleared = 0, needsReload = false)   // STEP15A-2026-11-08 cert mode (TEST-ONLY): jobSchemaConverge deletes every trail when its marker is missing
        val r = GtStartupHousekeeping.run(GtHkAndroid(ctx))
        return Result(ran = r.ran, cleared = r.cleared, needsReload = r.needsReload, error = r.error)
    }
}
