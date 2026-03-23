content = open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', 'r', encoding='utf-8').read()

# Fix 1: Remove convoyState from zoom LaunchedEffect keys
old1 = '    LaunchedEffect(hudMode, convoyState, selectedNode) {'
new1 = '    LaunchedEffect(hudMode, selectedNode, mapReady) {'

# Fix 2: Add initial fitBounds LaunchedEffect before it
# Insert the new block before the hudMode LaunchedEffect
new_block = '''    LaunchedEffect(convoyState) {
        val wv = webViewRef.value ?: return@LaunchedEffect
        val validNodes = convoyState.nodes.filter { it.latitude != 0.0 && it.longitude != 0.0 }
        if (validNodes.isNotEmpty() && !viewModel.hasSeenNodes.value) {
            val lats = validNodes.joinToString(",") { it.latitude.toString() }
            val lons = validNodes.joinToString(",") { it.longitude.toString() }
            wv.evaluateJavascript("fitBounds([$lats], [$lons])", null)
        }
    }
    LaunchedEffect(hudMode, selectedNode, mapReady) {'''

print('Found fix1:', old1 in content)
result = content.replace(old1, new_block)
open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', 'w', encoding='utf-8').write(result)
print('Done')
