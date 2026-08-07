package com.geeksville.mesh.convoy

import android.content.Context

/**
 * CORRDELETE-STAGE1-2026-08-07B
 *
 * Corridor tile deletion -- STAGE 1 (preview only).
 *
 * Mirrors ConvoyCorridorWorker's discovery exactly, so the tiles counted here
 * are the same tiles that worker would download:
 *
 *     geom_hash -> SpatialDbManager.getTrackPoints(ctx, geomHash)   (worker :68)
 *     segments  -> ConvoyTileCalculator.corridorTiles(segments)     (worker :74)
 *
 * The default buffer in corridorTiles (0.00724 deg, ~half a mile either side)
 * is deliberately NOT overridden -- a preview computed with a different buffer
 * than the download used would report a tile set that does not match the store.
 *
 * STAGE 1 DELETES NOTHING. It exists so the count is known before anything
 * irreversible happens. Nothing calls it yet.
 */
object ConvoyCorridorDelete {

    private const val TAG = "ConvoyCorridorDelete"

    /**
     * Per-track preview result.
     *
     * @param geomHash  the track's geom_hash (also the key getTrackPoints takes)
     * @param name      track name at preview time, or null when the row has none.
     *                  Nullable is correct here rather than a placeholder string:
     *                  a missing name is a real state the caller must be able to
     *                  distinguish from a track literally named "Unnamed", and
     *                  the display layer already has a fallback for null.
     * @param tileCount corridor tiles this track would contribute
     */
    data class TrackPreview(
        val geomHash: String,
        val name: String?,
        val tileCount: Int
    )

    /**
     * Full preview across every track carrying geometry.
     *
     * @param tracks           tracks that produced a non-empty corridor
     * @param skippedNoGeom    hash present but getTrackPoints returned nothing
     * @param skippedEmpty     geometry present but corridor came back empty
     * @param totalTiles       distinct tiles across all tracks
     * @param overlapSavings   sum of per-track counts minus totalTiles --
     *                         tiles shared by two or more corridors. Reported
     *                         because the naive sum overstates the real delete,
     *                         and an unexplained gap between the two numbers is
     *                         exactly what would look like a bug later.
     */
    data class PreviewResult(
        val tracks: List<TrackPreview>,
        val skippedNoGeom: Int,
        val skippedEmpty: Int,
        val totalTiles: Int,
        val overlapSavings: Int
    )

    /**
     * Count what a full corridor delete would remove. Deletes nothing.
     *
     * Runs a DB read per track and a tile computation per track, so it must not
     * be called on the main thread.
     */
    fun previewAllTracks(context: Context): PreviewResult {
        val hashes = SpatialDbManager.allTrackGeomHashes()
        android.util.Log.i(TAG, "CORRDELETE-STAGE1-2026-08-07B preview start: ${hashes.size} tracks with geom_hash")

        val previews = ArrayList<TrackPreview>()
        val distinct = HashSet<TileKey>()
        var skippedNoGeom = 0
        var skippedEmpty = 0
        var naiveSum = 0

        for ((geomHash, name) in hashes) {
            // Same guard order as ConvoyCorridorWorker :70 and :76 -- a track
            // with no captured points is a known real case (a recording that
            // caught zero GPS fixes), not an error.
            val segments = SpatialDbManager.getTrackPoints(context, geomHash)
            if (segments == null || segments.isEmpty()) {
                skippedNoGeom++
                continue
            }
            val corridor = ConvoyTileCalculator.corridorTiles(segments)
            if (corridor.isEmpty()) {
                skippedEmpty++
                continue
            }
            naiveSum += corridor.size
            distinct.addAll(corridor)
            previews.add(TrackPreview(geomHash, name, corridor.size))
        }

        val result = PreviewResult(
            tracks = previews,
            skippedNoGeom = skippedNoGeom,
            skippedEmpty = skippedEmpty,
            totalTiles = distinct.size,
            overlapSavings = naiveSum - distinct.size
        )
        android.util.Log.i(
            TAG,
            "CORRDELETE-STAGE1-2026-08-07B preview done: ${previews.size} tracks, ${result.totalTiles} distinct tiles, " +
                "${result.overlapSavings} shared, skipped noGeom=$skippedNoGeom empty=$skippedEmpty"
        )
        return result
    }
}
