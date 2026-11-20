package com.geeksville.mesh.convoy

/**
 * IOSARTIFACT-2026-11-19: ROUTETH-2026-09-27's trailhead choice, moved out of ConvoyArtifactOps so the
 * shared ArtifactDetailPanel can name it. Package UNCHANGED (com.geeksville.mesh.convoy), so it resolves
 * unqualified in both modules and there is exactly ONE of this type -- the RecordingState pattern, not
 * the TrailFilterState facade that cost four builds on 11-19 (FACADE-2026-11-19).
 *
 * A route is never saved without a trailhead (ROUTETH-2026-09-27), and [isNew] says whether the rider is
 * creating one at the track's first point rather than reusing a trailhead that already exists.
 */
data class RouteTrailhead(val name: String, val lat: Double, val lon: Double, val isNew: Boolean)
