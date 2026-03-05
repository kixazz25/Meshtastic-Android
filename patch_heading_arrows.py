# Fix 1: ConvoyScreen - replace bullseye with arrowhead for MyLocationOverlay
# Fix 2: ConvoyMarkerRenderer - rotate LEAD/TAIL markers by heading_deg

screen_path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyScreen.kt"
renderer_path = r"C:\Users\kixaz\Meshtastic-Android\app\src\main\java\com\geeksville\mesh\convoy\ConvoyMarkerRenderer.kt"

# ── Fix 1: Arrowhead for position icon ───────────────────────────────────────
with open(screen_path, "r", encoding="utf-8") as f:
    sc = f.read()

old1 = """            // Draw a bullseye icon for current location
            val sizePx = (24 * context.resources.displayMetrics.density).toInt()
            val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val cx = sizePx / 2f
            val r = sizePx / 2f
            // Outer ring
            paint.color = android.graphics.Color.argb(180, 33, 150, 243)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = sizePx * 0.12f
            canvas.drawCircle(cx, cx, r * 0.85f, paint)
            // Inner dot
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = android.graphics.Color.argb(220, 33, 150, 243)
            canvas.drawCircle(cx, cx, r * 0.35f, paint)
            setPersonIcon(bmp)
            setPersonAnchor(0.5f, 0.5f)"""

new1 = """            // Draw a small arrowhead pointing up (OSMDroid rotates it with GPS heading)
            val sizePx = (20 * context.resources.displayMetrics.density).toInt()
            val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(230, 33, 150, 243)
                style = android.graphics.Paint.Style.FILL
            }
            val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = sizePx * 0.1f
            }
            val cx = sizePx / 2f
            val path = android.graphics.Path().apply {
                moveTo(cx, 0f)                          // tip (north)
                lineTo(sizePx * 0.8f, sizePx * 0.9f)   // bottom right
                lineTo(cx, sizePx * 0.65f)              // inner notch
                lineTo(sizePx * 0.2f, sizePx * 0.9f)   // bottom left
                close()
            }
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
            setPersonIcon(bmp)
            setPersonAnchor(0.5f, 0.5f)
            enableAutoRotate()"""

if old1 in sc:
    sc = sc.replace(old1, new1)
    print("Fixed position arrowhead")
else:
    print("ERROR: position icon marker not found")

with open(screen_path, "w", encoding="utf-8") as f:
    f.write(sc)

# ── Fix 2: Rotate LEAD/TAIL markers by heading ───────────────────────────────
with open(renderer_path, "r", encoding="utf-8") as f:
    rr = f.read()

old2 = """            val marker = Marker(map).apply {
                position = GeoPoint(node.latitude, node.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = node.callsign
                snippet = buildSnippet(node)
                icon = buildMarkerDrawable(context, node.markerColor, node.markerSymbol, node.markerSize)
                setOnMarkerClickListener { _, _ ->
                    onNodeTapped(markerNode)
                    true // consume event, suppress default info window
                }
            }"""

new2 = """            val marker = Marker(map).apply {
                position = GeoPoint(node.latitude, node.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = node.callsign
                snippet = buildSnippet(node)
                icon = buildMarkerDrawable(context, node.markerColor, node.markerSymbol, node.markerSize)
                // Rotate LEAD and TAIL markers to show heading direction
                if (node.isLead || node.isTail || node.isMyCart) {
                    rotation = -node.heading_deg  // OSMDroid rotation is counter-clockwise
                }
                setOnMarkerClickListener { _, _ ->
                    onNodeTapped(markerNode)
                    true // consume event, suppress default info window
                }
            }"""

if old2 in rr:
    rr = rr.replace(old2, new2)
    print("Fixed LEAD/TAIL/MYCART rotation")
else:
    print("ERROR: marker rotation not found")

with open(renderer_path, "w", encoding="utf-8") as f:
    f.write(rr)
