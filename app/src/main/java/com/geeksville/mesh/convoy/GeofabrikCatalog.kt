package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * GeofabrikCatalog — loads the shipped geofabrik_states.json asset
 * and provides state lookup by GPS coordinates.
 *
 * USAGE:
 *   val states = GeofabrikCatalog.load(context)
 *   val detected = GeofabrikCatalog.findByLocation(states, lat, lon)
 *   // detected?.name == "Utah", detected?.gpkgUrl == "https://..."
 *
 * The asset is a JSON file with a "states" array, each entry:
 *   { "name", "slug", "bbox": [west, south, east, north], "gpkg_url",
 *     "pbf_url", ... }
 *
 * PBFURL-2026-08-30: BOTH urls are required. The GeoPackage carries geometry
 * cleanly; the PBF carries the access tags (ohv, motor_vehicle, access,
 * 4wd_only) that Geofabrik's shapefile-derived GeoPackage drops entirely.
 * The import needs both and joins them on osm_id.
 *
 * California is split into "California North" and "California South".
 * When the user picks "California" from the picker, both entries
 * should be processed.
 */

private const val TAG = "GeofabrikCatalog"
private const val ASSET_FILE = "geofabrik_states.json"

data class GeofabrikState(
    val name: String,
    val slug: String,
    val bboxWest: Double,
    val bboxSouth: Double,
    val bboxEast: Double,
    val bboxNorth: Double,
    val gpkgUrl: String,
    // PBFURL-2026-08-30: REQUIRED, not optional. The PBF carries the access
    // tags Geofabrik strips from the GeoPackage, so an import without it
    // classifies by shape alone -- silently. A row that cannot say where its
    // PBF is should fail at catalogue load, not at download time on a rider's
    // phone in a state nobody tested.
    val pbfUrl: String,
    val description: String? = null,
    val parentState: String? = null,
) {
    /** True if the given point falls inside this state's bbox. */
    fun containsPoint(lat: Double, lon: Double): Boolean =
        lat in bboxSouth..bboxNorth && lon in bboxWest..bboxEast

    /** True if the given bbox intersects this state's bbox. */
    fun intersectsBbox(south: Double, west: Double, north: Double, east: Double): Boolean =
        !(east < bboxWest || west > bboxEast || north < bboxSouth || south > bboxNorth)

    /** Display name for the state picker — "Utah", "California North", etc. */
    val displayName: String
        get() = name
}

object GeofabrikCatalog {

    private var cached: List<GeofabrikState>? = null

