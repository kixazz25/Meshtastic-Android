path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Add RecordingState enum after imports (before ConvoyScreen composable)
old_enum = "// ── CONVOY SCREEN ────────────────────────────────────────────────────────────"
new_enum = """enum class RecordingState { IDLE, RECORDING, PAUSED }

// ── CONVOY SCREEN ────────────────────────────────────────────────────────────"""

if old_enum in content:
    content = content.replace(old_enum, new_enum)
    print("Added RecordingState enum")
else:
    print("ERROR: enum marker not found")

# 2. Add recordingState to ConvoyScreen state vars
old_state = "    var showLeadTrack by remember { mutableStateOf(true) }"
new_state = """    var showLeadTrack by remember { mutableStateOf(true) }
    var recordingState by remember { mutableStateOf(RecordingState.IDLE) }
    var showRecMenu by remember { mutableStateOf(false) }"""

if old_state in content:
    content = content.replace(old_state, new_state)
    print("Added recordingState vars")
else:
    print("ERROR: state var marker not found")

# 3. Replace tiny REC button with large upper-left button + overlay menu
old_rec = """            // Task 5.4 — Route Recorder button (REQ-111) — delegates to ViewModel
            TextButton(
                onClick = { viewModel.toggleRouteRecorder() },
                modifier = Modifier.padding(0.dp)
            ) {
                Text(
                    text = "REC",
                    color = Color(0xFF4A6080),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }"""

new_rec = """            // REC button placeholder — large button added as map overlay below"""

if old_rec in content:
    content = content.replace(old_rec, new_rec)
    print("Removed old REC stub")
else:
    print("ERROR: old REC not found")

# 4. Add large REC button overlay in upper left of map Box
old_box = "        // ── CONTACT LOST banner ───────────────────────────────────────────"
new_box = """        // ── REC button — upper left ──────────────────────────────────────────
        Box(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
            if (!showRecMenu) {
                // Main REC button
                Surface(
                    modifier = Modifier.clickable {
                        when (recordingState) {
                            RecordingState.IDLE -> { recordingState = RecordingState.RECORDING; viewModel.toggleRouteRecorder() }
                            RecordingState.RECORDING, RecordingState.PAUSED -> showRecMenu = true
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = when (recordingState) {
                        RecordingState.IDLE -> Color(0xFF8B0000)
                        RecordingState.RECORDING -> Color(0xFFCC0000)
                        RecordingState.PAUSED -> Color(0xFF994400)
                    },
                    shadowElevation = 6.dp
                ) {
                    Text(
                        text = when (recordingState) {
                            RecordingState.IDLE -> "⏺  REC"
                            RecordingState.RECORDING -> "⏸  PAUSE"
                            RecordingState.PAUSED -> "⏺  RESUME"
                        },
                        color = Color.White,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            } else {
                // Expanded menu: RESUME and END
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        modifier = Modifier.clickable {
                            recordingState = RecordingState.RECORDING
                            showRecMenu = false
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF994400),
                        shadowElevation = 6.dp
                    ) {
                        Text("⏸  PAUSE", color = Color.White, fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                    Surface(
                        modifier = Modifier.clickable {
                            recordingState = RecordingState.IDLE
                            showRecMenu = false
                            viewModel.toggleRouteRecorder()
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF4A0000),
                        shadowElevation = 6.dp
                    ) {
                        Text("⏹  END", color = Color.White, fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
            }
        }

        // ── CONTACT LOST banner ───────────────────────────────────────────"""

if old_box in content:
    content = content.replace(old_box, new_box)
    print("Added large REC button overlay")
else:
    print("ERROR: box marker not found")

with open(path, "w", encoding="utf-8") as f:
    f.write(content)
