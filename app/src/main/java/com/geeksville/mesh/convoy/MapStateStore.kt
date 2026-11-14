package com.geeksville.mesh.convoy

// SWITCHMAPSTATE-2026-11-14: the certified shared port and its types
import com.grouptrack.shared.imports.GtFiles
import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.mapstate.GtBBox
import com.grouptrack.shared.mapstate.GtFitArtifact
import com.grouptrack.shared.mapstate.GtMapSnapshot
import com.grouptrack.shared.mapstate.GtMapState
import com.grouptrack.shared.mapstate.GtPanelBoxes
import com.grouptrack.shared.mapstate.GtRouteState
import com.grouptrack.shared.mapstate.GtRow
import com.grouptrack.shared.mapstate.GtTypeState
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath

import android.content.Context
import java.io.File

//
// MapStateStore - per-map durable panel/artifact state (JSON on disk).
//
// PURPOSE: each map (convoy, planning) owns its own state, persisted so it
// survives app close / crash / restart. Eliminates cross-map contamination
// (the old shared ConvoyConfig.*DisplayState / *Checked fields).
//
// WHAT IS STORED (light, no geometry):
//   per artifact type (Trails/Tracks/Waypoints/Routes):
//     - state: Int  (0=OFF, 1=ON, 2=SELECTED)
//     - rows:  [{id, name, checked}]   (the SELECT/Edit list selection)
//   panel checkboxes (map-level): tiles, trails, removeTiles
//
// WHAT IS NOT STORED: geometry / coordinates. On reopen, the screen always
// runs a fresh DB query (queryArtifactList) to attach live geometry to the
// saved selection. Saved-but-now-missing ids simply do not match and drop.
//
// SAFE LOAD: missing or corrupt file -> defaults (Trails ON, others OFF,
// checkboxes false). Never throws to the caller.
//
object MapStateStore {

    // STORAGEROOT-2026-09-11: was `const val DIR = "/sdcard/Documents/..."`.
    // ⛔ A const cannot call anything, so this one had to become a function --
    // the only non-mechanical redirect of the eight.
    // SWITCHMAPSTATE-2026-11-14: dir() / fileFor() / viewFile() / defaults() all moved into GtMapState.
    private val TYPES = listOf("Trails", "Tracks", "Waypoints", "Routes")

    // Which map ("convoy"/"planning") last ran its view refresh. Gates variable
    // substitution: reseed a map's local state from JSON only when the active map
    // changed since the last refresh. @Volatile - touched from worker + main thread.
    @Volatile var lastMapProcessed: String? = null

    data class Row(val id: String, val name: String, val checked: Boolean)
    data class TypeState(val state: Int, val rows: List<Row>)
    data class PanelBoxes(
        val tilesChecked: Boolean = false,
        val trailsChecked: Boolean = false,
        val removeTilesChecked: Boolean = false
    )
    data class BBox(
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double,
        val zoom: Double = 15.0   // [zoom-restore 2026-07-12] persisted so reopen restores exact zoom, not a fitBounds recompute
    )
    data class FitArtifact(
        val id: String,
        val name: String,
        val type: String
    )
    // Route+ in-progress state (per-map). open=route-add mode active;
    // status: "draft" (WIP, reopenable) or "final" (graduated to DB row);
    // draftName links to the RouteDraftStore JSON draft of the same name.
    data class RouteState(
        val open: Boolean,
        val status: String,
        val draftName: String
    )
    data class MapSnapshot(
        val types: Map<String, TypeState>,
        val panel: PanelBoxes,
        val bbox: BBox? = null,
        val fitArtifact: FitArtifact? = null,
        val routeState: RouteState? = null
    )

    // mapKey is "convoy" (classic ride map), "planning" or "grouptrack" (ONE MAP)   // PERSISTKEY-2026-10-23
    // PERSISTKEY-2026-10-23 (Fred 10-22 15:02): the ride map's key follows the layout -- every ride-map read/write uses this.
    // ---------------------------------------------------------------- SWITCHMAPSTATE-2026-11-14
    /**
     * \u26d4 SWITCHED to the shared GtMapState (certified 11-14: Droid 343 SAME / 0 DIFF /
     * 6 EXPECTED, iPad 349 SAME / 0 DIFF cross-device, two clean runs).
     *
     * THE IDIOM IS DELEGATION. This object keeps its name, its data classes and every signature;
     * only the bodies moved. All 51 call sites across the app are untouched, which is the whole
     * point -- a switch asks only whether the call resolves and executes, because the SERVICE was
     * already certified.
     *
     * \u26a0 The 6 EXPECTED differences are org.json's tolerance of comments and single quotes,
     * which kotlinx refuses -- it throws and lands in defaults(), a branch 2.7e already has and
     * already calls correct. Nothing GroupTrack writes is lenient, so only a hand-edited state file
     * can reach it. (org.json does NOT tolerate a trailing comma -- measured on the device 11-14.)
     *
     * \u26a0 lastMapProcessed stays HERE and is not in the shared port. It is @Volatile
     * cross-thread Android state that no compared key reads, so porting it would have added a
     * member the certification could not exercise.
     */
    private val shared: GtMapState by lazy {
        // No Context, as 2.7e: only the folder rules are used. The StateData idiom.
        val files = object : GtFiles {
            override val fs: FileSystem = FileSystem.SYSTEM
            override fun setLastModified(path: Path, epochMs: Long): Boolean = File(path.toString()).setLastModified(epochMs)
            override fun lastModified(path: Path): Long = File(path.toString()).lastModified()
            override fun list(dir: Path): List<Path> = File(dir.toString()).listFiles()?.map { it.toOkioPath() } ?: emptyList()
        }
        val paths = object : GtImportPaths {
            override fun groupTrackRoot(): Path = GroupTrackStorage.root().toOkioPath()
            override fun dir(name: String): Path = GroupTrackStorage.dir(name).toOkioPath()
            override fun tracksDir(): Path = throw UnsupportedOperationException("MapStateStore: state folder only")
            override fun cacheDir(): Path = throw UnsupportedOperationException("MapStateStore: state folder only")
            override fun filesDir(): Path = throw UnsupportedOperationException("MapStateStore: state folder only")
        }
        GtMapState(files, paths)
    }

