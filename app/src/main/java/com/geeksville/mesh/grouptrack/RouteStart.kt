package com.geeksville.mesh.grouptrack

import androidx.compose.runtime.mutableIntStateOf

/**
 * ROUTESTART-2026-10-22 (Fred 13:49-13:51): ONE MAP's ADD ROUTE has two choices -- Build it myself, AI route design -- and each
 * runs the EXISTING Route+ process (the planning map's: its start-up checks, in-progress routes, compare sets, the unnamed
 * draft, name at save) with that METHOD already set and LOCKED (no change allowed).
 * [method]: NONE = no request; else ROUTE_METHOD_P2P / ROUTE_METHOD_SUGGEST. Cleared when the planning map closes.
 */
object RouteStart {
    const val NONE = -1
    private val m = mutableIntStateOf(NONE)
    var method: Int
        get() = m.intValue
        set(v) { m.intValue = v }
}
