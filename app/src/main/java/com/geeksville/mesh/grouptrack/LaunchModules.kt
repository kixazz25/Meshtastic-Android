package com.geeksville.mesh.grouptrack

/**
 * SHELL-2026-10-22 (2.7c, Fred 10-22): ONE MODULE PER LAUNCH POINT. GroupTrack.kt (the scaffold) knows only this list; it never
 * reaches into a process. Each module says what its button is, where it sits, when it shows, whether its panel stays on screen,
 * and the processes its panel lists.
 *
 * ATTACHING (one module at a time, each its own patch, bench-tested):
 *   1. RE-POINT  -- a process gets an [GtProcess.run] that opens today's function where it sits now (attached = true).
 *   2. SIMPLIFY  -- the process moves INTO its module as a function that takes its values on one first screen and runs; the old
 *                   copy is deleted from ConvoyScreen. When nothing is left there, ConvoyScreen is retired.
 * In the shell every process is a PLACEHOLDER: listed, greyed, "not attached yet". The lists are the 2.7c target (the One Map
 * prototype, final design 10-21) and S1 (GroupTrack_2.7c_S1_ScreenInventory_2026-10-22_v1).
 */
enum class LaunchSlot { TOP_CENTRE, LEFT, RIGHT, BOTTOM_BAR, READOUT }

class GtProcess(
    val id: String,
    val label: String,
    /** RE-POINT / SIMPLIFY: what the row does. Placeholder = does nothing (attached = false). */
    val run: () -> Unit = {},
    val attached: Boolean = false,
)

class LaunchModule(
    val id: String,
    val label: String,
    val slot: LaunchSlot,
    val order: Int,
    /** true = the button shows only in the GROUP layout (Android only hides it -- Fred 10-21). */
    val groupOnly: Boolean,
    /** true = its panel stays on screen after use, so it gets MOVE and HIDE (standard 4). */
    val stays: Boolean,
    val processes: List<GtProcess>,
)

object LaunchModules {
    private fun p(module: String, vararg rows: Pair<String, String>) = rows.map { (id, label) -> GtProcess("$module.$id", label) }

    val all: List<LaunchModule> = listOf(
        LaunchModule("record", "RECORD", LaunchSlot.TOP_CENTRE, 1, groupOnly = false, stays = false, processes = p("record",
            "checkin" to "CHECK IN (Group) — ride, role and radio on one screen, then records",
            "rec" to "REC (Android only) — start recording",
            "end" to "END RIDE — name, survey and share on one screen")),
        LaunchModule("menu", "MENU", LaunchSlot.LEFT, 1, groupOnly = false, stays = false, processes = p("menu",
            "settings" to "Settings",
            "help" to "Help — Quick Start, Release Notes, Manual",
            "account" to "Account — profile, terms, completed rides")),
        LaunchModule("mode", "MODE", LaunchSlot.RIGHT, 1, groupOnly = false, stays = false, processes = p("mode",
            "switch" to "ANDROID ONLY ⇄ GROUP (blocked while recording)")),
        LaunchModule("internet", "INTERNET", LaunchSlot.RIGHT, 2, groupOnly = false, stays = false, processes = p("internet",
            "switch" to "Internet ON / OFF — shows the current state, allows OFF")),
        LaunchModule("key", "MAP KEY", LaunchSlot.RIGHT, 3, groupOnly = false, stays = true, processes = p("key",
            "trails" to "Trails — STYLE ›",
            "tracks" to "My tracks",
            "routes" to "My routes",
            "work" to "While you work (route being built, lead track, my track)",
            "group" to "Group riding (carts, lead, tail)")),
        LaunchModule("filter", "FILTER", LaunchSlot.RIGHT, 4, groupOnly = false, stays = true, processes = p("filter",
            "hide" to "Hide everything",
            "types" to "Each type — OFF / ALL / SELECT",
            "trails" to "Trails — motorized / non-motorized, public / private")),
        LaunchModule("centre", "CENTRE ON ME", LaunchSlot.RIGHT, 5, groupOnly = false, stays = false, processes = p("centre",
            "me" to "Centre the map on my cart and follow")),
        LaunchModule("readout", "READOUT", LaunchSlot.READOUT, 1, groupOnly = false, stays = true, processes = p("readout",
            "tabs" to "MY CART | GROUP",
            "cart" to "Tap a cart for its readout",
            "gps" to "GPS every n s · Medium Fast / Long Fast",
            "distance" to "Distance traveled")),
        LaunchModule("map", "Map", LaunchSlot.BOTTOM_BAR, 1, groupOnly = false, stays = false, processes = p("map",
            "type" to "Map type",
            "flyover" to "Flyover Z14–18",
            "download" to "Download for offline",
            "queues" to "Queues",
            "trails" to "Import trail data",
            "gpx" to "Import GPX",
            "sources" to "Map sources",
            "distance" to "Distance")),
        // SHELL9-2026-10-22 (Fred 11:07): ROUTE is its own bottom button -- a key function is never hidden between navigation layers.
        LaunchModule("route", "Route", LaunchSlot.BOTTOM_BAR, 2, groupOnly = false, stays = true, processes = p("route",
            "create" to "Create a route \u2014 draw it on the map")),
        LaunchModule("rides", "Rides", LaunchSlot.BOTTOM_BAR, 3, groupOnly = false, stays = false, processes = p("rides",
            "carts" to "View checked-in carts / change roles",   // SHELL5-2026-10-22 (Fred 10:40): first in Rides
            "ride" to "Create a ride",
            "import" to "Import a ride",
            "mine" to "My rides — Send / Edit / Apply to radio / Delete")),
        LaunchModule("search", "Search", LaunchSlot.BOTTOM_BAR, 4, groupOnly = false, stays = false, processes = p("search",
            "search" to "Search")),
        // SHELL4-2026-10-22 (Fred 10:23): the last bottom button is RADIO SETUP, with that as its only option. The other group
        // items moved: checked-in carts / roles to RIDES (first option, SHELL5); the GPS rate stays in the HUD; archived setups inside Radio setup.
        LaunchModule("group", "RADIO SETUP", LaunchSlot.BOTTOM_BAR, 5, groupOnly = true, stays = false, processes = p("group",
            "radio" to "Radio setup \u2014 connect the radio, apply the ride's settings, archived setups")),
    )

    fun bySlot(slot: LaunchSlot, group: Boolean): List<LaunchModule> =
        all.filter { it.slot == slot && (group || !it.groupOnly) }.sortedBy { it.order }
}
