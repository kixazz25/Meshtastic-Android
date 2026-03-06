path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyScreen.kt"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

old = "    DisposableEffect(lifecycle) {"
new = """    // Set mapInitialized after 3s so GPS center settles before auto-zoom kicks in
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3000)
        mapInitialized = true
    }

    DisposableEffect(lifecycle) {"""

if old in content:
    content = content.replace(old, new, 1)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("Added LaunchedEffect for mapInitialized")
else:
    print("ERROR: not found")
