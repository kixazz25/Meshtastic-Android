package com.geeksville.mesh.grouptrack

import androidx.compose.runtime.mutableStateOf

/**
 * RIDESEARCH-2026-10-24 (Fred 10-24 10:30): Create a ride = SEARCH positions the map, then the ride form lists the routes in the map area.
 * Nothing new underneath: the one-box search, the map's own last viewport, the ride form as it exists.
 */
object GtRideStart {
    /** true from Rides > Create a ride until ROUTES IN THIS AREA or CANCEL. */
    val pending = mutableStateOf(false)
    /** a one-shot request: GroupTrack opens Search. */
    val wantSearch = mutableStateOf(false)
    /** RIDESEARCH3-2026-10-24: a one-shot request from Search's PROCEED -- GroupTrack runs the ride form for the map area. */
    val wantProceed = mutableStateOf(false)
    /** the map area handed to the ride form: south, north, west, east. EMPTY = no area -- every route
     *  (CODE RULE 1: empty, not null; the form is also opened from a route or a track, with no area). */
    private var area = DoubleArray(0)
    fun begin() { pending.value = true; wantSearch.value = true }
    fun cancel() { pending.value = false }
    fun handOff(s: Double, n: Double, w: Double, e: Double) { area = doubleArrayOf(s, n, w, e); pending.value = false }
    /** the ride form takes the area ONCE -- the next opening of the form by any other way shows every route. */
    fun takeArea(): DoubleArray { val a = area; area = DoubleArray(0); return a }
}
