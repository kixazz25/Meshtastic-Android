#!/usr/bin/env python3
"""
patch_v25_import_tracksonly_v1.py

PURPOSE
  Temporarily bypass GPX *import* of waypoints and routes in
  ConvoyTrackOps.importGpxAllArtifacts, leaving TRACK import fully intact.

WHY
  GPX import of waypoints/routes is a separate, untested task. In-app
  waypoint/route CREATION is unaffected by this change. The untested
  import path was running on every track import as a side effect and is
  the prime suspect for the 87-track import hang (no summary, no source
  delete). This isolates track import (the current task) and quarantines
  the untested code until it gets its own task.

WHAT IT DOES (2 surgical edits, lowest-risk form)
  - Section 2: replaces  val waypoints = parseGpxWaypoints(text)
               with      val waypoints = emptyList<GpxWaypoint>()  (TEMP BYPASS)
  - Section 3: replaces  val routes = parseGpxRoutes(text)
               with      val routes = emptyList<GpxRoute>()        (TEMP BYPASS)
  The existing for-loops then iterate nothing; waypointCount / routeCount
  stay 0; totalImported = trackFiles.size; delete-on-success + summary
  still work off tracks alone. Nothing structural is removed.

REVERSIBILITY
  Restore by swapping the two emptyList() lines back to the parseGpx* calls
  (the originals are preserved in the trailing comment on each line).

TARGET
  app/src/main/java/com/geeksville/mesh/convoy/ConvoyTrackOps.kt
"""

import io
import sys

TARGET = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyTrackOps.kt"
TARGET_SCREEN = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyTrackImportScreen.kt"

MARKER = (
    "            // TEMP BYPASS 2026-06-02: GPX import of waypoints/routes is a "
    "separate untested task.\n"
    "            // In-app waypoint/route creation is UNAFFECTED. Re-enable the "
    "parseGpx* call when import\n"
    "            // logic is built + tested. See STATE_OF_PLAY_2026-06-02.\n"
)

# --- Edit 1: waypoints -------------------------------------------------------
WPT_ANCHOR = (
    "            // \u2500\u2500 2. Process Waypoints (<wpt> elements) \u2500\u2500\n"
    "            val waypoints = parseGpxWaypoints(text)\n"
)
WPT_REPLACE = (
    "            // \u2500\u2500 2. Process Waypoints (<wpt> elements) \u2500\u2500\n"
    + MARKER
    + "            val waypoints = emptyList<GpxWaypoint>()  // was: parseGpxWaypoints(text)\n"
)

# --- Edit 2: routes ----------------------------------------------------------
RTE_ANCHOR = (
    "            // \u2500\u2500 3. Process Routes (<rte> elements) \u2500\u2500\n"
    "            val routes = parseGpxRoutes(text)\n"
)
RTE_REPLACE = (
    "            // \u2500\u2500 3. Process Routes (<rte> elements) \u2500\u2500\n"
    + MARKER
    + "            val routes = emptyList<GpxRoute>()  // was: parseGpxRoutes(text)\n"
)


# --- Edit 3: recap dialog notice (ConvoyTrackImportScreen.kt) ----------------
# Insert an UNCONDITIONAL line after the `if (routes > 0) { ... }` block and
# before the "// Imported track names" comment, so the recap always states
# that waypoint/route import was bypassed (since both counts are forced to 0).
RECAP_ANCHOR = (
    "                }\n"
    "                // Imported track names (show up to 20)\n"
)
RECAP_REPLACE = (
    "                }\n"
    "                // TEMP BYPASS 2026-06-02: waypoint/route IMPORT disabled (separate task).\n"
    "                Text(\n"
    "                    \"Waypoint & route import: BYPASSED (tracks only)\",\n"
    "                    color = Color(0xFFC1C9BF),\n"
    "                    fontSize = 10.sp,\n"
    "                    fontFamily = FontFamily.Monospace\n"
    "                )\n"
    "                // Imported track names (show up to 20)\n"
)


def apply_screen(text: str) -> str:
    n = text.count(RECAP_ANCHOR)
    if n == 0:
        raise SystemExit(
            "ERROR: recap-notice anchor not found in ConvoyTrackImportScreen.kt. "
            "File may already be patched or has changed. No edits made."
        )
    if n > 1:
        raise SystemExit(
            f"ERROR: recap-notice anchor found {n} times (expected 1). "
            "Ambiguous. No edits made."
        )
    if "Waypoint & route import: BYPASSED" in text:
        raise SystemExit("ERROR: recap notice already present. No edits made.")
    return text.replace(RECAP_ANCHOR, RECAP_REPLACE, 1)


