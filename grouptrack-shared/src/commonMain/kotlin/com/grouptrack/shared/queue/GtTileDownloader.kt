package com.grouptrack.shared.queue

import com.grouptrack.shared.imports.GtHttpBytes
import com.grouptrack.shared.log.gtLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/*
 * TILEDL-2026-11-25 -- the map tile downloader, SHARED (release 2.8b, map downloads item 7).
 *
 * Relocated from app/.../convoy/ConvoyTileDownloader.kt (2.7e: [V2.6b-CONCBACKOFF], SUBDOMAIN-2026-08-02,
 * [V2.6b-502], ZCAP2-2026-09-14, [V2.6b-CONCURRENCY]) with the bodies as they were. What a shared file cannot do
 * itself is the HOST's, passed in:
 *   OkHttp                          -> http (GtHttpBytes): Android ConvoyTileDownloader.bytesHttp, iPad IosHttpBytes
 *   withContext(Dispatchers.IO)     -> io: Android Dispatchers.IO, iPad Dispatchers.Default (IO is internal on native)
 *   MBTilesStore.hasTile/insertTile -> hasTile / insertTile: Android MBTilesStore (TileCodec for a base layer),
 *                                      iPad GtMBTiles (every live slot, VEC and DEM, is "overlay": stored as received)
 *   ConvoyConfig.DOWNLOAD_ZOOM      -> downloadZoom (GtTileCalc.maxZoomForSlot takes it as a parameter)
 * CODE RULE 1: no defaults -- each host wires every parameter.
 */

/** 2.7e DownloadSummary. */
class GtTileDownloadSummary(val downloaded: Int, val failed: Int, val totalMB: Float)

class GtTileDownloader(
    private val http: GtHttpBytes,
    private val io: CoroutineDispatcher,
    private val hasTile: (type: String, z: Int, x: Int, y: Int) -> Boolean,
    private val insertTile: (type: String, z: Int, x: Int, y: Int, bytes: ByteArray, isOverlay: Boolean) -> Boolean,
    private val downloadZoom: () -> Int,
) {
    companion object {
        // [V2.6b-CONCBACKOFF]
        const val TILE_FETCH_CONCURRENCY = 10
        const val TILE_MAX_RETRIES = 4
        const val TILE_BACKOFF_BASE_MS = 400L
        const val AVG_TILE_BYTES = 15_360L    // 15 KB average
        /** 2.7e's tile request header, unchanged -- tile servers were measured to answer it. */
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

        /** 2.7e buildTileUrl. SUBDOMAIN-2026-08-02: {s} from (x + y) mod 4. */
        fun buildTileUrl(tile: GtTileKey, sourceUrl: String): String {
            return sourceUrl
                .replace("{s}", (((tile.x + tile.y) % 4 + 4) % 4).toString())
                .replace("{z}", tile.z.toString())
                .replace("{x}", tile.x.toString())
                .replace("{y}", tile.y.toString())
        }
    }

    private val log = gtLogger("TileDownloader")   // 2.7e's tag

    /**
     * 2.7e fetchTileBytes: one tile's bytes, NO store write (the caller writes). 429 / 503 / 502 retry with
     * exponential backoff, honouring Retry-After; any other non-2xx gives up at once; a transport failure (2.7e's
     * catch) retries up to TILE_MAX_RETRIES.
     */
    suspend fun fetchTileBytes(url: String): ByteArray? {
        var attempt = 0
        while (attempt <= TILE_MAX_RETRIES) {
            if (!currentCoroutineContext().isActive) return null
            val r = withContext(io) { http.getBytes(url, USER_AGENT) }
            val failure = r.failure
            if (failure != null) {
                log.e { "Tile fail attempt=$attempt: $url err=$failure" }
                if (attempt >= TILE_MAX_RETRIES) return null
                delay(TILE_BACKOFF_BASE_MS * (1L shl attempt))
            } else {
                val code = r.code
                when {
                    code in 200..299 -> return r.bytes   // 2.7e: a success with no body is not retried
                    code == 429 || code == 503 || code == 502 -> {   // [V2.6b-502] 502 = transient gateway
                        val retryAfterMs = (r.retryAfterSec ?: 0L) * 1000
                        log.w { "HTTP $code (retryable) attempt=$attempt: $url" }
                        delay(maxOf(retryAfterMs, TILE_BACKOFF_BASE_MS * (1L shl attempt)))
                    }
                    else -> {
                        log.w { "HTTP $code: $url" }
                        return null
                    }
                }
            }
            attempt++
        }
        return null
    }

    /**
     * 2.7e downloadTiles. ZCAP2-2026-09-14: tiles above the slot's cap are skipped. [V2.6b-CONCURRENCY]: batches of
     * TILE_FETCH_CONCURRENCY fetched in parallel, then written one by one; a tile already stored counts as
     * downloaded unless [forceOverwrite]. [onProgress] fires once per tile, as 2.7e.
     */
    suspend fun downloadTiles(
        tiles: List<GtTileKey>,
        sourceUrl: String,
        sourceName: String,
        forceOverwrite: Boolean,
        isOverlay: Boolean,
        onProgress: (downloaded: Int, total: Int, failCount: Int) -> Unit,
    ): Result<GtTileDownloadSummary> {
        return try {
            val zCap = GtTileCalc.maxZoomForSlot(sourceName, downloadZoom())
            val capTiles = tiles.filter { it.z <= zCap }
            if (capTiles.size != tiles.size) {
                log.i { "ZCAP2-2026-09-14: $sourceName cap z$zCap -- skipping ${tiles.size - capTiles.size} of ${tiles.size} tiles" }
            }
            var downloaded = 0
            var failed = 0
            val total = capTiles.size

            var loggedZ18 = 0
            for (fetchBatch in capTiles.chunked(TILE_FETCH_CONCURRENCY)) {
                if (!currentCoroutineContext().isActive) {
                    return Result.failure(CancellationException("Download cancelled"))
                }
                val results = coroutineScope {
                    fetchBatch.map { tile ->
                        async {
                            if (!forceOverwrite && hasTile(sourceName, tile.z, tile.x, tile.y)) {
                                Triple(tile, null as ByteArray?, true)   // already present
                            } else {
                                val url = buildTileUrl(tile, sourceUrl)
                                if (tile.z == 18 && loggedZ18 < 3) {
                                    loggedZ18++
                                    log.i { "DL: $url -> $sourceName.mbtiles z${tile.z}/${tile.x}/${tile.y}" }
                                }
                                Triple(tile, fetchTileBytes(url), false)
                            }
                        }
                    }.map { it.await() }
                }
                for ((tile, bytes, skipped) in results) {
                    if (skipped) {
                        downloaded++
                    } else {
                        val success = if (bytes != null) insertTile(sourceName, tile.z, tile.x, tile.y, bytes, isOverlay) else false
                        if (success) downloaded++ else failed++
                    }
                    onProgress(downloaded, total, failed)
                }
            }

            val totalMB = (downloaded * AVG_TILE_BYTES) / (1024f * 1024f)
            Result.success(GtTileDownloadSummary(downloaded, failed, totalMB))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
