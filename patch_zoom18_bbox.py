kt_path = 'app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt'
kt = open(kt_path, encoding='utf-8').read()

old = '''                                val tilesDir = java.io.File(context.filesDir, "tiles/SAT/10")
                                val bounds = mutableListOf<String>()
                                if (tilesDir.exists()) {
                                    tilesDir.listFiles()?.forEach { xDir ->
                                        val x = xDir.name.toLongOrNull() ?: return@forEach
                                        xDir.listFiles()?.forEach { yFile ->
                                            val y = yFile.name.removeSuffix(".png").toLongOrNull() ?: return@forEach
                                            val z = 10
                                            val tileN = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / (1 shl z)))))
                                            val tileS = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (y + 1) / (1 shl z)))))
                                            val tileW = x.toDouble() / (1 shl z) * 360.0 - 180.0
                                            val tileE = (x + 1).toDouble() / (1 shl z) * 360.0 - 180.0
                                            bounds.add("{\\\"n\\\":$tileN,\\\"s\\\":$tileS,\\\"e\\\":$tileE,\\\"w\\\":$tileW}")
                                        }
                                    }
                                }
                                val json = "[${bounds.joinToString(",")}]"'''

new = '''                                // Scan zoom 18 tiles and cluster into contiguous bounding boxes
                                val tilesDir = java.io.File(ctx.filesDir, "tiles/SAT/18")
                                val bounds = mutableListOf<String>()
                                if (tilesDir.exists()) {
                                    val z = 18
                                    var minX = Long.MAX_VALUE; var maxX = Long.MIN_VALUE
                                    var minY = Long.MAX_VALUE; var maxY = Long.MIN_VALUE
                                    tilesDir.listFiles()?.forEach { xDir ->
                                        val x = xDir.name.toLongOrNull() ?: return@forEach
                                        xDir.listFiles()?.forEach { yFile ->
                                            val y = yFile.name.removeSuffix(".png").toLongOrNull() ?: return@forEach
                                            if (x < minX) minX = x; if (x > maxX) maxX = x
                                            if (y < minY) minY = y; if (y > maxY) maxY = y
                                        }
                                    }
                                    if (minX != Long.MAX_VALUE) {
                                        val tileN = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * minY / (1 shl z)))))
                                        val tileS = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (maxY + 1) / (1 shl z)))))
                                        val tileW = minX.toDouble() / (1 shl z) * 360.0 - 180.0
                                        val tileE = (maxX + 1).toDouble() / (1 shl z) * 360.0 - 180.0
                                        bounds.add("{\\\"n\\\":$tileN,\\\"s\\\":$tileS,\\\"e\\\":$tileE,\\\"w\\\":$tileW}")
                                    }
                                }
                                val json = "[${bounds.joinToString(",")}]"'''

if old in kt:
    kt = kt.replace(old, new)
    open(kt_path, 'w', encoding='utf-8').write(kt)
    print('Fixed OK')
else:
    print('ERROR: pattern not found')
