kt_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
kt = open(kt_path, encoding='utf-8').read()

old = '''                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                val tilesDir = java.io.File(ctx.filesDir, "tiles/SAT/10")
                                val bounds = mutableListOf<String>()
                                if (tilesDir.exists()) {
                                    tilesDir.listFiles()?.forEach { xDir ->
                                        val x = xDir.name.toLongOrNull() ?: return@forEach
                                        xDir.listFiles()?.forEach { yFile ->
                                            val y = yFile.name.removeSuffix(".png").toLongOrNull() ?: return@forEach
                                            val z = 10
                                            val n = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / (1 shl z)))))
                                            val s = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (y + 1) / (1 shl z)))))
                                            val w = x.toDouble() / (1 shl z) * 360.0 - 180.0
                                            val e = (x + 1).toDouble() / (1 shl z) * 360.0 - 180.0
                                            bounds.add("{\"n\":$n,\"s\":$s,\"e\":$e,\"w\":$w}")
                                        }
                                    }
                                }
                                val json = "[${bounds.joinToString(",")}]"
                                webViewRef.value?.evaluateJavascript("showDownloadedAreas($json)", null)
                            },'''

new = '''                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                val tilesDir = java.io.File(context.filesDir, "tiles/SAT/10")
                                val bounds = mutableListOf<String>()
                                if (tilesDir.exists()) {
                                    tilesDir.listFiles()?.forEach { xDir: java.io.File ->
                                        val x = xDir.name.toLongOrNull() ?: return@forEach
                                        xDir.listFiles()?.forEach { yFile: java.io.File ->
                                            val y = yFile.name.removeSuffix(".png").toLongOrNull() ?: return@forEach
                                            val z = 10
                                            val tileN = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / (1 shl z)))))
                                            val tileS = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (y + 1) / (1 shl z)))))
                                            val tileW = x.toDouble() / (1 shl z) * 360.0 - 180.0
                                            val tileE = (x + 1).toDouble() / (1 shl z) * 360.0 - 180.0
                                            bounds.add("{\"n\":$tileN,\"s\":$tileS,\"e\":$tileE,\"w\":$tileW}")
                                        }
                                    }
                                }
                                val json = "[${bounds.joinToString(",")}]"
                                webViewRef.value?.evaluateJavascript("showDownloadedAreas($json)", null)
                            },'''

if old in kt:
    kt = kt.replace(old, new)
    open(kt_path, 'w', encoding='utf-8').write(kt)
    print('Fixed OK')
else:
    print('ERROR: pattern not found')
