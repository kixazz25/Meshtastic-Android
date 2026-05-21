package com.geeksville.mesh.convoy

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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

object ConvoyTileDownloader {

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
    suspend fun downloadTile(url: String, dest: File): Boolean {
        repeat(2) { attempt ->
            if (!coroutineContext.isActive) return false
            try {
                val success = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")
                .build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        response.body?.bytes()?.let { bytes ->
                            dest.writeBytes(bytes)
                            true
                        } ?: false
                    } else {
                        android.util.Log.w("TileDownloader", "HTTP ${response.code}: $url")
                        response.close()
                        false
                    }
                }
                if (success) return true
            } catch (e: Exception) {
                android.util.Log.e("TileDownloader", "Tile fail: ${dest.path} err=${e.message}")
                if (attempt == 1) return false
                kotlinx.coroutines.delay(500)
            }
        }
        return false
    }

    // ── Download all tiles in batch ───────────────────────────
    // onProgress(downloaded, total, failCount) called after each tile.
    // Checks coroutineContext.isActive before each tile — cancellable.
    suspend fun downloadTiles(
        context: Context,
        tiles: List<TileKey>,
        sourceUrl: String,
        sourceName: String,
        forceOverwrite: Boolean = false,
        onProgress: (downloaded: Int, total: Int, failCount: Int) -> Unit
    ): Result<DownloadSummary> {
        return try {
            var downloaded = 0
            var failed = 0
            val total = tiles.size

            for (tile in tiles) {
                if (!coroutineContext.isActive) {
                    // Cancelled — return partial summary as failure
                    return Result.failure(
                        kotlinx.coroutines.CancellationException("Download cancelled")
                    )
                }

                val dest = tilePath(context, sourceName, tile)

                // Skip tiles already on disk (resume support) unless forceOverwrite
                if (!forceOverwrite && dest.exists() && dest.length() > 0) {
                    downloaded++
                    onProgress(downloaded, total, failed)
                    continue
                }

                val url = buildTileUrl(tile, sourceUrl)
                if (tile.z == 18 && downloaded < 3) android.util.Log.i("TileDownloader", "DL: $url -> ${dest.path}")
                val success = downloadTile(url, dest)

                if (success) downloaded++ else failed++
                onProgress(downloaded, total, failed)
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
        val dir = File(ConvoyConfig.TILE_DIR, slotName)
        if (!dir.exists()) return emptyList()
        val keys = mutableListOf<TileKey>()
        // Directory structure: {slotName}/{z}/{x}/{y}.png
        dir.listFiles()?.filter { it.isDirectory }?.forEach { zDir ->
            val z = zDir.name.toIntOrNull() ?: return@forEach
            zDir.listFiles()?.filter { it.isDirectory }?.forEach { xDir ->
                val x = xDir.name.toIntOrNull() ?: return@forEach
                xDir.listFiles()?.filter { it.isFile && it.name.endsWith(".png") }?.forEach { yFile ->
                    val y = yFile.nameWithoutExtension.toIntOrNull() ?: return@forEach
                    keys.add(TileKey(z, x, y))
                }
            }
        }
        android.util.Log.i("TileDownloader", "Scanned $slotName: ${keys.size} tiles")
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
        val dir = File(ConvoyConfig.TILE_DIR, sourceName)
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    // ── Count tiles and size for a downloaded source ─────────
    fun sourceInfo(context: Context, sourceName: String): Pair<Int, Float> {
        val dir = File(ConvoyConfig.TILE_DIR, sourceName)
        if (!dir.exists()) return Pair(0, 0f)
        var count = 0
        var bytes = 0L
        dir.walkTopDown().filter { it.isFile }.forEach {
            count++
            bytes += it.length()
        }
        return Pair(count, bytes / (1024f * 1024f))
    }
}
