# Wire ConvoyConfig values into ConvoyScreen and ConvoyMarkerRenderer

# ── ConvoyScreen: use config zoom values ──────────────────────────────────────
screen_path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyScreen.kt"
with open(screen_path, "r", encoding="utf-8") as f:
    sc = f.read()

# Default map zoom
sc = sc.replace(
    "            controller.setZoom(13.0)",
    "            controller.setZoom(ConvoyConfig.MAP_DEFAULT_ZOOM)"
)

# MY CART and NODE zoom
sc = sc.replace(
    "                        mv.controller.setZoom(16.0)\n                            }\n                        }\n                        HudMode.NODE -> {\n                            // Zoom to selected node\n                            selectedNode?.let {\n                                mv.controller.animateTo(GeoPoint(it.latitude, it.longitude))\n                                mv.controller.setZoom(16.0)",
    "                        mv.controller.setZoom(ConvoyConfig.MAP_CART_ZOOM)\n                            }\n                        }\n                        HudMode.NODE -> {\n                            // Zoom to selected node\n                            selectedNode?.let {\n                                mv.controller.animateTo(GeoPoint(it.latitude, it.longitude))\n                                mv.controller.setZoom(ConvoyConfig.MAP_CART_ZOOM)"
)

# Group zoom padding
sc = sc.replace(
    "                                mv.zoomToBoundingBox(box.increaseByScale(1.4f), true)",
    "                                mv.zoomToBoundingBox(box.increaseByScale(ConvoyConfig.MAP_GROUP_ZOOM_PADDING), true)"
)

with open(screen_path, "w", encoding="utf-8") as f:
    f.write(sc)
print("Patched ConvoyScreen with ConvoyConfig")

# ── ConvoyMarkerRenderer: use config blink rates and marker sizes ─────────────
renderer_path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyMarkerRenderer.kt"
with open(renderer_path, "r", encoding="utf-8") as f:
    rr = f.read()

rr = rr.replace(
    "                    val delay = if (hasDrop) 150L else 3000L",
    "                    val delay = if (hasDrop) ConvoyConfig.BLINK_DROP_MS else ConvoyConfig.BLINK_LOST_MS"
)

# Marker sizes
rr = rr.replace(
    '            "large" -> (48 * density).toInt()',
    '            "large" -> (ConvoyConfig.MARKER_SIZE_LARGE_DP * density).toInt()'
)
rr = rr.replace(
    '            else    -> (36 * density).toInt()',
    '            else    -> (ConvoyConfig.MARKER_SIZE_MEDIUM_DP * density).toInt()'
)

with open(renderer_path, "w", encoding="utf-8") as f:
    f.write(rr)
print("Patched ConvoyMarkerRenderer with ConvoyConfig")
