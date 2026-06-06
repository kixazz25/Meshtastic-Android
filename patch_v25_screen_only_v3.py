#!/usr/bin/env python3
"""
patch_v25_streaming_import_recap_v1.py

PURPOSE
  Tier-2 streaming track import + real inserted/dropped recap.

  THREE FILES:
  1) SpatialDbManager.kt  -- insertTrackToDb returns Boolean (true=inserted,
     false=dropped as dupe), detected via changes(); honest log line.
  2) ConvoyTrackOps.kt    -- importGpxAllArtifacts rewritten to STREAM the file
     (BufferedReader, accumulate one <trk>..</trk> block at a time, never hold
     the whole file); strip <extensions> per-block; tally inserted/dropped;
     ImportArtifactsSummary gains inserted/dropped (defaulted) fields.
  3) ConvoyTrackImportScreen.kt -- doImport tallies inserted/dropped; recap
     dialog shows "X new / Y already in library"; RECAP TRIGGER log line.

  Waypoint/route blocks remain bypassed (today's emptyList()), untouched here.

REVERSIBILITY
  git checkout the three files. Each edit is anchored on verbatim current text.

NOTE
  insertTrackToDb has exactly ONE caller (ConvoyTrackOps.kt:544, return ignored),
  confirmed by grep, so changing its return type is safe.
"""

import io
import sys

OPS = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyTrackOps.kt"
DB = "app/src/main/java/com/geeksville/mesh/convoy/SpatialDbManager.kt"
SCREEN = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyTrackImportScreen.kt"

# =====================================================================
# EDIT 1 -- SpatialDbManager.insertTrackToDb : String  ->  : Boolean
# =====================================================================
DB_ANCHOR = (
    '    fun insertTrackToDb(name: String, geometryWkt: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): String {\n'
    '        val db = spatialDb ?: throw IllegalStateException("SpatialDbManager not initialized")\n'
    '        val id = newId()\n'
    '        val ts = now()\n'
    '        val bbox = "$minLat,$minLon,$maxLat,$maxLon"\n'
    '        val nm = notNamed(name)\n'
    '        val gh = computeGeomHash(geometryWkt)\n'
    '        try {\n'
    '            db.execSQL(\n'
    '                "INSERT OR IGNORE INTO tracks (track_id, name, geometry, min_lat, max_lat, min_lon, max_lon, bbox, type, created_at, updated_at, geom_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",\n'
    '                arrayOf<Any>(id, nm, geometryWkt, minLat, maxLat, minLon, maxLon, bbox, "TRACK", ts, ts, gh)\n'
    '            )\n'
    '            android.util.Log.i("SpatialDb", "Inserted track to DB: $nm")\n'
    '        } catch (e: Exception) {\n'
    '            android.util.Log.e("SpatialDb", "Track DB insert failed: ${e.message}")\n'
    '        }\n'
    '        return id\n'
    '    }\n'
)
DB_REPLACE = (
    '    // CHANGED 2026-06-02: returns Boolean (true=row inserted, false=dropped as dupe via\n'
    '    // INSERT OR IGNORE on UNIQUE(geom_hash)). Detected with changes(). Enables real import recap.\n'
    '    fun insertTrackToDb(name: String, geometryWkt: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Boolean {\n'
    '        val db = spatialDb ?: throw IllegalStateException("SpatialDbManager not initialized")\n'
    '        val id = newId()\n'
    '        val ts = now()\n'
    '        val bbox = "$minLat,$minLon,$maxLat,$maxLon"\n'
    '        val nm = notNamed(name)\n'
    '        val gh = computeGeomHash(geometryWkt)\n'
    '        var inserted = false\n'
    '        try {\n'
    '            db.execSQL(\n'
    '                "INSERT OR IGNORE INTO tracks (track_id, name, geometry, min_lat, max_lat, min_lon, max_lon, bbox, type, created_at, updated_at, geom_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",\n'
    '                arrayOf<Any>(id, nm, geometryWkt, minLat, maxLat, minLon, maxLon, bbox, "TRACK", ts, ts, gh)\n'
    '            )\n'
    '            db.rawQuery("SELECT changes()", null).use { c ->\n'
    '                if (c.moveToFirst()) inserted = c.getInt(0) > 0\n'
    '            }\n'
    '            if (inserted) android.util.Log.i("SpatialDb", "Inserted track: $nm")\n'
    '            else android.util.Log.i("SpatialDb", "Skipped dupe track: $nm")\n'
    '        } catch (e: Exception) {\n'
    '            android.util.Log.e("SpatialDb", "Track DB insert failed: ${e.message}")\n'
    '        }\n'
    '        return inserted\n'
    '    }\n'
)

