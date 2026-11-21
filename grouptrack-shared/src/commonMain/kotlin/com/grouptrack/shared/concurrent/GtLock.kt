package com.grouptrack.shared.concurrent

/**
 * GTLOCK-2026-11-20: the toolkit's lock.
 *
 * ⛔ WHY IT EXISTS. Shared code could not guard a mutable collection. `@Synchronized` is
 * `kotlin.jvm`, so it cannot appear in commonMain, and `@Volatile` -- the only concurrency
 * primitive this module used before today -- publishes a REFERENCE safely and says nothing about
 * the object behind it. Any shared service with a producer on one thread and a reader on another
 * (a stats collector fed by the comm layer and read by a panel, for instance) needs a real lock or
 * it is only accidentally correct.
 *
 * ⚠ REENTRANT, to match what it replaces. `@Synchronized` on the JVM is reentrant: a guarded method
 * may call another guarded method on the same object. A non-reentrant lock would turn that into a
 * DEADLOCK the first time someone ports such a method, and it would look like a hang rather than a
 * mistake. Both actuals are therefore recursive -- ReentrantLock on Android, NSRecursiveLock on iOS.
 *
 * ⚠ NOT a coroutine mutex. [withLock] BLOCKS the calling thread, exactly as `synchronized` does, so
 * a port keeps its existing call shape and non-suspend callers do not have to become suspend. For
 * work that should suspend instead, use kotlinx.coroutines' Mutex -- this is not that.
 *
 * Keep the guarded section SHORT. It is a mutex, and a long section under it blocks the producer.
 */
expect class GtLock() {

    /** Runs [block] holding the lock, and releases it even if [block] throws. */
    fun <T> withLock(block: () -> T): T
}
