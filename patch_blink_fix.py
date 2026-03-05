path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyMarkerRenderer.kt"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

old = """        blinkRunnable = object : Runnable {
            override fun run() {
                blinkState = !blinkState
                val map = mapView ?: return
                var hasMore = false
                for (marker in nodeMarkers) {
                    // Find matching node by title (callsign)
                    val node = currentNodes.firstOrNull { it.callsign == marker.title }
                    when (node?.status) {
                        ConvoyStatus.LOST -> {
                            marker.alpha = if (blinkState) 1.0f else 0.2f
                            hasMore = true
                            blinkHandler.postDelayed(this, 900) // slow blink
                        }
                        ConvoyStatus.SIGNAL_DROP -> {
                            marker.alpha = if (blinkState) 1.0f else 0.25f
                            hasMore = true
                            blinkHandler.postDelayed(this, 280) // fast blink
                        }
                        else -> marker.alpha = 1.0f
                    }
                }
                if (hasMore) map.invalidate()
                else stopBlinkLoop()
            }
        }
        blinkHandler.post(blinkRunnable!!)"""

new = """        blinkRunnable = object : Runnable {
            override fun run() {
                blinkState = !blinkState
                val map = mapView ?: return
                var hasLost = false
                var hasDrop = false
                for (marker in nodeMarkers) {
                    val node = currentNodes.firstOrNull { it.callsign == marker.title }
                    when (node?.status) {
                        ConvoyStatus.LOST -> {
                            marker.alpha = if (blinkState) 1.0f else 0.2f
                            hasLost = true
                        }
                        ConvoyStatus.SIGNAL_DROP -> {
                            marker.alpha = if (blinkState) 1.0f else 0.25f
                            hasDrop = true
                        }
                        else -> marker.alpha = 1.0f
                    }
                }
                if (hasLost || hasDrop) {
                    map.invalidate()
                    // Use fastest interval needed — drop=280ms, lost=900ms
                    val delay = if (hasDrop) 280L else 900L
                    blinkHandler.postDelayed(this, delay)
                } else {
                    stopBlinkLoop()
                }
            }
        }
        blinkHandler.post(blinkRunnable!!)"""

if old in content:
    content = content.replace(old, new)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("Fixed blink loop")
else:
    print("ERROR: not found")
