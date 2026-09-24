package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * RIDEJSON-2026-09-23 — writes the ride file (format 3) from the rows that already hold it.
 *
 * THE CONTRACT (Fred 09-23): kind · formatVersion · ride · originator · network{mesh,nucleus} · rideData.
 * ORIGINATOR-2026-09-24: the ride LEADER is a ride-time role (enrollments); the file records who CREATED the ride.
 * No map block (the receiver runs a corridor download on the route). Every attribute comes from an
 * EXISTING provider; this object generates nothing. A value with no provider yet is written as an
 * explicit null placeholder, so the file's shape never changes as features fill them in.
 *
 * COMPLETE = route + trailhead + network all resolve. build() reports what is missing; the send
 * step refuses an incomplete ride. Writing the file never changes the ride's state.
 */
object ConvoyRideJsonWriter {

    private const val TAG = "ConvoyRideJson"

    const val KIND = "grouptrack.ride"
    const val FORMAT_VERSION = 3
    const val COT_SA = "239.2.3.1:6969"        // Nathan 09-22: ATAK situational-awareness multicast
    const val COT_CHAT = "224.10.10.1:17012"   // Nathan 09-22: ATAK chat multicast

    /**
     * ⚠ OPEN (Fred): the narrative payload carries OSM feature names and classes. Until that is
     * decided it is NOT written. The recipe is cleared: every field is a value the rider chose.
     */
    const val INCLUDE_NARRATIVE = false

    data class Result(val json: JSONObject, val missing: List<String>) {
        val complete: Boolean get() = missing.isEmpty()
    }

    private fun nul(v: Any?): Any = v ?: JSONObject.NULL

