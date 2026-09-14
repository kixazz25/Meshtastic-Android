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
    private const val TILE_FETCH_CONCURRENCY = 10
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
            .replace("{s}", (((tile.x + tile.y) % 4 + 4) % 4).toString())  // SUBDOMAIN-2026-08-02
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
    /**
     * ===================================================================
     * SEGMENTATION SPLITTER  (replaces fixed-12-mile gridCells)
     * ===================================================================
     * Splits one drawn bbox into ceil(realTotal / 50_000) roughly-equal cells so
     * no single download job strands a queue slot. Each cell is a FULL z-stack
     * slice (z10..z18) of its geography -> every cell same shape of work ->
     * comparable -> they finish together in parallel slots = load balancing.
     *
     * ------------------------------------------------------------------
     * WHY sizeTiles is passed IN (not just quickEstimate here):
     *   quickEstimate(bbox).tileCount = ONE LAYER's geographic tiles.
     *   SAT actually pulls THREE layers (base + 2 overlays) over the SAME
     *   geography, each a separate download. So the REAL job volume for SAT is
     *   quickEstimate * 3 (~24,716 * 3 = ~74,148). enqueueArea already knows the
     *   per-slot layer count (slotLayers), so it passes the REAL total in as
     *   sizeTiles. Sizing off the bare 1-layer number under-segments (this was
     *   caught by the dry-run JSON on 2026-07-11: a 74K SAT area reported 24,716
     *   and split into 1 segment when it needed 2).
     *
     * WHY overlap is TILE-WIDTHS and SEAM-ONLY (not a fixed degree amount):
     *   Overlap only exists to stop a boundary-straddling tile from falling into
     *   NEITHER neighbor -> it needs ~1 tile of slop, not a big degree pad.
     *   1 tile at z18 = 360 / 2^18 = 0.00137 deg. The original spec used 1/8 deg
     *   (0.125) = ~91 tiles/edge, which BALLOONED a 24K area to 155K (6x) in the
     *   dry-run. So: overlap = OVERLAP_TILES tile-widths (default 3 ~= 0.004 deg),
     *   applied ONLY when numSegments>1 (a single segment has no internal seam,
     *   so it gets ZERO pad and reports its true tile count).
     *
     * Returns the SAME [north,south,east,west] DoubleArray shape gridCells
     * returned, so the enqueue loop in enqueueArea is UNCHANGED.
     * Logs every cell under "SEGMENT" (adb logcat -s SEGMENT) for visibility.
     * ------------------------------------------------------------------
     */
    fun segmentCells(
        north: Double, south: Double, east: Double, west: Double,
        sizeTiles: Int,                 // REAL job volume for the sizing source (quickEstimate * slotLayers)
        segCeilingTiles: Int = 50_000,
        overlapTiles: Int = 3,          // seam overlap in tile-widths (z18); ~0.004 deg
        zMax: Int = ConvoyConfig.DOWNLOAD_ZOOM
    ): List<DoubleArray> {
        // segment count from the REAL (layer-aware) total
        val n = Math.max(1, Math.ceil(sizeTiles.toDouble() / segCeilingTiles).toInt())

        // overlap in degrees = overlapTiles * (one tile width at zMax); SEAM-ONLY
        val tileDeg = 360.0 / Math.pow(2.0, zMax.toDouble())   // 1 tile width in degrees at zMax
        val pad = if (n > 1) overlapTiles * tileDeg else 0.0    // no seams in a single segment -> no pad

        val dLat = north - south
        val dLon = east - west
        val cutLat = dLat >= dLon                               // cut the LONGER axis
        val cells = mutableListOf<DoubleArray>()
        for (i in 0 until n) {
            val f0 = i.toDouble() / n
            val f1 = (i + 1).toDouble() / n
            val baseN: Double; val baseS: Double; val baseE: Double; val baseW: Double
            if (cutLat) {
                baseN = north - f0 * dLat; baseS = north - f1 * dLat; baseE = east; baseW = west
            } else {
                baseN = north; baseS = south; baseW = west + f0 * dLon; baseE = west + f1 * dLon
            }
            // pad OUTWARD by `pad` on every edge (0 when n==1). hasTile() dedupes overlap on insert.
            val cN = baseN + pad
            val cS = baseS - pad
            val cE = baseE + pad
            val cW = baseW - pad
            cells.add(doubleArrayOf(cN, cS, cE, cW))
            android.util.Log.i("SEGMENT", "cell ${i + 1}/$n N=$cN S=$cS E=$cE W=$cW")
        }
        android.util.Log.i(
            "SEGMENT",
            "SPLIT sizeTiles=$sizeTiles ceiling=$segCeilingTiles segments=$n " +
            "cutAxis=${if (cutLat) "lat" else "lon"} overlapTiles=${if (n > 1) overlapTiles else 0} padDeg=$pad"
        )
        return cells
    }

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
