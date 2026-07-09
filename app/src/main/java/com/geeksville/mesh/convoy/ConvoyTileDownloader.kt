package com.geeksville.mesh.convoy

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

// ============================================================
// ConvoyTileDownloader.kt
// NEW FILE — place at:
//   app/src/main/java/com/geeksville/mesh/convoy/ConvoyTileDownloader.kt
//
// Downloads map tiles from a remote URL template to local device
// storage for offline use. Coroutine-based, cancellable, reports
// progress via callback.
//
// Tile URL format support:
//   Esri SAT/TOPO  : .../tile/{z}/{y}/{x}      (y before x)
//   Carto RD       : .../{z}/{x}/{y}.png        (x before y)
//   Google HYB     : ...x={x}&y={y}&z={z}       (query params)
// ============================================================

data class DownloadSummary(
    val downloaded: Int,
    val failed: Int,
    val totalMB: Float
)

object ConvoyTileDownloader {  // [V2.6b-CONCBACKOFF]
    // [V2.6b] Parallel tile fetch + 429/503 backoff tuning (all network-bound).
    private const val TILE_FETCH_CONCURRENCY = 8
    private const val TILE_MAX_RETRIES = 4
    private const val TILE_BACKOFF_BASE_MS = 400L

    private const val AVG_TILE_BYTES = 15_360L    // 15 KB average
    private const val CONNECT_TIMEOUT_S = 10L
    private const val READ_TIMEOUT_S    = 15L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    // ── Build the full URL for a single tile ─────────────────
    fun buildTileUrl(tile: TileKey, sourceUrl: String): String {
        return sourceUrl
            .replace("{z}", tile.z.toString())
            .replace("{x}", tile.x.toString())
            .replace("{y}", tile.y.toString())
    }

    // ── Build local storage path for a tile ──────────────────
    // Returns: filesDir/tiles/{sourceName}/{z}/{x}/{y}.png
    fun tilePath(context: Context, sourceName: String, tile: TileKey): File {
        val dir = File(ConvoyConfig.TILE_DIR, "${sourceName}/${tile.z}/${tile.x}")
        dir.mkdirs()
        return File(dir, "${tile.y}.png")
    }

