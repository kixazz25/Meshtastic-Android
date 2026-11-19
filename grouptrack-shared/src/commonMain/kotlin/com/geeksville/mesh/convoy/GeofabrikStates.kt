package com.geeksville.mesh.convoy

import com.grouptrack.shared.log.gtLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * GEOSTATES-2026-11-19: the geofabrik_states.json PARSE, in shared code.
 *
 * GeofabrikCatalog.load(context) stays in app/ because it reads an ANDROID asset. The parse itself
 * is pure, so it lives here and both platforms use it -- the same split as every other seam in the
 * A-I port: the engine is shared, acquiring the bytes is the platform's job.
 *
 * ⚠ ALL-OR-NOTHING, ON PURPOSE. getValue()/content THROW on a missing field, and the catch below
 * returns an EMPTY list -- so one malformed row fails the whole catalogue, exactly as Android does
 * (its org.json getString() throws into the same kind of catch). PBFURL-2026-08-30 is why:
 * "a row that cannot say where its PBF is should fail at catalogue load, not at download time on a
 * rider's phone in a state nobody tested." Skipping bad rows would be a REPAIR, and the standing
 * rule for this port is transcribe, do not repair.
 *
 * An empty list is a LEGITIMATE value (CODE RULE 1): the search then runs unbounded, which is what
 * Android does when the map sits outside every state outline.
 */
fun parseGeofabrikStates(json: String): List<GeofabrikState> = try {
    Json.parseToJsonElement(json).jsonObject.getValue("states").jsonArray.map { el ->
        val o = el.jsonObject
        val bb = o.getValue("bbox").jsonArray
        GeofabrikState(
            name = o.getValue("name").jsonPrimitive.content,
            slug = o.getValue("slug").jsonPrimitive.content,
            // the catalogue stores bbox as [west, south, east, north] -- GeofabrikCatalog.kt:61-64
            bboxWest = bb[0].jsonPrimitive.double,
            bboxSouth = bb[1].jsonPrimitive.double,
            bboxEast = bb[2].jsonPrimitive.double,
            bboxNorth = bb[3].jsonPrimitive.double,
            gpkgUrl = o.getValue("gpkg_url").jsonPrimitive.content,
            pbfUrl = o.getValue("pbf_url").jsonPrimitive.content,
            // FETCHFIRST-2026-11-02: "" = this row has no PAD-US file, as optString(..., "") gives.
            landCode = o["land_code"]?.jsonPrimitive?.contentOrNull ?: "",
            landUrl = o["land_url"]?.jsonPrimitive?.contentOrNull ?: "",
            description = o["description"]?.jsonPrimitive?.contentOrNull,
            parentState = o["parent_state"]?.jsonPrimitive?.contentOrNull,
        )
    }
} catch (e: Throwable) {
    gtLogger("GeofabrikStates").e(e) { "GEOSTATES-2026-11-19: catalogue parse failed -- no states" }
    emptyList()
}
