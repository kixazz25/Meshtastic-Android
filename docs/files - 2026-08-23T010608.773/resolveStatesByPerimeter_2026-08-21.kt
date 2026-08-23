// ─────────────────────────────────────────────────────────────────────────
// PERIMETER STATE RESOLUTION — 2026-08-21
//
// Replaces GeofabrikCatalog.findByBbox() as the state selector for AREA imports.
//
// WHY: findByBbox tests the drawn box against each state's RECTANGULAR bbox.
// California's bbox reaches east to -114.13, so a box over the Lake Mead corner
// (-114.57 .. -113.17) "intersects California" and pulls a 600 MB+ extract that
// yields zero trails. Measured on device 2026-08-21.
//
// A rectangle tested against a rectangle can only ever be a rectangle. The fix
// needs real geography, and the device already has some: the same Geocoder that
// fixed the NH/MA border misdetection on 08-20.
//
// WHY PERIMETER ONLY (Fred, 08-21): a state cannot be inside the box without
// crossing its edge. Any state present intersects at least one edge, so
// sampling the perimeter is COMPLETE for a rectangle — interior points add
// nothing and cost lookups.
//
// Add to GeofabrikCatalog.kt.
// ─────────────────────────────────────────────────────────────────────────

/**
 * Sample the perimeter of [bbox] and reverse-geocode each point to find which
 * states the drawn area actually touches.
 *
 * @param south,west,north,east the drawn box.
 * @param stepMiles spacing along each edge. 10 miles means a state would have
 *        to be under 10 miles wide along that edge to be missed, and the four
 *        corners are always sampled, so a corner clip — the most likely
 *        near-miss — is caught regardless.
 *
 * @return the catalog entries for every state found, or NULL if the geocoder
 *         could not be used at all. NULL is not an empty list: see the caller
 *         contract below.
 *
 * ⚠ CALLER CONTRACT: a null return means UNKNOWN, and the caller must fall back
 * to findByBbox(). Treating it as "no states" would import nothing, which is a
 * worse failure than over-downloading. Over-inclusion costs bandwidth; under-
 * inclusion costs the rider their trails.
 */
suspend fun resolveStatesByPerimeter(
    context: Context,
    states: List<GeofabrikState>,
    south: Double, west: Double, north: Double, east: Double,
    stepMiles: Double = 10.0
): List<GeofabrikState>? = withContext(Dispatchers.IO) {

    if (!Geocoder.isPresent()) {
        Log.w(TAG, "perimeter: no geocoder on this device -- caller must fall back")
        return@withContext null
    }

    val points = perimeterPoints(south, west, north, east, stepMiles)
    Log.i(TAG, "perimeter: ${points.size} sample points at ${stepMiles}mi " +
        "on box S=$south W=$west N=$north E=$east")

    val geocoder = Geocoder(context, Locale.US)
    val names = LinkedHashSet<String>()
    var failures = 0

    for ((lat, lon) in points) {
        try {
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocation(lat, lon, 1)
            val admin = results?.firstOrNull()?.adminArea
            if (admin.isNullOrBlank()) {
                // Ocean, or a point the geocoder has nothing for. NOT a failure —
                // a box on the coast legitimately has empty samples.
                Log.d(TAG, "perimeter: $lat,$lon -> (none)")
            } else {
                if (names.add(admin)) Log.i(TAG, "perimeter: $lat,$lon -> $admin (new)")
            }
        } catch (e: Exception) {
            failures++
            Log.w(TAG, "perimeter: lookup failed at $lat,$lon: ${e.message}")
        }
    }

    // ⚠ EVERY lookup failing means the geocoder is unreachable (offline, or the
    // backing service is down), not that the box is empty. Distinguish that from
    // a box whose samples legitimately returned nothing.
    if (failures == points.size) {
        Log.w(TAG, "perimeter: all ${points.size} lookups failed -- caller must fall back")
        return@withContext null
    }
    if (names.isEmpty()) {
        Log.w(TAG, "perimeter: no state names resolved (${failures} failures) -- caller must fall back")
        return@withContext null
    }

    // ── Name -> catalog entry ────────────────────────────────────────────
    // The geocoder returns a display name ("California"); the catalog may hold
    // several entries for it (california/norcal, california/socal). A state
    // confirmed by the perimeter is real, but WHICH SUB-REGION still needs
    // deciding — so sub-regions get the bbox test they were always suited to.
    // Their rectangles are far tighter than the parent's, which is the whole
    // reason the parent's rectangle was misleading.
    val resolved = LinkedHashSet<GeofabrikState>()
    for (name in names) {
        val matches = states.filter {
            it.name.equals(name, true) || it.parentState.equals(name, true)
        }
        when {
            matches.isEmpty() ->
                // A neighbouring country, a territory we do not ship, or a name
                // spelled differently. Logged, not fatal.
                Log.i(TAG, "perimeter: '$name' has no catalog entry -- skipped")
            matches.size == 1 -> resolved.add(matches[0])
            else -> {
                // Split state: keep only the sub-regions the box actually reaches.
                val hits = matches.filter { it.intersectsBbox(south, west, north, east) }
                if (hits.isEmpty()) {
                    // Confirmed present but no sub-region bbox agrees. Take them
                    // all rather than drop a state the geocoder says is there.
                    Log.w(TAG, "perimeter: '$name' confirmed but no sub-region bbox hit -- taking all ${matches.size}")
                    resolved.addAll(matches)
                } else {
                    Log.i(TAG, "perimeter: '$name' -> ${hits.size} of ${matches.size} sub-regions")
                    resolved.addAll(hits)
                }
            }
        }
    }

    Log.i(TAG, "perimeter: RESOLVED ${resolved.size} state(s): " +
        resolved.joinToString(", ") { it.slug })
    resolved.toList()
}

/**
 * Points along the box edge at [stepMiles] spacing, corners always included.
 *
 * Longitude degrees shrink with latitude, so the east-west step is computed from
 * the box's own latitude rather than hardcoded — a fixed degree step would
 * oversample in Arizona and undersample badly in Alaska.
 */
private fun perimeterPoints(
    south: Double, west: Double, north: Double, east: Double,
    stepMiles: Double
): List<Pair<Double, Double>> {
    val latStep = stepMiles / 69.0                       // ~69 mi per degree lat
    val midLat = (south + north) / 2.0
    val milesPerLonDeg = 69.0 * cos(Math.toRadians(midLat))
    val lonStep = if (milesPerLonDeg > 0.1) stepMiles / milesPerLonDeg else 1.0

    val pts = LinkedHashSet<Pair<Double, Double>>()

    // Corners first — a corner clip is the likeliest near-miss, so it is never
    // left to fall between two samples.
    pts.add(south to west); pts.add(south to east)
    pts.add(north to west); pts.add(north to east)

    // South and north edges
    var lon = west
    while (lon < east) {
        pts.add(south to lon)
        pts.add(north to lon)
        lon += lonStep
    }
    // West and east edges
    var lat = south
    while (lat < north) {
        pts.add(lat to west)
        pts.add(lat to east)
        lat += latStep
    }

    return pts.toList()
}
