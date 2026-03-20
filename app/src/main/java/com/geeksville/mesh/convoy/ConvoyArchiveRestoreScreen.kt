package com.geeksville.mesh.convoy

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geeksville.mesh.model.UIViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.meshtastic.proto.DeviceProfile
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * ConvoyArchiveRestoreScreen
 *
 * Lists all archived .cfg files for the current node sorted newest first.
 * User selects an archive to review then taps RESTORE to install it.
 *
 * Flow:
 *   1. Read nodeId from myNodeInfo
 *   2. List files in convoy_backups/<nodeId>/
 *   3. Show sorted list — newest first
 *   4. User selects archive
 *   5. User taps RESTORE — installProfileToRadio()
 *   6. 60s wait for reboot
 *   7. Navigate back
 */
@Composable
fun ConvoyArchiveRestoreScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    uiViewModel: UIViewModel = hiltViewModel(),
    convoyViewModel: ConvoyViewModel = hiltViewModel()
) {
    val context         = LocalContext.current
    val scope           = rememberCoroutineScope()
    val connectionState by uiViewModel.connectionState.collectAsStateWithLifecycle()
    val myNodeInfo      by convoyViewModel.myNodeInfo.collectAsStateWithLifecycle()
    val isConnected     = connectionState.toString().contains("Connected", ignoreCase = true)

    val nodeId   = myNodeInfo?.myNodeNum?.let { "!%08x".format(it) } ?: ""
    val archiveDir = remember(nodeId) {
        if (nodeId.isNotEmpty())
            File(context.filesDir, "convoy_backups/$nodeId")
        else null
    }

    // Load archive list
    val archives = remember(archiveDir) {
        archiveDir?.listFiles { f -> f.extension == "cfg" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    var selectedFile    by remember { mutableStateOf<File?>(null) }
    var phase           by remember { mutableStateOf("SELECT") } // SELECT, CONFIRM, RESTORING, WAITING, DONE, FAILED
    var countdown       by remember { mutableStateOf(60) }
    var statusMsg       by remember { mutableStateOf("") }
    var logLines        by remember { mutableStateOf(listOf<String>()) }

    fun addLog(msg: String) {
        Log.i("ConvoyArchive", msg)
        logLines = logLines + msg
    }

    fun formatFileName(file: File): String {
        // !1d5bdc79_20260319_125157_pre_master.cfg → 2026-03-19 12:51:57
        val parts = file.nameWithoutExtension.split("_")
        return try {
            val date = parts[1] // 20260319
            val time = parts[2] // 125157
            "${date.substring(0,4)}-${date.substring(4,6)}-${date.substring(6,8)} " +
            "${time.substring(0,2)}:${time.substring(2,4)}:${time.substring(4,6)}"
        } catch (e: Exception) {
            file.name
        }
    }

    fun formatFileSize(file: File): String {
        val bytes = file.length()
        return "${bytes}b"
    }

    fun restoreArchive(file: File) {
        scope.launch {
            phase = "RESTORING"
            statusMsg = "\u25cc Installing archive..."
            addLog("Restoring: ${file.name}")

            try {
                val nodeNum = myNodeInfo?.myNodeNum
                if (nodeNum == null) {
                    addLog("\u2717 No node info — cannot restore")
                    phase = "FAILED"
                    statusMsg = "\u2717 No node connected"
                    return@launch
                }

                val profileBytes = file.readBytes()
                val profile = DeviceProfile.ADAPTER.decode(profileBytes)
                convoyViewModel.installProfileToRadio(nodeNum, profile)
                addLog("\u2713 Install sent — radio rebooting")
                statusMsg = "\u2713 Archive installed — radio rebooting..."

                // Wait 60s for reboot
                phase = "WAITING"
                for (i in 60 downTo 1) {
                    countdown = i
                    statusMsg = "\u25cc Waiting for radio reboot... ${i}s"
                    delay(1000)
                }
                countdown = 0
                phase = "DONE"
                statusMsg = "\u25cf Restore complete — radio ready"
                addLog("Restore complete")

            } catch (e: Exception) {
                addLog("\u2717 Restore failed: ${e.message}")
                phase = "FAILED"
                statusMsg = "\u2717 Restore failed: ${e.message}"
            }
        }
    }

    BackHandler(enabled = phase == "RESTORING" || phase == "WAITING") { }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101510))) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // Header
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("\u2190", color = Color(0xFF97D5A5), fontSize = 20.sp,
                    modifier = Modifier.clickable(enabled = phase != "RESTORING" && phase != "WAITING") {
                        onBack()
                    }.padding(end = 12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("ARCHIVE RESTORE", color = Color(0xFF97D5A5), fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp)
                    Text("Node: $nodeId  |  ${archives.size} archive${if (archives.size == 1) "" else "s"} found",
                        color = Color(0xFF8B938A), fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(12.dp))

            // Connection status
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp),
                color = if (isConnected) Color(0xFF0D2010) else Color(0xFF2A1A1A)) {
                Text(
                    if (isConnected) "\u25cf RADIO CONNECTED" else "\u25cb RADIO NOT CONNECTED — connect before restoring",
                    color = if (isConnected) Color(0xFF97D5A5) else Color(0xFFFFB4AB),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
            }
            Spacer(Modifier.height(12.dp))

            when (phase) {
                "SELECT", "CONFIRM" -> {
                    if (archives.isEmpty()) {
                        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1C211C)) {
                            Text("No archives found for node $nodeId.\nArchives are created automatically before each config apply.",
                                color = Color(0xFF8B938A), fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(16.dp))
                        }
                    } else {
                        Text("SELECT ARCHIVE TO RESTORE", color = Color(0xFF4A6080),
                            fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp)
                        Spacer(Modifier.height(6.dp))

                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(archives) { file ->
                                val isSelected = selectedFile == file
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                        .clickable { selectedFile = file; phase = "CONFIRM" },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) Color(0xFF1F4E79) else Color(0xFF1C211C)
                                ) {
                                    Row(modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(formatFileName(file),
                                                color = if (isSelected) Color.White else Color(0xFF97D5A5),
                                                fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold)
                                            Text(file.name,
                                                color = Color(0xFF4A6080), fontSize = 8.sp,
                                                fontFamily = FontFamily.Monospace)
                                        }
                                        Text(formatFileSize(file),
                                            color = Color(0xFF4A6080), fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace)
                                        if (isSelected) {
                                            Spacer(Modifier.width(8.dp))
                                            Text("\u2713", color = Color(0xFF4DA6FF),
                                                fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Confirm restore button
                        if (phase == "CONFIRM" && selectedFile != null) {
                            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF2A1A08)) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("\u26a0 RESTORE WILL OVERWRITE CURRENT RADIO CONFIG",
                                        color = Color(0xFFFFB74D), fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(4.dp))
                                    Text("Selected: ${formatFileName(selectedFile!!)}",
                                        color = Color(0xFFFFB74D), fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace)
                                    Text("Radio will reboot after restore.",
                                        color = Color(0xFF8B938A), fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                            Spacer(Modifier.height(8.dp))

                            Surface(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(enabled = isConnected) { restoreArchive(selectedFile!!) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isConnected) Color(0xFF7B2D00) else Color(0xFF1C211C)
                            ) {
                                Text(
                                    if (isConnected) "\u21ba RESTORE THIS ARCHIVE" else "CONNECT RADIO TO RESTORE",
                                    color = if (isConnected) Color(0xFFFFB74D) else Color(0xFF8B938A),
                                    fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 16.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        // Cancel button
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onBack() },
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF2A1A1A)
                        ) {
                            Text("CANCEL", color = Color(0xFFFFB4AB), fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 16.dp))
                        }
                    }
                }

                "RESTORING", "WAITING" -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("\u25cc", color = Color(0xFFFFB74D), fontSize = 48.sp)
                        Spacer(Modifier.height(16.dp))
                        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF1A1A0D)) {
                            Text(statusMsg, color = Color(0xFFFFB74D), fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                        }
                        if (countdown > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text("$countdown", color = Color(0xFF8B938A), fontSize = 36.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        if (logLines.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF0D1A0D)) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    logLines.takeLast(6).forEach { line ->
                                        Text(line, color = Color(0xFF97D5A5), fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }
                }

                "DONE" -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("\u25cf", color = Color(0xFF97D5A5), fontSize = 48.sp)
                        Spacer(Modifier.height(16.dp))
                        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF0D2010)) {
                            Text(statusMsg, color = Color(0xFF97D5A5), fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onDone() },
                            shape = RoundedCornerShape(12.dp), color = Color(0xFF1A6B2E)
                        ) {
                            Text("\u2713 DONE", color = Color.White, fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 16.dp))
                        }
                    }
                }

                "FAILED" -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("\u2717", color = Color(0xFFFFB4AB), fontSize = 48.sp)
                        Spacer(Modifier.height(16.dp))
                        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF2A1A1A)) {
                            Text(statusMsg, color = Color(0xFFFFB4AB), fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { phase = "SELECT" },
                            shape = RoundedCornerShape(12.dp), color = Color(0xFF1C211C)
                        ) {
                            Text("TRY AGAIN", color = Color(0xFF8B938A), fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 16.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onBack() },
                            shape = RoundedCornerShape(12.dp), color = Color(0xFF2A1A1A)
                        ) {
                            Text("CANCEL", color = Color(0xFFFFB4AB), fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 16.dp))
                        }
                    }
                }
            }
        }
    }
}
