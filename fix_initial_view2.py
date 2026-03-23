content = open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', 'r', encoding='utf-8').read()

old = '''    val myNodeInfo by viewModel.myNodeInfo.collectAsStateWithLifecycle()
    LaunchedEffect(hudMode, selectedNode, mapReady) {
        val wv = webViewRef.value ?: return@LaunchedEffect
        val nodes = convoyState.nodes
        when (hudMode) {
            HudMode.MY_CART -> {
                val myCart = nodes.firstOrNull { it.isMyCart }
                if (myCart != null) {
                    wv.evaluateJavascript("setView(${myCart.latitude}, ${myCart.longitude}, ${ConvoyConfig.MAP_CART_ZOOM})", null)
                } else {
                    // No convoy node yet -- use device GPS if available
                    val pos = myNodeInfo?.position
                    if (pos != null && pos.latitude != 0.0) {
                        wv.evaluateJavascript("setView(${pos.latitude}, ${pos.longitude}, ${ConvoyConfig.MAP_CART_ZOOM})", null)
                    }
                }
            }
            HudMode.NODE -> {
                selectedNode?.let {
                    wv.evaluateJavascript("setView(${it.latitude}, ${it.longitude}, ${ConvoyConfig.MAP_CART_ZOOM})", null)
                }
            }
            else -> {
                // GROUP / COLLAPSED -- fit all nodes with valid GPS only
                val validNodes = nodes.filter { it.latitude != 0.0 && it.longitude != 0.0 }
                if (validNodes.isNotEmpty()) {
                    val lats = validNodes.joinToString(",") { it.latitude.toString() }
                    val lons = validNodes.joinToString(",") { it.longitude.toString() }
                    wv.evaluateJavascript("fitBounds([$lats], [$lons])", null)
                } else {
                    // No convoy nodes yet -- use device GPS if available
                    val pos = myNodeInfo?.position
                    if (pos != null && pos.latitude != 0.0) {
                        wv.evaluateJavascript("setView(${pos.latitude}, ${pos.longitude}, 14)", null)
                    }
                }
            }
        }
    }'''

new = '''    LaunchedEffect(hudMode, selectedNode, mapReady) {
        val wv = webViewRef.value ?: return@LaunchedEffect
        val nodes = convoyState.nodes
        when (hudMode) {
            HudMode.MY_CART -> {
                val myCart = nodes.firstOrNull { it.isMyCart }
                if (myCart != null) {
                    wv.evaluateJavascript("setView(${myCart.latitude}, ${myCart.longitude}, ${ConvoyConfig.MAP_CART_ZOOM})", null)
                } else {
                    wv.evaluateJavascript("initDeviceLocation()", null)
                }
            }
            HudMode.NODE -> {
                selectedNode?.let {
                    wv.evaluateJavascript("setView(${it.latitude}, ${it.longitude}, ${ConvoyConfig.MAP_CART_ZOOM})", null)
                }
            }
            else -> {
                val validNodes = nodes.filter { it.latitude != 0.0 && it.longitude != 0.0 }
                if (validNodes.isNotEmpty()) {
                    val lats = validNodes.joinToString(",") { it.latitude.toString() }
                    val lons = validNodes.joinToString(",") { it.longitude.toString() }
                    wv.evaluateJavascript("fitBounds([$lats], [$lons])", null)
                } else {
                    wv.evaluateJavascript("initDeviceLocation()", null)
                }
            }
        }
    }'''

print('Found:', old in content)
result = content.replace(old, new)
open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', 'w', encoding='utf-8').write(result)
print('Done')