def apply(text: str) -> str:
    for label, anchor, replace in (
        ("waypoints", WPT_ANCHOR, WPT_REPLACE),
        ("routes", RTE_ANCHOR, RTE_REPLACE),
    ):
        n = text.count(anchor)
        if n == 0:
            raise SystemExit(
                f"ERROR: {label} anchor not found. File may already be patched "
                f"or has changed. No edits made."
            )
        if n > 1:
            raise SystemExit(
                f"ERROR: {label} anchor found {n} times (expected 1). "
                f"Ambiguous. No edits made."
            )
        # guard against double-apply
        if "emptyList<Gpx" in text and ("was: parseGpxWaypoints" in text
                                        if label == "waypoints"
                                        else "was: parseGpxRoutes" in text):
            raise SystemExit(
                f"ERROR: {label} appears already bypassed. No edits made."
            )
        text = text.replace(anchor, replace, 1)
    return text


def selftest():
    """Run the patch against an in-memory copy of the real anchors."""
    sample = (
        "            }\n"
        + WPT_ANCHOR
        + "            for (wpt in waypoints) {\n"
        + "                try {\n"
        + "                    SpatialDbManager.insertWaypoint(wpt.name, wpt.lat, wpt.lon, wpt.type)\n"
        + "                    waypointCount++\n"
        + "                } catch (e: Exception) {\n"
        + "                    errors.add(\"Waypoint ${wpt.name}: ${e.message}\")\n"
        + "                }\n"
        + "            }\n"
        + RTE_ANCHOR
        + "            for (route in routes) {\n"
        + "                try {\n"
        + "                    val pts = route.points\n"
        + "                }\n"
        + "            }\n"
        + "            // \u2500\u2500 4. Delete source file after successful import \u2500\u2500\n"
    )
    out = apply(sample)
    assert "val waypoints = emptyList<GpxWaypoint>()" in out, "wpt replace failed"
    assert "val routes = emptyList<GpxRoute>()" in out, "rte replace failed"
    assert "parseGpxWaypoints(text)" not in out.replace("was: parseGpxWaypoints(text)", ""), \
        "wpt original call still active"
    assert "parseGpxRoutes(text)" not in out.replace("was: parseGpxRoutes(text)", ""), \
        "rte original call still active"
    assert out.count("TEMP BYPASS 2026-06-02") == 2, "marker count wrong"
    # idempotency: second apply must refuse
    try:
        apply(out)
    except SystemExit:
        pass
    else:
        raise AssertionError("double-apply was not refused")

    # --- recap notice (screen) ---
    screen_sample = (
        "                if (routes > 0) {\n"
        "                    Text(\"$routes routes imported\")\n"
        "                }\n"
        "                // Imported track names (show up to 20)\n"
        "                if (imported.isNotEmpty()) {\n"
    )
    sout = apply_screen(screen_sample)
    assert "Waypoint & route import: BYPASSED (tracks only)" in sout, "recap notice not inserted"
    assert sout.count("// Imported track names (show up to 20)") == 1, "comment duplicated/lost"
    try:
        apply_screen(sout)
    except SystemExit:
        pass
    else:
        raise AssertionError("recap double-apply was not refused")
    print("SELFTEST PASS")


def main():
    if "--selftest" in sys.argv:
        selftest()
        return
    with io.open(TARGET, "r", encoding="utf-8", newline="") as f:
        original = f.read()
    patched = apply(original)
    with io.open(TARGET, "w", encoding="utf-8", newline="") as f:
        f.write(patched)
    print(f"PATCHED: {TARGET}")
    print("  - waypoints import -> emptyList<GpxWaypoint>() (bypassed)")
    print("  - routes    import -> emptyList<GpxRoute>()    (bypassed)")
    print("  track import unchanged; delete-on-success + summary intact.")

    with io.open(TARGET_SCREEN, "r", encoding="utf-8", newline="") as f:
        screen_original = f.read()
    screen_patched = apply_screen(screen_original)
    with io.open(TARGET_SCREEN, "w", encoding="utf-8", newline="") as f:
        f.write(screen_patched)
    print(f"PATCHED: {TARGET_SCREEN}")
    print("  - recap dialog: added 'Waypoint & route import: BYPASSED (tracks only)'")


if __name__ == "__main__":
    main()
