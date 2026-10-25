package com.geeksville.mesh.grouptrack

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * SHELL-2026-10-22 (2.7c ONE MAP, Fred 10-22): which layout the ride map shows.
 *
 *   false = CLASSIC -- today's ConvoyScreen controls (the default; nothing changes for anyone who never turns it on).
 *   true  = ONE MAP -- ConvoyScreen's own controls are hidden and GroupTrack.kt's launch layer is drawn over the same map.
 *
 * The map, the recording, the radio and every dialog keep running in ConvoyScreen while the launch modules are attached one at a
 * time (re-point, then simplify). When every launch point is attached, ConvoyScreen is retired and GroupTrack.kt becomes the start
 * screen (Fred 10-22). Switched in Settings > Layout, and back from MENU > Classic layout.
 */
object GtLayout {
    private const val PREFS = "grouptrack_layout"
    private const val KEY = "one_map"

    // CLASSICOFF-2026-10-23 (removal step 4a, Fred 22:00): ONE MAP IS THE ONLY LAYOUT. The classic layout and its switch are gone
    // (Settings > Layout, MENU > Classic layout); the saved choice is no longer read. The classic-only code is deleted in step 4b.
    val oneMap = mutableStateOf(true)
    @Volatile private var loaded = false

    fun load(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        if (loaded) return
        oneMap.value = true
        loaded = true
    }
}
