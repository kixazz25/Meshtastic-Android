import re

# --- Patch 1: ConvoyConfig.kt ---
config_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyConfig.kt'
config = open(config_path, encoding='utf-8').read()

old_base = 'const val LOCAL_TILE_BASE = "file:///data/user/0/com.geeksville.mesh.google.debug/files/tiles/"'
new_base = 'const val LOCAL_TILE_BASE = "convoy://tiles/"'

if old_base in config:
    config = config.replace(old_base, new_base)
    open(config_path, 'w', encoding='utf-8').write(config)
    print('ConvoyConfig.kt patched OK')
else:
    print('ERROR: LOCAL_TILE_BASE pattern not found in ConvoyConfig.kt')

# --- Patch 2: ConvoyScreen.kt ---
screen_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
screen = open(screen_path, encoding='utf-8').read()

old_intercept = '''                            override fun shouldInterceptRequest(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                                android.util.Log.d("ConvoyIntercept", "INTERCEPT: $url")
                                if (url.startsWith("file:///data/user/0/") && url.contains("/files/tiles/")) {
                                    val path = url.removePrefix("file://")
                                    val file = java.io.File(path)
                                    android.util.Log.d("ConvoyIntercept", "FILE exists=${file.exists()} path=$path")
                                    if (file.exists()) return android.webkit.WebResourceResponse("image/png", "utf-8", file.inputStream())
                                }
                                return super.shouldInterceptRequest(view, request)
                            }'''

new_intercept = '''                            override fun shouldInterceptRequest(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                                if (url.startsWith("convoy://tiles/")) {
                                    val tilePath = url.removePrefix("convoy://tiles/")
                                    val file = java.io.File(ctx.filesDir, "tiles/$tilePath")
                                    android.util.Log.d("ConvoyIntercept", "TILE exists=${file.exists()} path=${file.absolutePath}")
                                    if (file.exists()) return android.webkit.WebResourceResponse("image/png", "utf-8", file.inputStream())
                                }
                                return super.shouldInterceptRequest(view, request)
                            }'''

if old_intercept in screen:
    screen = screen.replace(old_intercept, new_intercept)
    open(screen_path, 'w', encoding='utf-8').write(screen)
    print('ConvoyScreen.kt patched OK')
else:
    print('ERROR: interceptor pattern not found in ConvoyScreen.kt')
