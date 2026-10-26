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
    /** MAPTARGET-2026-10-23: a SUBMENU row -- tapping it lists these. Empty = an ordinary row. Not nullable (CODE RULE 1):
     *  only the group rows carry children, every other row legitimately has none, and "none" is the empty list. */
    val children: List<GtProcess> = emptyList(),
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
    // MAPTARGET-2026-10-23: a submenu row; its rows are ordinary processes with ids "$module.$id" (so attached ids survive a move).
    private fun g(module: String, id: String, label: String, vararg rows: Pair<String, String>) =
        GtProcess("$module.$id", label, children = p(module, *rows))

    val all: List<LaunchModule> = listOf(
        LaunchModule("record", "RECORD", LaunchSlot.TOP_CENTRE, 1, groupOnly = false, stays = false, processes = p("record",
            "checkin" to "CHECK IN (Group) — ride, role and radio on one screen, then records",
            "rec" to "REC (Android only) — start recording",
            "end" to "END RIDE — name, survey and share on one screen")),
        LaunchModule("menu", "MENU", LaunchSlot.LEFT, 1, groupOnly = false, stays = false, processes = p("menu",
            "settings" to "Settings",
            "help" to "Help — Quick Start, Release Notes, Manual",
            "account" to "Account — profile, terms, completed rides")),
        // GRPONEMAP-2026-10-22 (Fred 11:38): GRP AWARENESS stays -- the in-ride issue indicator, one tap to its panel. Always shown.
        LaunchModule("grp", "GRP Awareness", LaunchSlot.RIGHT, 1, /* MODEFIRST-2026-10-26 (Fred 11:51): after MODE */ groupOnly = false, stays = false, processes = p("grp",
            "open" to "GRP Awareness \u2014 radio link status, connect, apply the ride's settings")),
        LaunchModule("mode", "MODE", LaunchSlot.RIGHT, 0, /* MODEFIRST-2026-10-26: first on the right */ groupOnly = false, stays = false, processes = p("mode",
            "switch" to "ANDROID ONLY ⇄ GROUP (blocked while recording)")),
        LaunchModule("internet", "INTERNET", LaunchSlot.RIGHT, 2, groupOnly = false, stays = false, processes = p("internet",
            "switch" to "Internet ON / OFF — shows the current state, allows OFF")),
        // MAPCHIP-2026-10-23 (Fred 08:54): the map type ON the map, under STATUS. Its panel is the list of types (drawn by GroupTrack).
        LaunchModule("maptype", "MAP TYPE", LaunchSlot.RIGHT, 3, groupOnly = false, stays = false, processes = emptyList()),
        LaunchModule("key", "MAP KEY", LaunchSlot.RIGHT, 4, groupOnly = false, stays = true, processes = p("key",
            "trails" to "Trails — STYLE ›",
            "tracks" to "My tracks",
            "routes" to "My routes",
            "work" to "While you work (route being built, lead track, my track)",
            "group" to "Group riding (carts, lead, tail)")),
        LaunchModule("filter", "FILTER", LaunchSlot.RIGHT, 5, groupOnly = false, stays = true, processes = p("filter",
            "hide" to "Hide everything",
            "types" to "Each type — OFF / ALL / SELECT",
            "trails" to "Trails — motorized / non-motorized, public / private")),
        LaunchModule("centre", "CENTRE ON ME", LaunchSlot.RIGHT, 6, groupOnly = false, stays = false, processes = p("centre",
            "me" to "Centre the map on my cart and follow")),
        LaunchModule("readout", "READOUT", LaunchSlot.READOUT, 1, groupOnly = false, stays = true, processes = p("readout",
            "tabs" to "MY CART | GROUP",
            "cart" to "Tap a cart for its readout",
            "gps" to "GPS every n s · Medium Fast / Long Fast",
            "distance" to "Distance traveled")),
        // GTMAP-2026-10-22 (Fred 14:35-14:38): EXACTLY as they exist -- the ride map's own map functions attached; the planning
        // map's functions listed as PLANNER placeholders (they come into ONE MAP with Route+, then everything is realigned).
        // MAP TYPE is drawn at the top of the panel (GtMapTypeRow).
        // MAPTARGET-2026-10-23 (Fred 08:46-09:09): THE TARGET, placeholders backfilled one at a time (copies -- the planner is untouched).
        // Ids of attached rows are kept: map.sources, map.queues, map.features, map.planner.
        // MAPTARGET4-2026-10-23 (Fred 10:04-10:18, option B): queue summary on top (drawn by GroupTrack), Map source, then
        // sections as TWISTIES, one open at a time. MAP TYPE is the Map: TYPE button on the map, not here. Ids of attached rows kept.
        LaunchModule("map", "Map", LaunchSlot.BOTTOM_BAR, 1, groupOnly = false, stays = false, processes = p("map",
            "sources" to "Map source") + listOf(
            g("map", "dl", "DOWNLOAD MAPS FOR OFFLINE USE",
                "dl_area" to "Download maps by area",   // draw a box (by area and by box are ONE function -- Fred 10:09)
                "dl_tracks" to "Download maps along tracks",   // all tracks, or pick
                "dl_settings" to "Download settings \u2014 map sources, replace existing tiles"),   // DLDEFAULTS-2026-10-24
            g("map", "rm", "REMOVE MAPS",
                "rm_area" to "Remove maps by area",
                "rm_track" to "Remove maps for all tracks"),   // stays (Fred 10:14); RMALLTRACKS-2026-10-23: it is ALL tracks
            g("map", "repair", "MAP RECOVERY",   // MAPRECOVERY2-2026-10-23 (Fred 10:26-10:28)
                "recipes" to "Show saved map recipes (60 days)",   // GTRECIPES-2026-10-23 (Fred 11:43)
                "restore" to "Restore maps as of a date \u2014 coming soon",   // RESTORESOON-2026-10-25 (Fred 21:03)   // NEW: replays a daily map recipe (ConvoyMapRecipe) -- restore not built yet
                "repair" to "Refresh maps by source"),   // planner Recreate Tiles by Source
            g("map", "import", "IMPORT MAP FEATURES",
                "trails_state" to "Import trails by state",
                "trails_area" to "Import trails by area \u2014 part of a state, or across state borders",   // AREAIMPORT-2026-10-24
                "utahprivate" to "Assign Utah private property \u2014 which trails are on private land",   // UTAHPRIV-2026-10-24
                "gpx" to "Import GPX \u2014 tracks, waypoints, routes",
                "ridertrails" to "Create trails from tracks",
                "synctracks" to "Resync tracks with their files",   // GTSYNC-2026-10-23 (Fred 12:42): from the Import GPX screen
                "imported" to "Display trail, waypoint counts by state"),   // AREASKIP-2026-10-24 (Fred 12:39)   // IMPORTEDMOVE-2026-10-23 (Fred 11:58): from MAP RECOVERY
            // MAPMOVE-2026-10-23 (Fred 21:45): UNTIL THE MOVE IS DONE is gone -- Map Features (original) and Open the planning map
            // came off (every function they held is on ONE MAP); Area Search Radius keeps its own row, in its own section.
            g("map", "search", "SEARCH",
                "flyover" to "Area Search Radius \u2014 how much a search shows"))),   // AREARADIUS + GTRADIUS-2026-10-23
        // SHELL9-2026-10-22 (Fred 11:07): ROUTE is its own bottom button -- a key function is never hidden between navigation layers.
        LaunchModule("route", "Route", LaunchSlot.BOTTOM_BAR, 2, groupOnly = false, stays = false,   /* GTROUTE1-2026-10-23: a choice closes it */ processes = p("route",
            // ROUTESTART-2026-10-22 (Fred 13:49): TWO choices, each runs the existing Route+ with the method set and locked.
            "manual" to "Build it myself \u2014 tap points, the route follows the trails",
            "ai" to "AI route design \u2014 with your guidance",
            "clear" to "Clear leftover routes \u2014 in progress and AI compare (asks first)")),   // CLEARROUTES-2026-10-22
        LaunchModule("rides", "Rides", LaunchSlot.BOTTOM_BAR, 3, groupOnly = false, stays = false, processes = p("rides",
            "carts" to "View checked-in carts / change roles",   // SHELL5-2026-10-22 (Fred 10:40): first in Rides
            "ride" to "Create a ride",   // RIDESORDER-2026-10-26 (Fred 10:27): carts (only when checked in), Create, My rides, Import
            "mine" to "My rides — Send / Edit / Apply to radio / Delete",
            "import" to "Import a ride")),
        LaunchModule("search", "Search", LaunchSlot.BOTTOM_BAR, 4, groupOnly = false, stays = false, processes = p("search",
            "search" to "Search")),
        // SHELL4-2026-10-22 (Fred 10:23): the last bottom button is RADIO SETUP, with that as its only option. The other group
        // items moved: checked-in carts / roles to RIDES (first option, SHELL5); the GPS rate stays in the HUD; archived setups inside Radio setup.
        LaunchModule("group", "RADIO SETUP", LaunchSlot.BOTTOM_BAR, 5, groupOnly = true, stays = false, processes = p("group",
            // RADIOSETUP-2026-10-22 (Fred 11:47): two rows; GRP Awareness (connect) has its own button.
            "configure" to "Configure radio for rides",
            "archive" to "Archived radio setups \u2014 review / apply")),
    )

    fun bySlot(slot: LaunchSlot, group: Boolean): List<LaunchModule> =
        all.filter { it.slot == slot && (group || !it.groupOnly) }.sortedBy { it.order }
}
