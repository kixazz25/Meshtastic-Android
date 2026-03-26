import sys

path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# Find the cleanup block by searching for onCleared
anchor = "    override fun onCleared()"

if anchor not in content:
    print("ERROR: anchor 'override fun onCleared()' not found")
    sys.exit(1)

additions = """    // ── Download state ──────────────────────────────────────────────────
    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val downloaded: Int, val total: Int, val failCount: Int) : DownloadState()
        data class Complete(val summary: DownloadSummary) : DownloadState()
        object Cancelled : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    data class PendingDownload(
        val tileCount: Int,
        val sizeMB: Float,
        val withinCeiling: Boolean,
        val north: Double,
        val south: Double,
        val east: Double,
        val west: Double,
        val sourceName: String,
        val sourceUrl: String
    )

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _pendingDownload = MutableStateFlow<PendingDownload?>(null)
    val pendingDownload: StateFlow<PendingDownload?> = _pendingDownload.asStateFlow()

    private var downloadJob: kotlinx.coroutines.Job? = null

    fun setPendingDownload(pending: PendingDownload) {
        _pendingDownload.value = pending
    }

    fun clearPendingDownload() {
        _pendingDownload.value = null
    }

    fun startDownload(context: android.content.Context, pending: PendingDownload) {
        clearPendingDownload()
        downloadJob = viewModelScope.launch {
            _downloadState.value = DownloadState.Downloading(0, pending.tileCount, 0)
            val tiles = ConvoyTileCalculator.calculateTiles(
                pending.north, pending.south, pending.east, pending.west
            )
            val result = ConvoyTileDownloader.downloadTiles(
                context = context,
                tiles = tiles,
                sourceUrl = pending.sourceUrl,
                sourceName = pending.sourceName
            ) { downloaded, total, failCount ->
                _downloadState.value = DownloadState.Downloading(downloaded, total, failCount)
            }
            result.fold(
                onSuccess = { summary ->
                    _downloadState.value = DownloadState.Complete(summary)
                    kotlinx.coroutines.delay(3_000L)
                    _downloadState.value = DownloadState.Idle
                },
                onFailure = { e ->
                    _downloadState.value = DownloadState.Error(e.message ?: "Download failed")
                }
            )
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _downloadState.value = DownloadState.Cancelled
        viewModelScope.launch {
            kotlinx.coroutines.delay(2_000L)
            _downloadState.value = DownloadState.Idle
        }
    }

"""

content = content.replace(anchor, additions + anchor)

with open(path, "w", encoding="utf-8") as f:
    f.write(content)

print("PATCHED OK")
