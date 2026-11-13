package com.geeksville.mesh.grouptrack

import com.grouptrack.shared.startup.GtHkFixture
import java.io.File

/**
 * STEP17G2F3-2026-11-13 ⚠ TEST-ONLY (CODE RULE 3 -- remove with GtCertRun before 2.8 is released):
 * the collaborators the generated 2.7e copies call instead of the real ones, all answering from the
 * same GtHkFixture the shared side is scripted from.
 *
 * ⛔ WHY EACH ONE IS SUBSTITUTED RATHER THAN LEFT REAL:
 *
 *   parse / hash   SpatialDbManager.parseGpxTrackPoints and .computeGeomHash are a GPX parser and a
 *                  hash function -- content-shaped maths owned by the geometry and database groups.
 *                  Left real, a G2f DIFF could mean "the hash differs", which says nothing about
 *                  startup housekeeping. ⭐ G2f certifies the CLASSIFICATION 2.7e makes once it has
 *                  those answers, so both sides are handed the same answers.
 *
 *   useOf          TrailClassifier.useOf("rider") is interpolated into an UPDATE statement. Same
 *                  reasoning: the classifier is its own certification.
 *
 *   delete         2.7e has a branch for delete() RETURNING FALSE, and there is no honest way to
 *                  make a real File.delete() fail on a writable scratch directory. Scripting it is
 *                  how that branch gets exercised at all. ⚠ When the fixture does not mark a file,
 *                  this really does delete it -- the destructive path is genuinely taken.
 */
object GtHkCertStubs {

    private var points: Map<String, List<Pair<Double, Double>>> = emptyMap()
    private var hashes: Map<String, String> = emptyMap()
    private var failDelete: Set<String> = emptySet()

    // ---- PIECE 4. TrailFilterState and HomeStateImportController, scripted.
    // ⚠ Both of 2.7e's catch arms matter: a palette failure and a sweep failure must each be
    // swallowed and the container must still finish (its own rule 4).
    private var ensureThrows = false
    private var sweepThrows = false
    private var categories: List<String> = emptyList()

    fun load(fx: GtHkFixture) {
        points = fx.files.associate { it.text to it.points }
        hashes = fx.files.associate { wktOf(it.points) to it.geomHash }
        failDelete = fx.files.filter { it.deleteFails }.map { it.name }.toSet()
        ensureThrows = fx.ensureThrows
        sweepThrows = fx.sweepThrows
        categories = fx.categoriesAdded
    }

    fun ensureDefaults() {
        if (ensureThrows) throw IllegalStateException("cert: ensureDefaults throws")
        GtHkCertLog.effect("ensureDefaults")
    }

    fun mergeCategories(): List<String> = categories

    fun sweepManifests() {
        if (sweepThrows) throw IllegalStateException("cert: sweepManifests throws")
        GtHkCertLog.effect("sweepManifests")
    }

    /** The LINESTRING 2.7e builds, character for character (repairNow line 49). */
    private fun wktOf(pts: List<Pair<Double, Double>>): String =
        "LINESTRING(" + pts.joinToString(",") { "${it.first} ${it.second}" } + ")"

    fun parse(text: String): List<Pair<Double, Double>> = points[text] ?: emptyList()
    fun hash(wkt: String): String = hashes[wkt] ?: ""
    fun useOf(cartoCode: String): String = "MOTORIZED"

    /** Scripted failure; otherwise the real delete, so the destructive path really runs. */
    fun delete(f: File): Boolean = if (f.name in failDelete) false else f.delete()
}