# =====================================================================
# EDIT 2 -- ImportArtifactsSummary: add inserted/dropped (defaulted)
# =====================================================================
SUMMARY_ANCHOR = (
    '    data class ImportArtifactsSummary(\n'
    '        val sourceName: String,\n'
    '        val trackCount: Int,\n'
    '        val waypointCount: Int,\n'
    '        val routeCount: Int,\n'
    '        val trackFiles: List<String>,\n'
    '        val errors: List<String>\n'
    '    )\n'
)
SUMMARY_REPLACE = (
    '    data class ImportArtifactsSummary(\n'
    '        val sourceName: String,\n'
    '        val trackCount: Int,\n'
    '        val waypointCount: Int,\n'
    '        val routeCount: Int,\n'
    '        val trackFiles: List<String>,\n'
    '        val errors: List<String>,\n'
    '        val inserted: Int = 0,   // ADDED 2026-06-02: tracks newly inserted\n'
    '        val dropped: Int = 0     // ADDED 2026-06-02: tracks dropped as dupe (already in library)\n'
    '    )\n'
)

# =====================================================================
# EDIT 3 -- importGpxAllArtifacts: STREAM the file + tally inserted/dropped
# Replace from `var text = sourceFile.readText()` through the end of the
# track loop (the `}` closing `for ((index, trk) ...`).
# =====================================================================
OPS_ANCHOR = (
    '            var text = sourceFile.readText()\n'
    '            // Strip <extensions> \u2014 custom namespaces (onXmaps etc) crash XML when tracks are split\n'
    '            text = text.replace(Regex("""<extensions>[\\s\\S]*?</extensions>"""), "")\n'
    '            if (!sourceFile.extension.lowercase().let { it == "gpx" }) {\n'
    '                return@withContext ImportArtifactsSummary(sourceName, 0, 0, 0, emptyList(), listOf("Not a GPX file"))\n'
    '            }\n'
    '\n'
    '            // Initialize spatial DB\n'
    '            SpatialDbManager.init(context)\n'
    '            val dir = tracksDir()\n'
    '            if (!dir.exists()) dir.mkdirs()\n'
    '\n'
    '            // \u2500\u2500 1. Process Tracks (<trk> elements) \u2500\u2500\n'
    '            val trkPattern = Regex("""<trk>([\\s\\S]*?)</trk>""")\n'
    '            val namePattern = Regex("""<name>([^<]*)</name>""")\n'
    '            val tracks = trkPattern.findAll(text).toList()\n'
    '\n'
    '            for ((index, trk) in tracks.withIndex()) {\n'
    '                try {\n'
    '                    val trkContent = trk.groupValues[1]\n'
    '                    val rawName = namePattern.find(trkContent)?.groupValues?.get(1)?.trim()\n'
    '                        ?: "track_${index + 1}"\n'
    '                    val baseName = rawName\n'
    '                        .replace("/", "_").replace("\\\\", "_")\n'
    '                        .replace(Regex("[^a-zA-Z0-9_\\\\- ]"), "")\n'
    '                        .trim().ifEmpty { "track_${index + 1}" }\n'
    '                    val safeName = "$baseName.gpx"\n'
    '\n'
    '                    // Create GPX file in my_tracks/\n'
    '                    val dest = File(dir, safeName)\n'
    '                    if (!dest.exists()) {\n'
    '                        val singleGpx = "<?xml version=\\"1.0\\" encoding=\\"UTF-8\\"?>\\n" +\n'
    '                            "<gpx version=\\"1.1\\" creator=\\"GroupTrack\\">\\n" +\n'
    '                            "<trk>${trkContent}</trk>\\n</gpx>"\n'
    '                        dest.writeText(singleGpx)\n'
    '                        extractEarliestTime(singleGpx)?.let { dest.setLastModified(it) }\n'
    '                            ?: dest.setLastModified(sourceFile.lastModified())\n'
    '                    }\n'
    '\n'
    '                    // Parse coordinates for spatial DB\n'
    '                    val coords = SpatialDbManager.parseGpxTrackPoints("<trk>${trkContent}</trk>")\n'
    '                    if (coords.isNotEmpty()) {\n'
    '                        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE\n'
    '                        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE\n'
    '                        for (pair in coords) {\n'
    '                            val lon = pair.first; val lat = pair.second\n'
    '                            if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat\n'
    '                            if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon\n'
    '                        }\n'
    '                        val wkt = "LINESTRING(" + coords.joinToString(",") { "${it.first} ${it.second}" } + ")"\n'
    '                        SpatialDbManager.insertTrackToDb(baseName, wkt, minLat, maxLat, minLon, maxLon)\n'
    '                    }\n'
    '\n'
    '                    trackFiles.add(safeName)\n'
    '                } catch (e: Exception) {\n'
    '                    errors.add("Track ${index + 1}: ${e.message}")\n'
    '                }\n'
    '            }\n'
)

