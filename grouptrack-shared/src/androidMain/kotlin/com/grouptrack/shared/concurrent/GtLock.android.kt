package com.grouptrack.shared.concurrent

import java.util.concurrent.locks.ReentrantLock

/**
 * GTLOCK-2026-11-20: Android's half. ReentrantLock, so the semantics are the ones `@Synchronized`
 * already gave every call site this replaces -- mutual exclusion, reentrant, released on throw.
 */
actual class GtLock actual constructor() {

    private val lock = ReentrantLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
