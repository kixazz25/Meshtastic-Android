content = open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', 'r', encoding='utf-8').read()

# Replace the initial fitBounds LaunchedEffect to use its own flag
old = '''    LaunchedEffect(convoyState) {
        val wv = webViewRef.value ?: return@LaunchedEffect
        val validNodes = convoyState.nodes.filter { it.latitude != 0.0 && it.longitude != 0.0 }
        if (validNodes.isNotEmpty() && !viewModel.hasSeenNodes.value) {
            val lats = validNodes.joinToString(",") { it.latitude.toString() }
            val lons = validNodes.joinToString(",") { it.longitude.toString() }
            wv.evaluateJavascript("fitBounds([$lats], [$lons])", null)
        }
    }'''

new = '''    val initialViewSet = remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(convoyState) {
        if (initialViewSet.value) return@LaunchedEffect
        val wv = webViewRef.value ?: return@LaunchedEffect
        val validNodes = convoyState.nodes.filter { it.latitude != 0.0 && it.longitude != 0.0 }
        if (validNodes.isNotEmpty()) {
            val lats = validNodes.joinToString(",") { it.latitude.toString() }
            val lons = validNodes.joinToString(",") { it.longitude.toString() }
            wv.evaluateJavascript("fitBounds([$lats], [$lons])", null)
            initialViewSet.value = true
        }
    }'''

print('Found:', old in content)
result = content.replace(old, new)
open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', 'w', encoding='utf-8').write(result)
print('Done')