    // ── Download a single tile — retry once on failure ───────
    // [V2.6-PASS1-WRITE] MBTiles write redirect
    // Fetch a single tile's bytes (retry once). NO file write - the caller
    // inserts into MBTilesStore. Returns bytes on success, null on failure.
    // [V2.6b] 429 (Too Many Requests) / 503 are treated as RETRYABLE with exponential
    // backoff on the SAME tile — never dropped — since throttling is transient and
    // silently losing tiles under load is worse than a slower download. Honors
    // Retry-After when sent. Genuine absence (404 etc.) returns null quickly.
    suspend fun fetchTileBytes(url: String): ByteArray? {
        var attempt = 0
        while (attempt <= TILE_MAX_RETRIES) {
            if (!coroutineContext.isActive) return null
            try {
                val result = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")
                        .build()
                    val response = client.newCall(request).execute()
                    val code = response.code
                    when {
                        response.isSuccessful -> FetchOutcome(response.body?.bytes(), false, 0L)
                        code == 429 || code == 503 || code == 502 -> {  // [V2.6b-502] 502=transient gateway, retry
                            val retryAfterMs = response.header("Retry-After")?.toLongOrNull()?.times(1000) ?: 0L
                            response.close()
                            android.util.Log.w("TileDownloader", "HTTP $code (retryable) attempt=$attempt: $url")
                            FetchOutcome(null, true, retryAfterMs)
                        }
                        else -> {
                            android.util.Log.w("TileDownloader", "HTTP $code: $url")
                            response.close()
                            FetchOutcome(null, false, 0L)
                        }
                    }
                }
                if (result.bytes != null) return result.bytes
                if (!result.retryable) return null
                val backoff = maxOf(result.retryAfterMs, TILE_BACKOFF_BASE_MS * (1L shl attempt))
                kotlinx.coroutines.delay(backoff)
            } catch (e: Exception) {
                android.util.Log.e("TileDownloader", "Tile fail attempt=$attempt: $url err=${e.message}")
                if (attempt >= TILE_MAX_RETRIES) return null
                kotlinx.coroutines.delay(TILE_BACKOFF_BASE_MS * (1L shl attempt))
            }
            attempt++
        }
        return null
    }

    // [V2.6b] Outcome of one fetch attempt.
    private data class FetchOutcome(val bytes: ByteArray?, val retryable: Boolean, val retryAfterMs: Long)

    // ── Download all tiles in batch ───────────────────────────
    // onProgress(downloaded, total, failCount) called after each tile.
    // Checks coroutineContext.isActive before each tile — cancellable.
    suspend fun downloadTiles(
        context: Context,
        tiles: List<TileKey>,
        sourceUrl: String,
        sourceName: String,
        forceOverwrite: Boolean = false,
        isOverlay: Boolean = false,   // [V2.6a-WEBP] role -> codec dispatch
        onProgress: (downloaded: Int, total: Int, failCount: Int) -> Unit
    ): Result<DownloadSummary> {
        return try {
            var downloaded = 0
            var failed = 0
            val total = tiles.size

            // [V2.6b-CONCURRENCY] Fetch tiles in parallel batches (network is the
            // bottleneck; OkHttp is thread-safe). INSERT serially per batch —
            // MBTilesStore holds one cached SQLite handle per type, so concurrent
            // inserts are unsafe. Overlaps network waits without racing the DB.
            var loggedZ18 = 0
            for (fetchBatch in tiles.chunked(TILE_FETCH_CONCURRENCY)) {
                if (!coroutineContext.isActive) {
                    return Result.failure(
                        kotlinx.coroutines.CancellationException("Download cancelled")
                    )
                }
                // Phase 1 (parallel): resolve each tile to skip / fetched-bytes / fail. No DB.
                val results = coroutineScope {
                    fetchBatch.map { tile ->
                        async {
                            // [V2.6-PASS1-WRITE] resume-skip via MBTilesStore (sourceName = type)
                            if (!forceOverwrite && MBTilesStore.hasTile(sourceName, tile.z, tile.x, tile.y)) {
                                Triple(tile, null as ByteArray?, true)   // already present
                            } else {
                                val url = buildTileUrl(tile, sourceUrl)
                                if (tile.z == 18 && loggedZ18 < 3) {
                                    loggedZ18++
                                    android.util.Log.i("TileDownloader", "DL: $url -> $sourceName.mbtiles z${tile.z}/${tile.x}/${tile.y}")
                                }
                                Triple(tile, fetchTileBytes(url), false)
                            }
                        }
                    }.map { it.await() }
                }
                // Phase 2 (serial): insert in order into the single SQLite handle.
                for ((tile, bytes, skipped) in results) {
                    if (skipped) {
                        downloaded++
                    } else {
                        val success = if (bytes != null) MBTilesStore.insertTile(sourceName, tile.z, tile.x, tile.y, bytes, isOverlay) else false
                        if (success) downloaded++ else failed++
                    }
                    onProgress(downloaded, total, failed)
                }
            }

            // Overlay download removed from here.
            // ConvoyViewModel now iterates ALL layers from MapSourceManager,
            // including overlays. Each layer downloaded with its own cacheDir.
            // No source-specific logic needed in the downloader.
            val totalMB = (downloaded * AVG_TILE_BYTES) / (1024f * 1024f)
            Result.success(DownloadSummary(downloaded, failed, totalMB))

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Scan existing tiles to TileKey list (for refresh) ────
    fun scanTilesToKeys(slotName: String): List<TileKey> {
        // [V2.6-PASS1-S4] DB-backed: slotName is the type (= old cache_dir).
        val keys = MBTilesStore.scanKeys(slotName)
        android.util.Log.i("TileDownloader", "Scanned $slotName: ${keys.size} tiles (mbtiles)")
        return keys
    }

    /** Calculate lat/lon bounding box from existing tiles on disk */
    fun tileBoundsLatLon(slotName: String): DoubleArray? {
        val keys = scanTilesToKeys(slotName)
        if (keys.isEmpty()) return null
        val zoomGroups = keys.groupBy { it.z }
        val bestZ = zoomGroups.maxByOrNull { it.value.size }?.key ?: return null
        val tilesAtZ = zoomGroups[bestZ] ?: return null
        val minX = tilesAtZ.minOf { it.x }
        val maxX = tilesAtZ.maxOf { it.x }
        val minY = tilesAtZ.minOf { it.y }
        val maxY = tilesAtZ.maxOf { it.y }
        val n = 1 shl bestZ
        val north = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * minY / n))))
        val south = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (maxY + 1) / n))))
        val west = minX.toDouble() / n * 360.0 - 180.0
        val east = (maxX + 1).toDouble() / n * 360.0 - 180.0
        android.util.Log.i("TileDownloader", "Bounds for $slotName: N=${"%.4f".format(north)} S=${"%.4f".format(south)} E=${"%.4f".format(east)} W=${"%.4f".format(west)}")
        return doubleArrayOf(north, south, east, west)
    }

    /** Divide bounds into grid cells at roughly gridMiles resolution */
    fun gridCells(north: Double, south: Double, east: Double, west: Double, gridMiles: Double = 12.0): List<DoubleArray> {
        val step = gridMiles / 69.0
        val cells = mutableListOf<DoubleArray>()
        var lat = south
        while (lat < north) {
            val cellN = minOf(lat + step, north)
            var lon = west
            while (lon < east) {
                val cellE = minOf(lon + step, east)
                cells.add(doubleArrayOf(cellN, lat, cellE, lon))
                lon += step
            }
            lat += step
        }
        android.util.Log.i("TileDownloader", "Grid: ${cells.size} cells at ${gridMiles}mi")
        return cells
    }

    // ── Delete all tiles for a source ────────────────────────
    fun deleteSource(context: Context, sourceName: String): Boolean {
        // [V2.6-PASS1-S4] DB-backed: drop the <type>.mbtiles file.
        return MBTilesStore.deleteSource(sourceName)
    }

    // ── Count tiles and size for a downloaded source ─────────
    fun sourceInfo(context: Context, sourceName: String): Pair<Int, Float> {
        // [V2.6-PASS1-S4] DB-backed: tile count + .mbtiles file size.
        return MBTilesStore.sourceInfo(sourceName)
    }
}
