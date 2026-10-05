package org.meshtastic.core.data.manager

import java.util.concurrent.ConcurrentHashMap

/**
 * LINKHB-2026-10-05 (GroupTrack, Fred) -- when each radio last handed us a TAK report over Bluetooth.
 * Our own radio (TAK_TRACKER) hands its report to our tablet every ~15 s; no report from MY radio for 45 s means the
 * Bluetooth hand-off has failed. In memory only -- it is a live signal, nothing to keep.
 */
object TakSeenStore {
    private val last = ConcurrentHashMap<Int, Long>()

    fun saw(num: Int) { last[num] = System.currentTimeMillis() }

    /** When this radio's last TAK report arrived, or null if it has not sent one since GroupTrack started. */
    fun lastFrom(num: Int): Long? = last[num]
}
