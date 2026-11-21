package com.grouptrack.shared.platform

import com.grouptrack.shared.log.gtLogger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_monitor_t
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_get_main_queue

/**
 * REACHABILITY-2026-11-21: the iPad's half of [GtReachability], on Apple's Network framework.
 *
 * ANDROID'S ANSWER IS WHAT THIS MATCHES: `nw_path_status_satisfied` is "a path exists and can carry
 * traffic", which is the same claim as Android's `NET_CAPABILITY_INTERNET` on the default network --
 * neither reaches a server, and both report TRUE behind a captive portal. The contract says so.
 *
 * The update handler is set on the MAIN queue deliberately: both consumers are UI state (E's wording,
 * and the map's switch to local tiles), so a background hop would only add a race.
 */
@OptIn(ExperimentalForeignApi::class)
object IosReachability : GtReachability {

    private val log = gtLogger("GtIosReachability")
    private var monitor: nw_path_monitor_t = null   // nw_path_monitor_t is already nullable
    private var last: Boolean = true   // Android starts optimistic too (mutableStateOf(true))
    private var onChange: ((Boolean) -> Unit)? = null

    override fun online(): Boolean = last

    override fun watch(onChange: (Boolean) -> Unit) {
        this.onChange = onChange
        if (monitor != null) return   // replacing the watcher, not stacking a second monitor
        val m = nw_path_monitor_create()
        monitor = m
        nw_path_monitor_set_queue(m, dispatch_get_main_queue())
        nw_path_monitor_set_update_handler(m) { path ->
            val now = nw_path_get_status(path) == nw_path_status_satisfied
            if (now != last) {
                last = now
                log.i { "REACHABILITY-2026-11-21: online=" + now }
                this.onChange?.invoke(now)
            }
        }
        nw_path_monitor_start(m)
    }

    override fun stopWatching() {
        monitor?.let { nw_path_monitor_cancel(it) }
        monitor = null
        onChange = null
    }
}
