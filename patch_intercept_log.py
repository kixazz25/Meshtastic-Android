content = open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', encoding='utf-8').read()

old = '''                            override fun shouldInterceptRequest(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                                if (url.startsWith("file:///data/user/0/") && url.contains("/files/tiles/")) {
                                    val path = url.removePrefix("file://")
                                    val file = java.io.File(path)
                                    if (file.exists()) return android.webkit.WebResourceResponse("image/png", "utf-8", file.inputStream())
                                }
                                return super.shouldInterceptRequest(view, request)
                            }'''

new = '''                            override fun shouldInterceptRequest(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
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

if old in content:
    content = content.replace(old, new)
    open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', 'w', encoding='utf-8').write(content)
    print('Done')
else:
    print('ERROR: pattern not found')
