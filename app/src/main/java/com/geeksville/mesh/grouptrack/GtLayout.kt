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

    val oneMap = mutableStateOf(false)
    @Volatile private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        oneMap.value = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
        loaded = true
    }

    fun set(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
        oneMap.value = value
        loaded = true
        android.util.Log.i("GtLayout", "SHELL-2026-10-22: layout " + (if (value) "ONE MAP" else "CLASSIC"))
    }
}