    // ---- the type mappings. \u26a0 NAMED ARGUMENTS THROUGHOUT: these mirror field for field with
    // ---- the same types, so a positional swap would COMPILE and silently transpose values.
    private fun row(r: GtRow) = Row(id = r.id, name = r.name, checked = r.checked)
    private fun gtRow(r: Row) = GtRow(id = r.id, name = r.name, checked = r.checked)
    private fun typeState(t: GtTypeState) = TypeState(state = t.state, rows = t.rows.map { row(it) })
    private fun gtTypeState(t: TypeState) = GtTypeState(state = t.state, rows = t.rows.map { gtRow(it) })
    private fun panel(p: GtPanelBoxes) =
        PanelBoxes(tilesChecked = p.tilesChecked, trailsChecked = p.trailsChecked, removeTilesChecked = p.removeTilesChecked)
    private fun gtPanel(p: PanelBoxes) =
        GtPanelBoxes(tilesChecked = p.tilesChecked, trailsChecked = p.trailsChecked, removeTilesChecked = p.removeTilesChecked)
    private fun bbox(b: GtBBox?) =
        b?.let { BBox(south = it.south, west = it.west, north = it.north, east = it.east, zoom = it.zoom) }
    private fun gtBBox(b: BBox?) =
        b?.let { GtBBox(south = it.south, west = it.west, north = it.north, east = it.east, zoom = it.zoom) }
    private fun fit(f: GtFitArtifact?) = f?.let { FitArtifact(id = it.id, name = it.name, type = it.type) }
    private fun gtFit(f: FitArtifact?) = f?.let { GtFitArtifact(id = it.id, name = it.name, type = it.type) }
    private fun route(r: GtRouteState?) = r?.let { RouteState(open = it.open, status = it.status, draftName = it.draftName) }
    private fun gtRoute(r: RouteState?) = r?.let { GtRouteState(open = it.open, status = it.status, draftName = it.draftName) }

    private fun snap(s: GtMapSnapshot) = MapSnapshot(
        types = s.types.mapValues { typeState(it.value) },
        panel = panel(s.panel),
        bbox = bbox(s.bbox),
        fitArtifact = fit(s.fitArtifact),
        routeState = route(s.routeState),
    )
    private fun gtSnap(s: MapSnapshot) = GtMapSnapshot(
        types = s.types.mapValues { gtTypeState(it.value) },
        panel = gtPanel(s.panel),
        bbox = gtBBox(s.bbox),
        fitArtifact = gtFit(s.fitArtifact),
        routeState = gtRoute(s.routeState),
    )

    /**
     * PERSISTVIEW-2026-10-23: a map's own map type and offline choice, in <mapKey>_view.json.
     * ⚠ RE-DECLARED HERE because 2.7e declared it BELOW rideKey, inside the range this switch
     * replaced. Its callers are unchanged.
     */
    data class ViewState(val mapType: String, val offline: Boolean)

    // ---- the switched members. Signatures unchanged; bodies delegate. -------------------------

    fun rideKey(): String = GtMapState.rideKey()

    fun saveMap(mapKey: String, snap: MapSnapshot) = shared.saveMap(mapKey, gtSnap(snap))

    fun deleteMap(mapKey: String) = shared.deleteMap(mapKey)

    fun readMap(mapKey: String): MapSnapshot = snap(shared.readMap(mapKey))

    fun saveView(mapKey: String, mapType: String, offline: Boolean) = shared.saveView(mapKey, mapType, offline)

    fun readView(mapKey: String): ViewState =
        shared.readView(mapKey).let { ViewState(mapType = it.mapType, offline = it.offline) }

    fun checkedIdsFor(snap: MapSnapshot, type: String): Set<String>? =
        GtMapState.checkedIdsFor(gtSnap(snap), type)
}