OPS_REPLACE = (
    '            // Validate extension before reading (streaming \u2014 we never load the whole file)\n'
    '            if (!sourceFile.extension.lowercase().let { it == "gpx" }) {\n'
    '                return@withContext ImportArtifactsSummary(sourceName, 0, 0, 0, emptyList(), listOf("Not a GPX file"))\n'
    '            }\n'
    '\n'
    '            // Initialize spatial DB\n'
    '            SpatialDbManager.init(context)\n'
    '            val dir = tracksDir()\n'
    '            if (!dir.exists()) dir.mkdirs()\n'
    '\n'
    '            // \u2500\u2500 1. Process Tracks (<trk> elements) \u2014 STREAMED 2026-06-02 \u2500\u2500\n'
    '            // Tier-2 streaming: read the file with a BufferedReader and accumulate ONE\n'
    '            // <trk>..</trk> block at a time, process it, release it. The whole file is\n'
    '            // never held in memory (was: readText + whole-file regex + findAll().toList(),\n'
    '            // which OOM\'d on large onX exports). Per-track parse/insert logic is reused.\n'
    '            val namePattern = Regex("""<name>([^<]*)</name>""")\n'
    '            val extPattern = Regex("""<extensions>[\\s\\S]*?</extensions>""")\n'
    '            var trackIndex = 0\n'
    '            var insertedCount = 0\n'
    '            var droppedCount = 0\n'
    '\n'
    '            sourceFile.bufferedReader().use { reader ->\n'
    '                val block = StringBuilder()\n'
    '                var inTrk = false\n'
    '                var line: String?\n'
    '                while (reader.readLine().also { line = it } != null) {\n'
    '                    val ln = line ?: continue\n'
    '                    if (!inTrk) {\n'
    '                        val s = ln.indexOf("<trk>")\n'
    '                        if (s >= 0) { inTrk = true; block.setLength(0); block.append(ln.substring(s)).append("\\n") }\n'
    '                    } else {\n'
    '                        block.append(ln).append("\\n")\n'
    '                    }\n'
    '                    if (inTrk && block.contains("</trk>")) {\n'
    '                        // One complete <trk>..</trk> block accumulated. Process and release.\n'
    '                        inTrk = false\n'
    '                        val rawBlock = block.toString()\n'
    '                        block.setLength(0)\n'
    '                        val endIdx = rawBlock.indexOf("</trk>")\n'
    '                        val trkWhole = rawBlock.substring(0, endIdx + "</trk>".length)\n'
    '                        // strip extensions per-block (was whole-file)\n'
    '                        val trkClean = extPattern.replace(trkWhole, "")\n'
    '                        val trkContent = trkClean.removePrefix("<trk>").removeSuffix("</trk>")\n'
    '                        trackIndex++\n'
    '                        try {\n'
    '                            val rawName = namePattern.find(trkContent)?.groupValues?.get(1)?.trim()\n'
    '                                ?: "track_$trackIndex"\n'
    '                            val baseName = rawName\n'
    '                                .replace("/", "_").replace("\\\\", "_")\n'
    '                                .replace(Regex("[^a-zA-Z0-9_\\\\- ]"), "")\n'
    '                                .trim().ifEmpty { "track_$trackIndex" }\n'
    '                            val safeName = "$baseName.gpx"\n'
    '\n'
    '                            val dest = File(dir, safeName)\n'
    '                            if (!dest.exists()) {\n'
    '                                val singleGpx = "<?xml version=\\"1.0\\" encoding=\\"UTF-8\\"?>\\n" +\n'
    '                                    "<gpx version=\\"1.1\\" creator=\\"GroupTrack\\">\\n" +\n'
    '                                    "<trk>${trkContent}</trk>\\n</gpx>"\n'
    '                                dest.writeText(singleGpx)\n'
    '                                extractEarliestTime(singleGpx)?.let { dest.setLastModified(it) }\n'
    '                                    ?: dest.setLastModified(sourceFile.lastModified())\n'
    '                            }\n'
    '\n'
    '                            val coords = SpatialDbManager.parseGpxTrackPoints("<trk>${trkContent}</trk>")\n'
    '                            if (coords.isNotEmpty()) {\n'
    '                                var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE\n'
    '                                var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE\n'
    '                                for (pair in coords) {\n'
    '                                    val lon = pair.first; val lat = pair.second\n'
    '                                    if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat\n'
    '                                    if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon\n'
    '                                }\n'
    '                                val wkt = "LINESTRING(" + coords.joinToString(",") { "${it.first} ${it.second}" } + ")"\n'
    '                                val wasNew = SpatialDbManager.insertTrackToDb(baseName, wkt, minLat, maxLat, minLon, maxLon)\n'
    '                                if (wasNew) insertedCount++ else droppedCount++\n'
    '                            }\n'
    '\n'
    '                            trackFiles.add(safeName)\n'
    '                        } catch (e: Exception) {\n'
    '                            errors.add("Track $trackIndex: ${e.message}")\n'
    '                        }\n'
    '                    }\n'
    '                }\n'
    '            }\n'
)

