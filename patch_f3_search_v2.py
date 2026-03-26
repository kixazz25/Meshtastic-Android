import sys

screen_path = "app/src/main/java/com/geeksville/mesh/convoy/ConvoyScreen.kt"

with open(screen_path, "r", encoding="utf-8") as f:
    content = f.read()

old = """                        // ── Download Region button ───────────────────────
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                webViewRef.value?.evaluateJavascript("activateDrawMode()", null)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF2A3545)
                        ) {
                            Text("⬇  DOWNLOAD REGION", color = Color(0xFF7A8DA0), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp))
                        }"""

new = """                        // ── Search + Download Region ─────────────────────
                        androidx.compose.material3.OutlinedTextField(
                            value = locationSearchQuery,
                            onValueChange = {
                                locationSearchQuery = it
                                locationSearchError = ""
                                locationSearchResults = emptyList()
                            },
                            placeholder = { Text("City, park, or region...", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF4A6080)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.White),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF2E75B6),
                                unfocusedBorderColor = Color(0xFF2A3545),
                                cursorColor = Color(0xFF2E75B6)
                            ),
                            trailingIcon = {
                                if (locationSearchQuery.isNotBlank()) {
                                    androidx.compose.material3.IconButton(onClick = {
                                        locationSearchQuery = ""
                                        locationSearchResults = emptyList()
                                        locationSearchError = ""
                                    }) {
                                        Text("x", color = Color(0xFF4A6080), fontSize = 10.sp)
                                    }
                                }
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (locationSearchQuery.isBlank()) return@clickable
                                locationSearchError = ""
                                locationSearchResults = emptyList()
                                coroutineScope.launch {
                                    try {
                                        val geocoder = android.location.Geocoder(context)
                                        @Suppress("DEPRECATION")
                                        val results = geocoder.getFromLocationName(locationSearchQuery, 5)
                                        if (results.isNullOrEmpty()) {
                                            locationSearchError = "No results found"
                                        } else if (results.size == 1) {
                                            val addr = results[0]
                                            webViewRef.value?.evaluateJavascript("setView(${addr.latitude},${addr.longitude},10)", null)
                                            locationSearchQuery = listOfNotNull(addr.featureName, addr.locality, addr.adminArea).distinct().joinToString(", ")
                                        } else {
                                            locationSearchResults = results
                                        }
                                    } catch (e: Exception) {
                                        locationSearchError = "Search unavailable"
                                    }
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1A3A5C)
                        ) {
                            Text("FIND AREA", color = Color(0xFF2E75B6), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp))
                        }
                        if (locationSearchError.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Text(locationSearchError, color = Color(0xFFFF4444), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
                        }
                        if (locationSearchResults.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            locationSearchResults.forEach { addr ->
                                val label = listOfNotNull(addr.featureName, addr.locality, addr.adminArea).distinct().joinToString(", ")
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable {
                                        webViewRef.value?.evaluateJavascript("setView(${addr.latitude},${addr.longitude},10)", null)
                                        locationSearchQuery = label
                                        locationSearchResults = emptyList()
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF1E2A3A)
                                ) {
                                    Text(label, color = Color(0xFF7A8DA0), fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        // ── Download Region button ───────────────────────
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                locationSearchResults = emptyList()
                                webViewRef.value?.evaluateJavascript("activateDrawMode()", null)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF2A3545)
                        ) {
                            Text("⬇  DOWNLOAD REGION", color = Color(0xFF7A8DA0), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp))
                        }"""

if old in content:
    content = content.replace(old, new)
    with open(screen_path, "w", encoding="utf-8") as f:
        f.write(content)
    print("PATCHED OK")
else:
    print("ERROR: anchor not found")
    sys.exit(1)
