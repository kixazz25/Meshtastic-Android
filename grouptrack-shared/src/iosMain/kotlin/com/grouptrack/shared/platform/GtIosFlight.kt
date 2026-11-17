package com.grouptrack.shared.platform

import com.grouptrack.shared.track.GtTracks
import kotlin.concurrent.AtomicInt

/**
 * IOSFLIGHT-2026-11-17: the iPad's GtTracks.Flight -- 2.7e's two single-flight guards.
 *
 * Android: SpatialDbManager holds two java.util.concurrent.atomic.AtomicBoolean fields
 * (syncRunning:47, propertiesBackfillRunning:1315), begins with compareAndSet(false, true) and ends
 * with set(false).
 *
 * ⭐ WHY THIS IS AN `object` AND NOT A CLASS. The interface says it, and it is the whole point:
 * "Process-wide on Android -- the front door passes the SAME object to every GtTracks it makes."
 * A per-instance guard would be no guard at all: two GtTracks instances would hold one flag each and
 * both would win beginSync(), which is precisely the concurrent sync this exists to prevent. An
 * `object` gives process-wide by construction rather than by a convention a caller has to honour.
 *
 * ⛔ WHY NOT @Volatile, WHICH IS THIS CODEBASE'S USUAL PATTERN (kotlin.concurrent.Volatile, as used
 * throughout GtRoute.kt). @Volatile gives VISIBILITY, not ATOMICITY. `if (!running) { running = true }`
 * is read-then-write: two callers can both read false and both proceed. compareAndSet is one
 * indivisible operation, and that difference is the entire function of this interface. The existing
 * pattern is the wrong pattern here, which is worth saying out loud because reusing it would compile,
 * look consistent, and silently lose the guarantee.
 *
 * AtomicInt is used rather than a boolean because kotlin.concurrent (the non-experimental native
 * surface on Kotlin 2.3.10) offers AtomicInt; 0 = idle, 1 = running. compareAndSet(0, 1) is
 * AtomicBoolean.compareAndSet(false, true) exactly.
 *
 * ⚠ END IS UNCONDITIONAL, exactly as 2.7e's set(false) is -- not compareAndSet(1, 0). If an end ever
 * runs without its begin the flag clears anyway, which is what Android does and is the safer failure:
 * a stuck TRUE would wedge sync permanently with no way back short of a restart.
 */
object IosFlight : GtTracks.Flight {

    private val sync = AtomicInt(0)
    private val backfill = AtomicInt(0)

    override fun beginSync(): Boolean = sync.compareAndSet(0, 1)

    override fun endSync() { sync.value = 0 }

    override fun beginBackfill(): Boolean = backfill.compareAndSet(0, 1)

    override fun endBackfill() { backfill.value = 0 }
}