# =====================================================================
# EDIT 4 -- the success return: carry inserted/dropped
# The return line currently:
#   ImportArtifactsSummary(sourceName, trackFiles.size, waypointCount, routeCount, trackFiles, errors)
# We need to add insertedCount/droppedCount. But that line is AFTER the loop;
# the variables trackFiles/waypointCount/routeCount exist; insertedCount/
# droppedCount are introduced by EDIT 3. Anchor on the exact return text.
# =====================================================================
RET_ANCHOR = (
    '            ImportArtifactsSummary(sourceName, trackFiles.size, waypointCount, routeCount, trackFiles, errors)\n'
)
RET_REPLACE = (
    '            ImportArtifactsSummary(sourceName, trackFiles.size, waypointCount, routeCount, trackFiles, errors, insertedCount, droppedCount)\n'
)

# =====================================================================
# EDIT 5 (SCREEN) -- doImport: tally inserted/dropped from summary
# =====================================================================
SCREEN_TALLY_ANCHOR = (
    '            val imported = mutableListOf<String>()\n'
    '            val skipped = mutableListOf<String>()\n'
    '            val failed = mutableListOf<String>()\n'
    '            var datesCorrected = 0\n'
    '            var wptTotal = 0\n'
    '            var rteTotal = 0\n'
)
SCREEN_TALLY_REPLACE = (
    '            val imported = mutableListOf<String>()\n'
    '            val skipped = mutableListOf<String>()\n'
    '            val failed = mutableListOf<String>()\n'
    '            var datesCorrected = 0\n'
    '            var wptTotal = 0\n'
    '            var rteTotal = 0\n'
    '            var newTotal = 0      // ADDED 2026-06-02: tracks newly inserted\n'
    '            var dupeTotal = 0     // ADDED 2026-06-02: tracks already in library\n'
)

SCREEN_ACCUM_ANCHOR = (
    '                    imported.addAll(summary.trackFiles)\n'
    '                    wptTotal += summary.waypointCount\n'
    '                    rteTotal += summary.routeCount\n'
    '                    datesCorrected += summary.trackFiles.size\n'
)
SCREEN_ACCUM_REPLACE = (
    '                    imported.addAll(summary.trackFiles)\n'
    '                    wptTotal += summary.waypointCount\n'
    '                    rteTotal += summary.routeCount\n'
    '                    datesCorrected += summary.trackFiles.size\n'
    '                    newTotal += summary.inserted\n'
    '                    dupeTotal += summary.dropped\n'
)

# EDIT 6 (SCREEN) -- recap trigger log + carry new/dupe into recap state
SCREEN_TRIGGER_ANCHOR = (
    '            recapDatesCorrected = datesCorrected\n'
    '            recapWaypoints = wptTotal\n'
    '            recapRoutes = rteTotal\n'
    '            showProgress = false\n'
    '            showRecap = true\n'
)
SCREEN_TRIGGER_REPLACE = (
    '            recapDatesCorrected = datesCorrected\n'
    '            recapWaypoints = wptTotal\n'
    '            recapRoutes = rteTotal\n'
    '            recapNew = newTotal\n'
    '            recapDupe = dupeTotal\n'
    '            android.util.Log.i("Import", "RECAP TRIGGER: new=$newTotal dupe=$dupeTotal files=${imported.size}")\n'
    '            showProgress = false\n'
    '            showRecap = true\n'
)

