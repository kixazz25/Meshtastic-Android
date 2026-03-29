import sys

path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

errors = []

# Part 1: Add autoPan state var near other var declarations
old1 = '    var isOfflineMode by remember { mutableStateOf(false) }'
new1 = '    var isOfflineMode by remember { mutableStateOf(false) }\n    var autoPan by remember { mutableStateOf(true) }'

if old1 in content:
    content = content.replace(old1, new1)
    print("Part 1 OK — autoPan state added")
else:
    errors.append("Part 1: state anchor not found")

# Part 2: Gate the LaunchedEffect on autoPan AND convoyState
old2 = '    LaunchedEffect(hudMode, selectedNode, mapReady) {\n        val wv = webViewRef.value ?: return@LaunchedEffect\n        val nodes = convoyState.nodes\n        when (hudMode) {'
new2 = '    LaunchedEffect(hudMode, selectedNode, mapReady, convoyState, autoPan) {\n        if (!autoPan) return@LaunchedEffect\n        val wv = webViewRef.value ?: return@LaunchedEffect\n        val nodes = convoyState.nodes\n        when (hudMode) {'

if old2 in content:
    content = content.replace(old2, new2)
    print("Part 2 OK — LaunchedEffect gated on autoPan + convoyState")
else:
    errors.append("Part 2: LaunchedEffect anchor not found")

# Part 3: Set autoPan=true when mode button tapped (onModeChange)
old3 = '            onModeChange = { viewModel.setHudMode(it) },\n            modifier = Modifier.align(Alignment.BottomCenter)'
new3 = '            onModeChange = { viewModel.setHudMode(it); autoPan = true },\n            modifier = Modifier.align(Alignment.BottomCenter)'

if old3 in content:
    content = content.replace(old3, new3)
    print("Part 3 OK — autoPan=true on mode button tap")
else:
    errors.append("Part 3: onModeChange anchor not found")

# Part 4: Set autoPan=false when user touches the map
old4 = '''                view.setOnTouchListener { v, event ->
                    if (event.action == android.view.MotionEvent.ACTION_UP) {'''
new4 = '''                view.setOnTouchListener { v, event ->
                    if (event.action == android.view.MotionEvent.ACTION_MOVE) {
                        autoPan = false
                    }
                    if (event.action == android.view.MotionEvent.ACTION_UP) {'''

if old4 in content:
    content = content.replace(old4, new4)
    print("Part 4 OK — autoPan=false on map touch/pan")
else:
    errors.append("Part 4: touch listener anchor not found")

if errors:
    for e in errors: print("ERROR:", e)
    sys.exit(1)

with open(path, "w", encoding="utf-8") as f:
    f.write(content)

print("ALL PATCHED OK")
