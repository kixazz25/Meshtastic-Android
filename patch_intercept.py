lines = open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', encoding='utf-8').readlines()
insert = '''                            override fun shouldInterceptRequest(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                                if (url.startsWith("file:///data/user/0/") && url.contains("/files/tiles/")) {
                                    val path = url.removePrefix("file://")
                                    val file = java.io.File(path)
                                    if (file.exists()) return android.webkit.WebResourceResponse("image/png", "utf-8", file.inputStream())
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
'''
lines.insert(400, insert)
open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt', 'w', encoding='utf-8').writelines(lines)
print('Done')
