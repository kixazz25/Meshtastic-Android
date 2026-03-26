import sys

screen_path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"
vm_path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt"

# ── Part 1: Add downloadStartTime to ViewModel ───────────────
with open(vm_path, "r", encoding="utf-8") as f:
    vm = f.read()

old1 = "    private var downloadJob: kotlinx.coroutines.Job? = null"
new1 = "    private var downloadJob: kotlinx.coroutines.Job? = null\n    var downloadStartTime: Long = 0L"

if old1 in vm:
    vm = vm.replace(old1, new1)
    print("Part 1 OK — downloadStartTime added to ViewModel")
else:
    print("ERROR: Part 1 anchor not found")
    sys.exit(1)

old2 = "    fun startDownload(context: android.content.Context, pending: PendingDownload) {\n        clearPendingDownload()\n        downloadJob = viewModelScope.launch {"
new2 = "    fun startDownload(context: android.content.Context, pending: PendingDownload) {\n        clearPendingDownload()\n        downloadStartTime = System.currentTimeMillis()\n        downloadJob = viewModelScope.launch {"

if old2 in vm:
    vm = vm.replace(old2, new2)
    print("Part 2 OK — startTime recorded on download start")
else:
    print("ERROR: Part 2 anchor not found")
    sys.exit(1)

with open(vm_path, "w", encoding="utf-8") as f:
    f.write(vm)

# ── Part 3: Update chip text to show ETA ─────────────────────
with open(screen_path, "r", encoding="utf-8") as f:
    screen = f.read()

old3 = '                    Text("⬇ ${ds.downloaded} / ${ds.total} tiles", color = Color(0xFF1CF0A0), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)'

new3 = '''                    androidx.compose.foundation.layout.Column {
                        Text("⬇ ${ds.downloaded} / ${ds.total} tiles", color = Color(0xFF1CF0A0), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        val elapsedMs = System.currentTimeMillis() - viewModel.downloadStartTime
                        val rate = if (elapsedMs > 2000 && ds.downloaded > 0) ds.downloaded.toFloat() / (elapsedMs / 1000f) else 0f
                        if (rate > 0f) {
                            val remaining = ((ds.total - ds.downloaded) / rate).toInt()
                            val etaText = if (remaining >= 60) "${remaining / 60}m ${remaining % 60}s" else "${remaining}s"
                            Text("~$etaText remaining", color = Color(0xFF4A6080), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                    }'''

if old3 in screen:
    screen = screen.replace(old3, new3)
    print("Part 3 OK — ETA text added to chip")
else:
    print("ERROR: Part 3 anchor not found")
    sys.exit(1)

with open(screen_path, "w", encoding="utf-8") as f:
    f.write(screen)

print("ALL PATCHED OK")
