package com.grouptrack.shared.startup
// STEP17G2F2-2026-11-13: the ONE fixture both sides of G2f read (2.8 step 17, group G2f)

/**
 * GtHkFixture — the single source of truth for a G2f cert run.
 *
 * ⭐ ONE FIXTURE, TWO PATHS. The Droid builds a REAL scratch SQLite database and REAL .gpx files
 * from this, and runs 2.7e over them as real Android code. Both the Droid and the iPad build
 * [GtHkScriptEnv] from the SAME fixture and run the shared port over that. So OLD is genuinely
 * 2.7e -- not a second transcription of it -- and NEW is identical on both platforms.
 *
 * ⛔ EVERY FIELD IS DATA, NEVER A LIVE READING. No clock, no listFiles(), no device state. That is
 * rules 1 and 2 from the 11-13 simulation, enforced by there being nowhere else to get it from.
 */
data class GtHkTrackRow(val geomHash: String, val name: String, val type: String)

data class GtHkFixture(
    val trackRows: List<GtHkTrackRow>,
    val files: List<GtHkFile>,
    val spatialNull: Boolean = false,
    val extNull: Boolean = false,
    val spatialPath: String? = "/data/cert/gt_cert_sp.db",
    val tracksDirName: String = "cert_tracks",
    /** The wall clock every side is handed. ⛔ RULE 1 -- never a real clock. */
    val now: Long = 1_762_000_000_000L,
    /** geom_hash values the tracks table holds, for repairNow's "is there a row" test (piece 3). */
    val trackHashes: List<String> = emptyList(),
    /**
     * PIECE 3. How many `trails` rows satisfy 2.7e's two put-back predicates. ⭐ These DRIVE
     * decisions -- 2.7e runs the UPDATE only `if (nImp > 0)` -- so the scratch database is seeded
     * with exactly this many matching rows and the scripted env answers with the same number. Both
     * sides then make the same call for the same reason.
     */
    val nImp: Int = 0,
    val nRid: Int = 0,
    /**
     * PIECE 3. Orphan rows per extension table, by 2.7e's own `what` label. Same contract: the
     * scratch database gets this many real orphans, the script answers the same count, and
     * `if (n == 0) continue` therefore fires identically on both sides.
     */
    val orphans: Map<String, Int> = emptyMap(),

    // ---- PIECE 4: StartupHousekeeping.run and its three jobs ------------------
    /** Is the schema marker already on the device? ⭐ The job's whole gate. */
    val markerPresent: Boolean = false,
    /** Which of TRAIL_COLUMNS the trails table already has. The rest must be ALTERed in. */
    val columnsPresent: Set<String> = setOf("status", "land_status", "use_type", "carto_code_source"),
    /** Plain trails rows, so `cleared` is a number we chose rather than a side effect of piece 3. */
    val trailsPlain: Int = 0,
    /** What mergeShippedCategories reports adding. */
    val categoriesAdded: List<String> = emptyList(),
    /** ensureDefaults throws -- 2.7e catches and logs, and the stream must continue. */
    val ensureThrows: Boolean = false,
    /** sweepManifests throws -- an un-swept manifest is clutter, not a fault. */
    val sweepThrows: Boolean = false,
    /** Which ownership files exist: "" (the file itself), ".part", ".tmp". PRUNEUGRC deletes them. */
    val ownershipSuffixes: Set<String> = emptySet(),
    /** The count query throws -- 2.7e's catch returns ran=true, cleared=0, needsReload=false. */
    val countThrows: Boolean = false,
) {
    companion object {
        /**
         * PIECE 2 -- GtDbAudit.run. Built to hit every branch and every edge the 11-13 simulation
         * found, so a green run means something:
         *   • a row WITH a file           -> never reported missing
         *   • a row with NO file          -> missing
         *   • a blank name                -> "(no name)"
         *   • type NULL                   -> treated as TRACK
         *   • type ROUTE                  -> never missing, whatever its file
         *   • an EMPTY geom_hash          -> IFNULL('') -- still a row, still missing
         *   • the SAME hash twice         -> both names reported
         *   • a file with no row          -> noRow
         *   • a dot-file and a .txt       -> skipped by 2.7e's own filter
         */
        val PIECE2 = GtHkFixture(
            trackRows = listOf(
                GtHkTrackRow("aaaaaaaaaa11", "Bar 10 Road", "TRACK"),
                GtHkTrackRow("bbbbbbbbbb22", "Gone Track", "TRACK"),
                GtHkTrackRow("cccccccccc33", "", "NULL"),
                GtHkTrackRow("dddddddddd44", "A Route", "ROUTE"),
                GtHkTrackRow("", "No Hash", "TRACK"),
                GtHkTrackRow("eeeeeeeeee55", "Dup One", "TRACK"),
                GtHkTrackRow("eeeeeeeeee55", "Dup Two", "TRACK"),
            ),
            files = listOf(
                GtHkFile("aaaaaaaaaa11.gpx", 1_762_000_000_000L - 9 * 3_600_000L, 120, "<gpx/>"),
                GtHkFile("ffffffffff66.gpx", 1_762_000_000_000L - 9 * 3_600_000L, 140, "<gpx/>"),
                GtHkFile("gggggggggg77.gpx", 1_762_000_000_000L - 9 * 3_600_000L, 160, "<gpx/>"),
                GtHkFile(".hidden.gpx", 1_762_000_000_000L - 9 * 3_600_000L, 10, "<gpx/>"),
                GtHkFile("notes.txt", 1_762_000_000_000L - 9 * 3_600_000L, 10, "not gpx"),
            ),
        )

        /** PIECE 2, variant: the extension database is not open -> the orphan block is skipped. */
        val PIECE2_NO_EXT = PIECE2.copy(extNull = true)

        /** PIECE 2, variant: the spatial database is not open -> run() leaves at its first line. */
        val PIECE2_NO_SPATIAL = PIECE2.copy(spatialNull = true)

        /** PIECE 2, variant: the spatial path is empty -> the orphan block is skipped (the other arm). */
        val PIECE2_NO_PATH = PIECE2.copy(spatialPath = "")

        private const val T = 1_762_000_000_000L
        private const val HOUR = 3_600_000L

        /**
         * PIECE 3 -- repairNow. The destructive half, and the one the 11-13 simulation flagged
         * hardest. Every file here exists to pin one branch of 2.7e's classification:
         *
         *   aaaa.gpx   its NAME is a geom_hash the tracks table holds  -> `continue`, untouched
         *   cccc.gpx   mtime 30 s INSIDE the two-hour window           -> "changed in the last 2 hours"
         *   hhhh.gpx   mtime 30 s OUTSIDE it, otherwise identical      -> falls through and IS judged
         *   dddd.gpx   parses to no points                            -> "no points", DELETED
         *   eeee.gpx   its geometry hashes to one we already hold      -> "a copy...", DELETED
         *   ffff.gpx   real points, a hash we do not hold              -> kept, "a real track"
         *   gggg.gpx   would be deleted but delete() fails             -> kept, "(delete failed)"
         *   .hidden.gpx / notes.txt                                    -> skipped by 2.7e's filter
         *
         * ⭐ cccc and hhhh are the pair that matters. They differ ONLY by sixty seconds across the
         * boundary, which is the exact case that flipped between platform flavours in the
         * simulation when the clock was live. With now() scripted they must classify differently
         * and identically on both sides -- that is rule 1 being certified rather than asserted.
         */
        val PIECE3 = GtHkFixture(
            trackRows = listOf(
                GtHkTrackRow("aaaa", "Has A Row", "TRACK"),
                GtHkTrackRow("bbbb", "Also Here", "TRACK"),
            ),
            trackHashes = listOf("aaaa", "bbbb"),
            files = listOf(
                GtHkFile("aaaa.gpx", T - 9 * HOUR, 100, "<gpx>aaaa</gpx>", listOf(1.0 to 2.0), "aaaa"),
                GtHkFile("cccc.gpx", T - 2 * HOUR + 30_000L, 110, "<gpx>cccc</gpx>", listOf(3.0 to 4.0), "cccc"),
                GtHkFile("hhhh.gpx", T - 2 * HOUR - 30_000L, 115, "<gpx>hhhh</gpx>", listOf(9.0 to 9.5), "hhhh"),
                GtHkFile("dddd.gpx", T - 9 * HOUR, 120, "<gpx>dddd</gpx>", emptyList(), ""),
                GtHkFile("eeee.gpx", T - 9 * HOUR, 130, "<gpx>eeee</gpx>", listOf(5.0 to 6.0), "bbbb"),
                GtHkFile("ffff.gpx", T - 9 * HOUR, 140, "<gpx>ffff</gpx>", listOf(7.0 to 8.0), "ffff"),
                GtHkFile("gggg.gpx", T - 9 * HOUR, 150, "<gpx>gggg</gpx>", emptyList(), "", deleteFails = true),
                GtHkFile(".hidden.gpx", T - 9 * HOUR, 10, "<gpx>hidden</gpx>", listOf(1.0 to 1.0), "hide"),
                GtHkFile("notes.txt", T - 9 * HOUR, 10, "not gpx"),
            ),
            nImp = 3,
            nRid = 2,
            orphans = mapOf(
                "route notes" to 4,
                "trail names" to 2,
                "track surveys" to 9,
            ),
        )

        /** PIECE 3, variant: no rider trails to put back -> NEITHER update fires. */
        val PIECE3_NO_RIDER = PIECE3.copy(nImp = 0, nRid = 0)

        /** PIECE 3, variant: no orphans -> every `if (n == 0) continue` fires, no DELETE runs. */
        val PIECE3_NO_ORPHANS = PIECE3.copy(orphans = emptyMap())

        /** PIECE 3, variant: the extension database is not open -> repairNow leaves after the files. */
        val PIECE3_NO_EXT = PIECE3.copy(extNull = true)

        /** PIECE 3, variant: the spatial database is not open -> repairNow leaves at its first line. */
        val PIECE3_NO_SPATIAL = PIECE3.copy(spatialNull = true)

        /**
         * PIECE 4 -- StartupHousekeeping.run. ⛔ THE DESTRUCTIVE CONTAINER: when its marker is
         * missing this DELETES EVERY TRAIL, which is exactly why 2.8 guards it with
         * GtCertGuard.skip. Here it runs for real, against a scratch database we built.
         *
         * The base case is the one that does the work: no marker, a populated table, two columns
         * missing, categories to merge, an ownership file and its .part to prune.
         */
        val PIECE4 = GtHkFixture(
            trackRows = emptyList(),
            files = emptyList(),
            markerPresent = false,
            columnsPresent = setOf("land_status", "use_type"),      // status + carto_code_source must be ALTERed
            trailsPlain = 12,
            categoriesAdded = listOf("dunes", "atv", "scenic"),
            ownershipSuffixes = setOf("", ".part"),
        )

        /** ⭐ THE GATE. Marker present -> ran=false, cleared=0, needsReload=false, and NO other job is skipped. */
        val PIECE4_MARKER = PIECE4.copy(markerPresent = true)

        /** An empty table: cleared=0 but needsReload is STILL true -- a new install routes to the import picker. */
        val PIECE4_EMPTY = PIECE4.copy(trailsPlain = 0)

        /** Every column already there -> no ALTER fires. */
        val PIECE4_ALL_COLUMNS = PIECE4.copy(columnsPresent = setOf("status", "land_status", "use_type", "carto_code_source"))

        /** The database is unavailable -> Result(false, 0, false, "database unavailable"). */
        val PIECE4_NO_DB = PIECE4.copy(spatialNull = true)

        /** The count throws -> the catch arm: ran=true, cleared=0, needsReload=false, error set. */
        val PIECE4_THROWS = PIECE4.copy(countThrows = true)

        /** ⚠ RULE 4 territory: both jobs that can fail do, and the container must still finish. */
        val PIECE4_JOBS_THROW = PIECE4.copy(ensureThrows = true, sweepThrows = true)

        /** No ownership files -> PRUNEUGRC deletes nothing and logs nothing. */
        val PIECE4_NO_OWNERSHIP = PIECE4.copy(ownershipSuffixes = emptySet())
    }
}
