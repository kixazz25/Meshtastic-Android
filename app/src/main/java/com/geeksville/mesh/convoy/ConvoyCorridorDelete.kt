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
    /**
     * @param onDiskByStore  store name -> tiles of this corridor set actually
     *                       present in that store. CORRDELETE-ONDISK-2026-08-07D.
     * @param onDiskTotal    sum across stores -- the number the delete should
     *                       actually remove, and the figure stage 3 verifies
     *                       against. ALWAYS <= totalTiles: a corridor crossing
     *                       ground that was never downloaded contributes
     *                       geometry tiles that are not in any store.
     */
    data class PreviewResult(
        val tracks: List<TrackPreview>,
        val skippedNoGeom: Int,
        val skippedEmpty: Int,
        val totalTiles: Int,
        val overlapSavings: Int,
        val onDiskByStore: Map<String, Int>,
        val onDiskTotal: Int
    )

    /**
     * CORRMIGRATE-2026-08-07H: per-track delete outcome.
     */
    data class DeleteResult(
        val tracksProcessed: Int,
        val tilesRemoved: Int,
        val byStore: Map<String, Int>
    )

    /**
     * CORRMIGRATE-2026-08-07H -- THE IRREVERSIBLE STEP.
     *
     * Deletes every track's corridor tiles from the slot's store(s). Recovery
     * is a full corridor re-download; there is no undo.
     *
     * Runs INLINE, not through the queue. The caller must have called
     * holdQueue() and cancelAll() first -- otherwise a running job can write
     * tiles into a store this loop is removing from.
     *
     * Discovery is identical to previewAllTracks() above and to
     * ConvoyCorridorWorker, so the tiles removed are the tiles that were
     * counted and the tiles the corridor download would fetch.
     *
     * A completion row is written per track AFTER its delete, carrying the
     * actual removed count and the geom_hash so the queue panel can resolve
     * the track name.
     *
     * Background thread only.
     */
    fun deleteAllTrackCorridors(
        context: Context,
        slotName: String,
        // CORRPROGRESS-2026-08-07K: REQUIRED, deliberately not defaulted.
        // No caller legitimately wants a silent multi-minute irreversible
        // delete, so an optional callback would be a shortcut rather than a
        // state a caller could actually be in. (CODE RULE 1.)
        // ⛔ FIRES ON THE CALLING THREAD, which is Dispatchers.IO. The callback
        // must not touch Compose state or show a Toast directly -- the caller
        // is responsible for crossing back to main.
        onProgress: (done: Int, total: Int, trackName: String) -> Unit
    ): DeleteResult {
        MapSourceManager.init(context)
        val hashes = SpatialDbManager.allTrackGeomHashes()
        android.util.Log.i(TAG, "CORRMIGRATE-2026-08-07H delete start: ${hashes.size} tracks")

        // Same live-layer derivation as ConvoyDownloadQueue.enqueueDelete :354-358.
        val storeNames: List<String> = run {
            val layers = MapSourceManager.getSourceByKey(slotName)?.layers ?: emptyList()
            if (layers.isEmpty()) listOf(slotName)
            else layers.mapIndexed { i, l -> if (i == 0) slotName else l.cacheDir }
        }

        val byStore = LinkedHashMap<String, Int>()
        var processed = 0
        var total = 0

        for ((geomHash, name) in hashes) {
            val segments = SpatialDbManager.getTrackPoints(context, geomHash)
            if (segments == null || segments.isEmpty()) continue
            val corridor = ConvoyTileCalculator.corridorTiles(segments)
            if (corridor.isEmpty()) continue

            var removedThisTrack = 0
            for (store in storeNames) {
                val removed = MBTilesStore.deleteTiles(store, corridor)
                removedThisTrack += removed
                byStore[store] = (byStore[store] ?: 0) + removed
            }
            total += removedThisTrack
            processed++

            // Label carries the track name -- with 88 tracks a queue of
            // "DEL CORR" rows is unreadable. Falls back to a short hash so a
            // nameless track still produces a diagnosable row.
            val shown = if (name.isNullOrBlank()) geomHash.take(8) else name
            DownloadQueueManager.recordCompletedDelete(
                context, "DEL CORR $shown", removedThisTrack, geomHash
            )
            // CORRPROGRESS-2026-08-07K: reported AFTER the row is written, so
            // "N done" means N tracks are fully accounted for -- deleted AND
            // recorded -- not merely started.
            onProgress(processed, hashes.size, shown)
        }

        android.util.Log.i(TAG,
            "CORRMIGRATE-2026-08-07H delete done: $processed tracks, $total tiles, $byStore")
        return DeleteResult(processed, total, byStore)
    }

    /**
     * Count what a full corridor delete would remove. Deletes nothing.
     *
     * Runs a DB read per track and a tile computation per track, so it must not
     * be called on the main thread.
     */
    fun previewAllTracks(context: Context, slotName: String): PreviewResult {
        MapSourceManager.init(context)
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

        // CORRDELETE-ONDISK-2026-08-07D: store list copied from
        // ConvoyDownloadQueue.enqueueDelete :354-358. Reads the LIVE layer
        // assignment, so it is right under Esri (3 stores) and Hybrid (1)
        // without this function knowing which is active.
        val storeNames: List<String> = run {
            val layers = MapSourceManager.getSourceByKey(slotName)?.layers ?: emptyList()
            if (layers.isEmpty()) listOf(slotName)
            else layers.mapIndexed { i, l -> if (i == 0) slotName else l.cacheDir }
        }
        val distinctList = distinct.toList()
        val onDisk = LinkedHashMap<String, Int>()
        for (store in storeNames) {
            onDisk[store] = MBTilesStore.countTiles(store, distinctList)
        }
        val onDiskTotal = onDisk.values.sum()

        val result = PreviewResult(
            tracks = previews,
            skippedNoGeom = skippedNoGeom,
            skippedEmpty = skippedEmpty,
            totalTiles = distinct.size,
            overlapSavings = naiveSum - distinct.size,
            onDiskByStore = onDisk,
            onDiskTotal = onDiskTotal
        )
        android.util.Log.i(
            TAG,
            "CORRDELETE-STAGE1-2026-08-07B preview done: ${previews.size} tracks, ${result.totalTiles} distinct tiles, " +
                "${result.overlapSavings} shared, skipped noGeom=$skippedNoGeom empty=$skippedEmpty, " +
                "ON DISK=${result.onDiskTotal} across ${onDisk.size} store(s) $onDisk"
        )
        return result
    }
}
