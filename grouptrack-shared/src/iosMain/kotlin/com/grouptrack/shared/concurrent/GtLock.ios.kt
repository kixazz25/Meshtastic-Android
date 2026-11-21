package com.grouptrack.shared.concurrent

import platform.Foundation.NSRecursiveLock

/**
 * GTLOCK-2026-11-20: the iPad's half.
 *
 * NSRecursiveLock rather than NSLock: the shared contract is REENTRANT because it stands in for
 * `@Synchronized`, and NSLock deadlocks on a second acquire from the same thread. Rather than a
 * pthread mutex through cinterop -- which would mean owning its lifetime and its memory -- this uses
 * the Foundation object, which Kotlin/Native binds directly and which is already how this module
 * reaches Foundation elsewhere (NSBundle, NSURL).
 */
actual class GtLock actual constructor() {

    private val lock = NSRecursiveLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
