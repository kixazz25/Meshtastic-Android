package com.grouptrack.shared.platform

import com.grouptrack.shared.startup.GtStartupHousekeeping

/**
 * IOSROAD-2026-11-18 -- the iOS front door to the shared GtStartupHousekeeping.
 *
 * This is the counterpart of Android's app/.../convoy/StartupHousekeeping.kt: a thin platform
 * caller that hands the shared road its environment. Android hands it GtHkAndroid(ctx); iOS
 * hands it IosHkEnv(). NOTHING ELSE BELONGS HERE.
 *
 * ⛔ THE RULES LIVE IN GtStartupHousekeeping, NOT HERE -- the 09-03 design: one job, one thread,
 * four steps in order, the gate not answering until it returns, and the ALTER only ever on an
 * EMPTY table. Read that file before adding a job.
 *
 * ⚠ run() IS BLOCKING AND DELIBERATELY SO. Android's caller says it in as many words: "THE
 * startup container. One call, every launch, before any map loads." The Swift side must run it
 * off the main thread and must not show the map until it returns.
 *
 * ⚠ initDb() is NOT called here. GtStartupHousekeeping:102 calls env.initDb() itself, UNGUARDED,
 * which is why IosHkEnv.initDb must not throw. Calling it here too would run it twice.
 */
object GtIosStartup {

    /** What the road decided, for the Swift side to render. Mirrors StartupHousekeeping.Result. */
    data class Result(
        val ran: Boolean,
        val cleared: Int,
        val needsReload: Boolean,
        val error: String?,
    )

    /** EVERYLAUNCH-2026-09-08, emptied HOUSEKEEPING-2026-09-12: a deliberate no-op. Still called. */
    fun everyLaunch() {
        GtStartupHousekeeping.everyLaunch(IosHkEnv())
    }

    /**
     * THE startup container. One call, every launch, BEFORE ANY MAP LOADS.
     * ⛔ BLOCKING, AND DELIBERATELY SO -- the caller must not proceed until this returns.
     */
    fun run(): Result {
        val r = GtStartupHousekeeping.run(IosHkEnv())
        return Result(ran = r.ran, cleared = r.cleared, needsReload = r.needsReload, error = r.error)
    }
}