    /**
     * Load states from the shipped JSON asset. Cached after first load.
     * Returns an empty list on parse failure (logged, never crashes).
     */
    fun load(context: Context): List<GeofabrikState> {
        cached?.let { return it }
        return try {
            val json = context.assets.open(ASSET_FILE)
                .bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val arr = root.getJSONArray("states")
            val result = mutableListOf<GeofabrikState>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val bbox = obj.getJSONArray("bbox")
                result.add(
                    GeofabrikState(
                        name = obj.getString("name"),
                        slug = obj.getString("slug"),
                        bboxWest = bbox.getDouble(0),
                        bboxSouth = bbox.getDouble(1),
                        bboxEast = bbox.getDouble(2),
                        bboxNorth = bbox.getDouble(3),
                        gpkgUrl = obj.getString("gpkg_url"),
                        pbfUrl = obj.getString("pbf_url"),  // PBFURL-2026-08-30
                        description = obj.optString("description", null),
                        parentState = obj.optString("parent_state", null),
                    )
                )
            }
            Log.i(TAG, "Loaded ${result.size} states from $ASSET_FILE")
            cached = result
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $ASSET_FILE", e)
            emptyList()
        }
    }

    /**
     * Find the state whose bbox contains the given GPS point.
     * For California sub-regions, returns the specific sub-region
     * (California North or California South), not the parent.
     * Returns null if no state matches (e.g. outside the US).
     */
    fun findByLocation(states: List<GeofabrikState>, lat: Double, lon: Double): GeofabrikState? {
        // Prefer sub-regions (California North/South) over parent entries
        // by checking all and returning the smallest bbox match
        val matches = states.filter { it.containsPoint(lat, lon) }
        return when {
            matches.isEmpty() -> null
            matches.size == 1 -> matches.first()
            else -> {
                // Multiple matches (e.g. overlapping bboxes at state borders)
                // Prefer sub-region (has parentState) over parent
                matches.firstOrNull { it.parentState != null }
                    ?: matches.first()
            }
        }
    }

    /**
     * Find all states whose bbox intersects the given area.
     * Used for the "Import Trails by Area" flow with drawn bbox.
     */
    fun findByBbox(
        states: List<GeofabrikState>,
        south: Double, west: Double, north: Double, east: Double
    ): List<GeofabrikState> =
        states.filter { it.intersectsBbox(south, west, north, east) }

    /**
     * For the state picker UI: group California sub-regions under
     * "California" for display, but return both when selected.
     * All other states return as-is.
     */
    fun displayList(states: List<GeofabrikState>): List<StatePickerItem> {
        val result = mutableListOf<StatePickerItem>()
        val caSubs = states.filter { it.parentState == "California" }
        val others = states.filter { it.parentState == null }

        for (s in others) {
            if (s.slug == "california") continue // skip if somehow present
            result.add(StatePickerItem(s.displayName, listOf(s)))
        }

        if (caSubs.isNotEmpty()) {
            // Insert California in alphabetical position
            val caItem = StatePickerItem("California", caSubs)
            val idx = result.indexOfFirst { it.displayName > "California" }
            if (idx >= 0) result.add(idx, caItem) else result.add(caItem)
        }

        return result.sortedBy { it.displayName }
    }


    /**
     * Detect the user's home state from the device's last known GPS position.
     * Uses LocationManager: GPS_PROVIDER → NETWORK_PROVIDER fallback.
     * Returns null if no fix is available or the location is outside the US.
     *
     * Caller must hold ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION
     * (the authority gate grants this before this is ever called).
     */
    @Suppress("MissingPermission")
    fun detectHomeState(context: Context): GeofabrikState? {
        val states = load(context)
        if (states.isEmpty()) return null

        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE)
                as? android.location.LocationManager ?: return null

        val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)

        if (loc == null || (loc.latitude == 0.0 && loc.longitude == 0.0)) {
            Log.w(TAG, "detectHomeState: no usable location (loc=${loc != null})")
            return null
        }

        // Use Geocoder for accurate state detection (bbox overlaps at borders)
        try {
            val geocoder = android.location.Geocoder(context)
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
            val adminArea = addresses?.firstOrNull()?.adminArea
            if (adminArea != null) {
                val match = states.firstOrNull { it.name.equals(adminArea, ignoreCase = true) }
                    ?: states.firstOrNull { it.parentState?.equals(adminArea, ignoreCase = true) == true }
                if (match != null) {
                    Log.i(TAG, "detectHomeState: geocoder → $adminArea → ${match.name}")
                    return match
                }
                Log.w(TAG, "detectHomeState: geocoder returned '$adminArea' but no state match")
            }
        } catch (e: Exception) {
            Log.w(TAG, "detectHomeState: geocoder failed, falling back to bbox", e)
        }

        // Fallback to bbox matching
        val result = findByLocation(states, loc.latitude, loc.longitude)
        Log.i(TAG, "detectHomeState: bbox fallback lat=%.4f lon=%.4f → %s".format(
            loc.latitude, loc.longitude, result?.name ?: "outside US"))
        return result
    }

    /** Clear the cache (e.g. after a live refresh). */
    fun invalidate() { cached = null }
}

/**
 * One row in the state picker. For most states, entries has one item.
 * For California, it has two (North + South) — both get processed.
 */
data class StatePickerItem(
    val displayName: String,
    val entries: List<GeofabrikState>,
) {
    /** Total estimated bbox area across all entries. */
    val description: String?
        get() = entries.firstOrNull()?.description
                ?: if (entries.size > 1) "${entries.size} regions" else null
}