# EDIT 7 (SCREEN) -- declare recapNew / recapDupe state. Anchor on an existing
# recap* state declaration to place them alongside.
SCREEN_STATE_ANCHOR = (
    '    var recapDatesCorrected by remember { mutableStateOf(0) }\n'
)
SCREEN_STATE_REPLACE = (
    '    var recapDatesCorrected by remember { mutableStateOf(0) }\n'
    '    var recapNew by remember { mutableStateOf(0) }    // ADDED 2026-06-02\n'
    '    var recapDupe by remember { mutableStateOf(0) }   // ADDED 2026-06-02\n'
)

# EDIT 8 (SCREEN) -- pass recapNew/recapDupe into the dialog call
SCREEN_DIALOGCALL_ANCHOR = (
    '        ImportRecapDialog(\n'
    '            imported = recapImported,\n'
    '            skipped = recapSkipped,\n'
    '            failed = recapFailed,\n'
    '            datesCorrected = recapDatesCorrected,\n'
    '            waypoints = recapWaypoints,\n'
    '            routes = recapRoutes,\n'
)
SCREEN_DIALOGCALL_REPLACE = (
    '        ImportRecapDialog(\n'
    '            imported = recapImported,\n'
    '            skipped = recapSkipped,\n'
    '            failed = recapFailed,\n'
    '            datesCorrected = recapDatesCorrected,\n'
    '            waypoints = recapWaypoints,\n'
    '            routes = recapRoutes,\n'
    '            newCount = recapNew,\n'
    '            dupeCount = recapDupe,\n'
)

# EDIT 9 (SCREEN) -- dialog signature: add newCount/dupeCount params (defaulted)
SCREEN_SIG_ANCHOR = (
    '    datesCorrected: Int,\n'
    '    waypoints: Int = 0,\n'
    '    routes: Int = 0,\n'
)
SCREEN_SIG_REPLACE = (
    '    datesCorrected: Int,\n'
    '    waypoints: Int = 0,\n'
    '    routes: Int = 0,\n'
    '    newCount: Int = 0,\n'
    '    dupeCount: Int = 0,\n'
)

# EDIT 10 (SCREEN) -- the display line. Insert a "X new / Y already in library"
# Text right after the imported-count Text block. Anchor on the
# `"${imported.size} tracks imported"` Text closing + the skipped block start.
SCREEN_DISPLAY_ANCHOR = (
    '                if (imported.isNotEmpty()) {\n'
    '                    Text(\n'
    '                        "${imported.size} tracks imported",\n'
    '                        color = Color(0xFF39FF14),\n'
    '                        fontSize = 12.sp,\n'
    '                        fontFamily = FontFamily.Monospace,\n'
    '                        fontWeight = FontWeight.Bold\n'
    '                    )\n'
    '                }\n'
)
SCREEN_DISPLAY_REPLACE = (
    '                if (imported.isNotEmpty()) {\n'
    '                    Text(\n'
    '                        "${imported.size} tracks imported",\n'
    '                        color = Color(0xFF39FF14),\n'
    '                        fontSize = 12.sp,\n'
    '                        fontFamily = FontFamily.Monospace,\n'
    '                        fontWeight = FontWeight.Bold\n'
    '                    )\n'
    '                }\n'
    '                // ADDED 2026-06-02: real inserted-vs-dupe breakdown\n'
    '                Text(\n'
    '                    "$newCount new / $dupeCount already in library",\n'
    '                    color = Color(0xFFC1C9BF),\n'
    '                    fontSize = 11.sp,\n'
    '                    fontFamily = FontFamily.Monospace\n'
    '                )\n'
)


def _replace_once(text, anchor, repl, label):
    n = text.count(anchor)
    if n == 0:
        raise SystemExit(f"ERROR [{label}]: anchor not found. No edits applied to this file.")
    if n > 1:
        raise SystemExit(f"ERROR [{label}]: anchor found {n}x (expected 1). Ambiguous. No edits.")
    return text.replace(anchor, repl, 1)


def patch_db(text):
    if "minLon: Double, maxLon: Double): Boolean {" in text:
        raise SystemExit("ERROR [db]: insertTrackToDb already returns Boolean. Already patched.")
    return _replace_once(text, DB_ANCHOR, DB_REPLACE, "db.insertTrackToDb")


