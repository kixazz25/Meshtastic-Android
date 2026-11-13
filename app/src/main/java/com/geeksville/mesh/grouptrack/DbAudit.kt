package com.geeksville.mesh.grouptrack

import android.content.Context
import android.util.Log
import com.grouptrack.shared.startup.GtDbAuditPort

/**
 * SWITCH17G2F-2026-11-13 (2.8 step 17, G2f): front door to the shared GtDbAuditPort (certified by
 * hk17g2f2 and hk17g2f3 -- 18 + 31 keys, 0 DIFF, both devices, twice).
 *
 * DBAUDIT-2026-11-03 (Fred 11:52): [run] is READ-ONLY -- what the two databases hold and what points
 * at something that is gone, logged DBAUDIT.
 * TRACKSFIX-2026-11-03 (Fred 12:07): [repair] DELETES what can never be used, logged DBREPAIR, once
 * per app start.
 */
object GtDbAudit {
    private const val TAG = "DbAudit"

    /** Read-only. Nothing is changed or deleted. */
    fun run(ctx: Context) { GtDbAuditPort.run(GtHkAndroid(ctx, TAG)) }

    /**
     * ⭐ THE SPAWN STAYS ON THIS SIDE. 2.7e used Thread { sleep(10_000); repairNow(ctx) }; the shared
     * port owns the once-per-process latch and the decision, the platform owns how it waits.
     *
     * ⚠ STEP14C-G-2026-11-08 (TEST-ONLY, removed with GtCertRun): the guard moved here with the
     * thread. A certification run has the database handles swapped onto copies, and the repair
     * deleted rows that way on 11-08.
     */
    fun repair(ctx: Context) {
        GtDbAuditPort.repair(GtHkAndroid(ctx.applicationContext, TAG)) { delayMs, body ->
            Thread {
                try {
                    Thread.sleep(delayMs)
                    if (GtCertRun.active) { Log.i(TAG, "DBREPAIR skipped: a certification run is using swapped database handles"); return@Thread }
                    body()
                } catch (e: Exception) { Log.e(TAG, "DBREPAIR failed", e) }
            }.start()
        }
    }
}
