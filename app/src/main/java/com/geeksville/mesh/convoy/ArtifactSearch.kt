package com.geeksville.mesh.convoy

/**
 * Reusable, DB-free helpers for artifact name-search results.
 *
 * WHY THIS EXISTS
 * ---------------
 * Artifact names are NOT unique: the same name can occur on many artifacts with
 * different geometry (e.g. on device, "Bluff Trail" x5, "9 Line Trail" x11, each
 * a distinct geom_hash). A search result is therefore a *name-occurrence keyed by
 * geom_hash*, not just a name. Rather than show the raw 64-char SHA-256 hash in a
 * cramped list, we show a small per-name SEQUENCE NUMBER. The full geom_hash and
 * id still travel on every row for the detail card / FIT.
 *
 * This file is pure -- no DB handle, no Compose -- so the numbering transform can
 * be reused anywhere name+geom_hash rows need stable display numbering: search
 * results, a maintenance/list screen, the alias accordion, etc.
 *
 * ============================================================================
 * RULES BAKED INTO assignNameSequence() -- read before reusing or changing:
 * ============================================================================
 *  R1. SEQUENCE IS PER-NAME, NOT GLOBAL. Numbering restarts at 1 for each
 *      distinct name. So two "Bluff Trail" rows are #1 and #2; an unrelated
 *      "Aspen Trail" right after is #1 again -- not a continuation of the list.
 *
 *  R2. ORDER WITHIN A NAME IS BY geom_hash (ascending, lexicographic). This is
 *      what makes the number STABLE: a given geometry always sorts to the same
 *      relative position among its same-name siblings, so its number does not
 *      change between searches, app launches, or devices (the hash is content-
 *      derived). The number is a stable proxy for the hash.
 *
 *  R3. SELF-SORTING. The function sorts internally; callers may pass rows in any
 *      order and still get correct, deterministic numbers. Sort key =
 *      (name lower-cased, geom_hash). The lower-casing MUST match searchByName's
 *      SQL `ORDER BY name COLLATE NOCASE, geom_hash` so on-device display order
 *      and the numbering always agree. If you change one, change the other.
 *
 *  R4. NAME-GROUP IDENTITY IS EXACT (case-sensitive) STRING EQUALITY. The sort
 *      is case-insensitive (R3) but a *group boundary* is an exact-string change.
 *      So "Foo" and "foo" -- if both existed -- sort adjacent yet number as two
 *      separate groups (each starting at 1), which is correct: they are
 *      genuinely different names.
 *
 *  R5. NULL HANDLING. Null name -> "Not Named"; null geom_hash -> "" (an empty
 *      hash sorts first within its name group); null type -> "". NOTE: the
 *      search QUERY already excludes "Not Named"/blank names, so in the search
 *      path these defaults rarely fire -- they exist so the util is safe for
 *      OTHER callers that may pass unfiltered rows.
 *
 *  R6. ROWS WITHOUT AN id ARE DROPPED. A result must be openable in the detail
 *      card, which needs the id; an id-less row is unusable, so it is filtered
 *      out rather than shown.
 *
 *  R7. INPUT CONTRACT. Each input row is a Map<String,String?> carrying at least
 *      the keys "id", "name", "geom_hash", "type" (exactly the keys produced by
 *      SpatialDbManager.searchByName). Extra keys are ignored.
 * ============================================================================
 *
 * Rules baked into the QUERY (SpatialDbManager.searchByName) -- documented here
 * for the full picture, but enforced there, not in this file:
 *  Q1. NON-SPATIAL: searches the whole table, NOT the map viewport (unlike the
 *      queryXByViewport family). A match can be far off-screen. FIT (later) is
 *      the only bridge back to the bbox-scoped display.
 *  Q2. SUBSTRING match: `name LIKE '%term%'` (finds the term anywhere in a name).
 *  Q3. EXCLUDES unnamed rows: name not null, not blank, and <> 'Not Named'.
 *  Q4. CAPPED at `limit` (default 200). A caller can detect truncation by
 *      comparing result size to the limit and prompt the user to refine.
 */

/** One numbered search/list result. seq = stable per-name sequence (1-based). */
data class ArtifactResult(
    val id: String,
    val name: String,
    val geomHash: String,
    val type: String,
    val seq: Int,
    // SEARCHFIT-2026-09-02: the artifact's own bounds, so selecting a result can
    // FRAME it instead of only opening its card. Fred, 09-02: "we select the
    // returned item from the list and then hit fit from the popup menu. Silly."
    // ⚠ NULLABLE WITH DEFAULTS, deliberately -- assignNameSequence and every
    // other construction site keeps working untouched. A required field here
    // would break all of them for a feature only search uses.
    val minLat: Double? = null,
    val maxLat: Double? = null,
    val minLon: Double? = null,
    val maxLon: Double? = null
)

/**
 * Turn raw rows (each carrying "id","name","geom_hash","type") into numbered
 * [ArtifactResult]s. See the RULES block above (R1-R7) for the full contract.
 */
fun assignNameSequence(rows: List<Map<String, String?>>): List<ArtifactResult> {
    val sorted = rows
        .filter { it["id"] != null }                                  // R6
        .map { row ->
            ArtifactResult(
                id = row["id"]!!,                                      // R7
                name = row["name"] ?: "Not Named",                    // R5
                geomHash = row["geom_hash"] ?: "",                    // R5
                type = row["type"] ?: "",                             // R5
                seq = 0,
                // SEARCHFIT-2026-09-02: nulls where the source did not supply
                // bounds -- alias hits, and any caller that builds rows without
                // them. The fit is skipped in that case rather than guessing.
                minLat = row["min_lat"]?.toDoubleOrNull(),
                maxLat = row["max_lat"]?.toDoubleOrNull(),
                minLon = row["min_lon"]?.toDoubleOrNull(),
                maxLon = row["max_lon"]?.toDoubleOrNull()
            )
        }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.geomHash })) // R2, R3

    val out = ArrayList<ArtifactResult>(sorted.size)
    var currentName: String? = null
    var seq = 0
    for (r in sorted) {
        if (r.name != currentName) {   // R4: exact-string group boundary
            currentName = r.name
            seq = 1                    // R1: restart per name
        } else {
            seq++
        }
        out.add(r.copy(seq = seq))
    }
    return out
}