def patch_ops(text):
    if "STREAMED 2026-06-02" in text:
        raise SystemExit("ERROR [ops]: already streamed. Already patched.")
    text = _replace_once(text, SUMMARY_ANCHOR, SUMMARY_REPLACE, "ops.summary")
    text = _replace_once(text, OPS_ANCHOR, OPS_REPLACE, "ops.trackloop")
    text = _replace_once(text, RET_ANCHOR, RET_REPLACE, "ops.return")
    return text


def patch_screen(text):
    if "recapNew" in text:
        raise SystemExit("ERROR [screen]: already patched (recapNew present).")
    text = _replace_once(text, SCREEN_STATE_ANCHOR, SCREEN_STATE_REPLACE, "screen.state")
    text = _replace_once(text, SCREEN_TALLY_ANCHOR, SCREEN_TALLY_REPLACE, "screen.tally")
    text = _replace_once(text, SCREEN_ACCUM_ANCHOR, SCREEN_ACCUM_REPLACE, "screen.accum")
    text = _replace_once(text, SCREEN_TRIGGER_ANCHOR, SCREEN_TRIGGER_REPLACE, "screen.trigger")
    text = _replace_once(text, SCREEN_DIALOGCALL_ANCHOR, SCREEN_DIALOGCALL_REPLACE, "screen.dialogcall")
    text = _replace_once(text, SCREEN_SIG_ANCHOR, SCREEN_SIG_REPLACE, "screen.sig")
    text = _replace_once(text, SCREEN_DISPLAY_ANCHOR, SCREEN_DISPLAY_REPLACE, "screen.display")
    return text


def run(path, fn):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        orig = f.read()
    had_crlf = "\r\n" in orig
    norm = orig.replace("\r\n", "\n")
    new = fn(norm)
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(new)
    print(f"PATCHED: {path}" + ("  (normalized CRLF->LF)" if had_crlf else ""))


def selftest():
    # Build minimal samples containing each anchor and confirm replacement.
    db_sample = DB_ANCHOR
    assert "): Boolean {" in patch_db(db_sample)

    ops_sample = SUMMARY_ANCHOR + "\n" + OPS_ANCHOR + "\n" + RET_ANCHOR
    out = patch_ops(ops_sample)
    assert "STREAMED 2026-06-02" in out
    assert "insertedCount, droppedCount)" in out
    assert "val inserted: Int = 0" in out

    screen_sample = (
        SCREEN_STATE_ANCHOR
        + "        processedFiles = sel\n        scope.launch {\n"
        + SCREEN_TALLY_ANCHOR
        + "            for ((i, f) in sel.withIndex()) {\n                try {\n"
        + "                    val summary = ConvoyTrackOps.importGpxAllArtifacts(f, context)\n"
        + SCREEN_ACCUM_ANCHOR
        + "                } catch (e: Exception) {}\n            }\n"
        + SCREEN_TRIGGER_ANCHOR
        + "        }\n    }\n"
        + SCREEN_DIALOGCALL_ANCHOR
        + "            onFilesDelete = {},\n            onDismiss = {}\n        )\n"
        + "@Composable\nprivate fun ImportRecapDialog(\n    imported: List<String>,\n    skipped: List<String>,\n    failed: List<String>,\n"
        + SCREEN_SIG_ANCHOR
        + "    onDismiss: () -> Unit\n) {\n"
        + SCREEN_DISPLAY_ANCHOR
    )
    sout = patch_screen(screen_sample)
    assert "recapNew by remember" in sout
    assert "RECAP TRIGGER" in sout
    assert "newCount = recapNew" in sout
    assert "newCount: Int = 0" in sout
    assert "new / $dupeCount already in library" in sout
    assert "newTotal += summary.inserted" in sout
    # idempotency
    for fn, s in ((patch_db, patch_db(db_sample)), (patch_ops, out), (patch_screen, sout)):
        try:
            fn(s)
        except SystemExit:
            pass
        else:
            raise AssertionError("double-apply not refused")
    print("SELFTEST PASS")


def main():
    if "--selftest" in sys.argv:
        selftest()
        return
    # SCREEN-ONLY v3: SpatialDbManager.kt + ConvoyTrackOps.kt already patched by v2.
    # This finishes ConvoyTrackImportScreen.kt (CRLF file v2 couldn't match).
    run(SCREEN, patch_screen)
    print("Screen file patched. Review with git diff before building.")


if __name__ == "__main__":
    main()