    fun build(context: Context, rideId: String): Result? {
        SpatialDbManager.init(context)
        val db = SpatialDbManager.getExtensionDb() ?: return null
        val missing = mutableListOf<String>()

        // ---- the ride row ------------------------------------------------------------------
        val r: Array<String?> = try {
            db.rawQuery(
                "SELECT ride_id, organizer_name, org_name, route_id, ride_name, ride_date, " +
                    "start_time, description, zip_code, config_id, expires_at, created_at " +
                    "FROM rides WHERE ride_id = ?", arrayOf(rideId)
            ).use { c ->
                if (!c.moveToFirst()) null
                else Array(12) { i -> if (c.isNull(i)) null else c.getString(i) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ride read failed: ${e.message}"); null
        } ?: run { Log.w(TAG, "no ride $rideId"); return null }

        val ride = JSONObject()
            .put("rideId", r[0])
            .put("name", nul(r[4]))
            .put("date", nul(r[5]))
            .put("startTime", nul(r[6]))
            .put("description", nul(r[7]))
            .put("zipCode", nul(r[8]))
            .put("expiresAt", nul(r[10]))
            .put("createdAt", nul(r[11]))

        // ---- originator: who created the ride (the creator's own tablet writes the file) --------
        // ORIGINATOR-2026-09-24: fixed forever. The ride LEADER is a role claimed at ride time.
        val originator = JSONObject()
            .put("name", nul(r[1]))
            .put("callsign", nul(ConvoyProfileStore.load()?.callsign))
            .put("orgName", nul(r[2]))

        // ---- network: the config the ride points at ---------------------------------------------
        val cfg = r[9]?.let { ConvoyNetworkStore.load(it) }
        val network: Any = if (cfg == null) {
            missing += "network"; JSONObject.NULL
        } else JSONObject()
            .put("id", cfg.configId)
            .put("baseVersion", cfg.baseVersion)
            .put("owner", JSONObject().put("type", cfg.ownerType.db).put("name", cfg.displayName))
            .put("mesh", JSONObject()
                .put("region", cfg.region).put("preset", cfg.modemPreset)
                .put("hopLimit", cfg.hopLimit).put("txPower", cfg.txPower)
                .put("frequencySlot", cfg.frequencySlot).put("role", cfg.role)
                .put("channelName", cfg.configId).put("key", cfg.psk))
            .put("nucleus", JSONObject()
                .put("ssid", cfg.networkSsid)
                .put("password", nul(cfg.networkPassword))
                .put("cot", JSONObject().put("sa", COT_SA).put("chat", COT_CHAT))
                .put("meshName", JSONObject.NULL)        // ⏳ Natak, Thursday
                .put("wifiChannel", JSONObject.NULL)     // ⏳ Natak, Thursday
                .put("takSdkVersion", JSONObject.NULL))  // ⏳ Natak, Thursday

        // ---- rideData: route identity, trailhead, GPX, recipe -------------------------------------
        val routeId = r[3]
        val rideData = JSONObject()
        if (routeId == null) {
            missing += "route"
            rideData.put("route", JSONObject.NULL).put("gpx", JSONObject.NULL)
                .put("narrative", JSONObject.NULL).put("recipe", JSONObject.NULL)
        } else {
            val routeGpx = SpatialDbManager.buildRouteGpxById(routeId)
            val geomHash = SpatialDbManager.routeGeomHash(routeId)
            if (routeGpx == null || geomHash == null) missing += "route"
            rideData.put("route", JSONObject()
                .put("type", "route")                    // identity = type + hash (09-22)
                .put("geomHash", nul(geomHash))
                .put("name", nul(routeGpx?.first)))

            val recipe = SpatialDbManager.routeRecipe(routeId)
            val th = trailheadWpt(recipe)
            if (th == null) missing += "trailhead"

            val wpts = StringBuilder()
            if (th != null) wpts.append(th)
            rideWaypointIds(rideId).forEach { wpId ->
                SpatialDbManager.buildWaypointGpxById(wpId)?.second?.let { g ->
                    WPT.findAll(g).forEach { m -> wpts.append(m.value).append('\n') }
                }
            }
            rideData.put("gpx", nul(routeGpx?.second?.let { mergeWpts(it, wpts.toString()) }))
            rideData.put("recipe", nul(recipe))
            rideData.put("narrative",
                if (INCLUDE_NARRATIVE)
                    nul(SpatialDbManager.readRouteNotes(routeId)?.let { n ->
                        JSONObject(n.toString()).also { it.remove("recipe") }
                    })
                else JSONObject.NULL)
        }

        val json = JSONObject()
            .put("kind", KIND)
            .put("formatVersion", FORMAT_VERSION)
            .put("ride", ride)
            .put("originator", originator)
            .put("network", network)
            .put("rideData", rideData)
        Log.i(TAG, "RIDEJSON-2026-09-23: $rideId built, missing=$missing")
        return Result(json, missing)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private val WPT = Regex("<wpt\\b[\\s\\S]*?</wpt>")

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** The trailhead = the recipe's anchor (the waypoint the route was anchored on). */
    private fun trailheadWpt(recipe: JSONObject?): String? {
        if (recipe == null || !recipe.has("anchorLat") || !recipe.has("anchorLon")) return null
        val lat = recipe.optDouble("anchorLat", Double.NaN)
        val lon = recipe.optDouble("anchorLon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        val name = recipe.optString("anchorName", "").ifBlank { "Trailhead" }
        return "<wpt lat=\"$lat\" lon=\"$lon\"><name>${esc(name)}</name><type>trailhead</type></wpt>\n"
    }

    private fun rideWaypointIds(rideId: String): List<String> {
        val db = SpatialDbManager.getExtensionDb() ?: return emptyList()
        return try {
            db.rawQuery("SELECT waypoint_id FROM ride_waypoints WHERE ride_id = ? ORDER BY seq",
                arrayOf(rideId)).use { c ->
                val out = mutableListOf<String>()
                while (c.moveToNext()) out += c.getString(0)
                out
            }
        } catch (e: Exception) {
            emptyList()   // table not created yet on this tablet = no waypoints attached
        }
    }

    // ---- RIDEFILE-2026-09-23: the ride JSON is a STORED FILE ----------------------------------
    /** GroupTrack storage / rides / <rideId>.json -- one file per ride, on every device that holds it. */
    fun rideFile(context: Context, rideId: String): java.io.File =
        java.io.File(GroupTrackStorage.dir("rides", context).also { it.mkdirs() }, "$rideId.json")

    /**
     * Builds the ride JSON and STORES it. Called on every Save (and on a date change after
     * distribution). Send attaches this file, apply reads it, import stores a received file here
     * unchanged. Atomic: .tmp, then rename -- a half-written ride file never exists.
     * Returns what is missing (empty = complete), or null when nothing was written.
     */
    fun save(context: Context, rideId: String): List<String>? {
        val r = build(context, rideId) ?: return null
        return try {
            val f = rideFile(context, rideId)
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeText(r.json.toString(2))
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                Log.e(TAG, "save: rename failed for ${f.name}"); return null
            }
            Log.i(TAG, "RIDEFILE-2026-09-23: ${f.name} written, missing=${r.missing}")
            r.missing
        } catch (e: Exception) {
            Log.e(TAG, "save failed: ${e.message}"); null
        }
    }

    /** One GPX: waypoints go after <metadata> (or the <gpx> tag), before the route — GPX order. */
    internal fun mergeWpts(routeGpx: String, wpts: String): String {
        if (wpts.isBlank()) return routeGpx
        val meta = routeGpx.indexOf("</metadata>")
        val at = if (meta >= 0) meta + "</metadata>".length
                 else routeGpx.indexOf('>', routeGpx.indexOf("<gpx")).let { if (it < 0) -1 else it + 1 }
        if (at <= 0) return routeGpx
        return routeGpx.substring(0, at) + "\n" + wpts + routeGpx.substring(at)
    }
}
