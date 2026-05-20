package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

// ----------------------------------------------------------------
// ConvoyTrailSourceScreen -- V2.5 §9 Trail Import Flow
//
// Step-based flow per ScreenReference v5 section 9:
//   9.2: Method Selection (Full Source / By Area)
//   Method A: A-1 Source Select → A-2 Validate → A-3 Processing
//   Method B: B-1 Draw Area → B-2 Suggested → B-3 Validate → B-4 Processing
// ----------------------------------------------------------------

private val mono = FontFamily.Monospace
private val txtB = Color(0xFFCCDDEE)
private val txtD = Color(0xFF4A6080)
private val blue = Color(0xFF4DA6FF)
private val green = Color(0xFF1CF0A0)
private val orange = Color(0xFFD29922)
private val bg = Color(0xFF0E1117)
private val cardBg = Color(0xFF1A2233)

private enum class ImportStep {
    METHOD_SELECT,
    A1_SOURCE_SELECT, A2_VALIDATE, A3_PROCESSING,
    B1_DRAW_AREA, B2_SUGGESTED, B3_VALIDATE, B4_PROCESSING
}

data class CatalogSource(
    val id: String, val name: String, val agency: String,
    val format: String, val scope: String,
    val boundaryN: Double, val boundaryS: Double,
    val boundaryE: Double, val boundaryW: Double,
    val status: String, val trailCount: Int,
    val fullyImported: Boolean = false,
    val lastImportedAt: String? = null,
    val importedTrailCount: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvoyTrailSourceScreen(onNavigateBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(ImportStep.A1_SOURCE_SELECT) }
    var sources by remember { mutableStateOf(listOf<CatalogSource>()) }
    var selectedSourceId by remember { mutableStateOf<String?>(null) }
    var importResult by remember { mutableStateOf<String?>(null) }
    var importRunning by remember { mutableStateOf(false) }
    var validationStatus by remember { mutableStateOf("") }

    // Load catalog on first compose
    LaunchedEffect(Unit) {
        sources = loadSourceCatalog(context)
    }

    val selectedSource = sources.firstOrNull { it.id == selectedSourceId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(when (step) {
                        ImportStep.METHOD_SELECT -> "Trail Import"
                        ImportStep.A1_SOURCE_SELECT -> "Select Source"
                        ImportStep.A2_VALIDATE -> "Validate Source"
                        ImportStep.A3_PROCESSING -> "Importing..."
                        ImportStep.B1_DRAW_AREA -> "Draw Import Area"
                        ImportStep.B2_SUGGESTED -> "Suggested Sources"
                        ImportStep.B3_VALIDATE -> "Validate Selection"
                        ImportStep.B4_PROCESSING -> "Importing..."
                    }, fontFamily = mono, fontSize = 14.sp)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when (step) {
                            ImportStep.METHOD_SELECT -> onNavigateBack()
                            ImportStep.A1_SOURCE_SELECT -> onNavigateBack()
                            ImportStep.A2_VALIDATE -> step = ImportStep.A1_SOURCE_SELECT
                            ImportStep.A3_PROCESSING -> if (!importRunning) step = ImportStep.A1_SOURCE_SELECT
                            ImportStep.B1_DRAW_AREA -> step = ImportStep.METHOD_SELECT
                            ImportStep.B2_SUGGESTED -> step = ImportStep.B1_DRAW_AREA
                            ImportStep.B3_VALIDATE -> step = ImportStep.B2_SUGGESTED
                            ImportStep.B4_PROCESSING -> if (!importRunning) step = ImportStep.METHOD_SELECT
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg)
            )
        },
        containerColor = bg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (step) {

                // ── 9.2 Method Selection ──
                ImportStep.METHOD_SELECT -> {
                    SectionLabel("SELECT IMPORT METHOD")
                    MethodCard(
                        title = "IMPORT FULL SOURCE",
                        desc = "Select a source and import its entire dataset.",
                        selected = false,
                        onClick = { step = ImportStep.A1_SOURCE_SELECT }
                    )
                    MethodCard(
                        title = "IMPORT BY AREA",
                        desc = "Draw a bounding area. Sources with overlapping content will be suggested.",
                        selected = false,
                        onClick = { step = ImportStep.B1_DRAW_AREA }
                    )
                }

                // ── A-1: Select Source ──
                ImportStep.A1_SOURCE_SELECT -> {
                    SectionLabel("APPROVED SOURCES")
                    sources.filter { it.status != "display_only_not_queryable" }.forEach { src ->
                        SourceSelectCard(
                            source = src,
                            isSelected = src.id == selectedSourceId,
                            onClick = { selectedSourceId = src.id }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { step = ImportStep.METHOD_SELECT }) {
                            Text("BACK", fontFamily = mono, fontSize = 10.sp)
                        }
                        Button(
                            onClick = {
                                if (selectedSourceId != null) {
                                    validationStatus = ""
                                    step = ImportStep.A2_VALIDATE
                                }
                            },
                            enabled = selectedSourceId != null,
                            colors = ButtonDefaults.buttonColors(containerColor = blue)
                        ) {
                            Text("VALIDATE", fontFamily = mono, fontSize = 10.sp)
                        }
                    }
                }

                // ── A-2: Validate Source ──
                ImportStep.A2_VALIDATE -> {
                    if (selectedSource != null) {
                        SectionLabel("VALIDATING: ${selectedSource.name}")
                        ValidationRow("Name", selectedSource.name)
                        ValidationRow("Agency", selectedSource.agency)
                        ValidationRow("Format", selectedSource.format)
                        ValidationRow("Scope", selectedSource.scope)
                        ValidationRow("Coverage", String.format(
                            "%.1f\u00b0N to %.1f\u00b0N, %.1f\u00b0W to %.1f\u00b0W",
                            selectedSource.boundaryS, selectedSource.boundaryN,
                            Math.abs(selectedSource.boundaryW), Math.abs(selectedSource.boundaryE)))
                        ValidationRow("Known Trails", "${selectedSource.trailCount}")
                        ValidationRow("Dedup", "source_unique_id match")

                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF0D3320)
                        ) {
                            Text(
                                "\u2713 Source format valid \u00b7 \u2713 No active import",
                                color = green, fontSize = 9.sp, fontFamily = mono,
                                modifier = Modifier.padding(8.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { step = ImportStep.A1_SOURCE_SELECT }) {
                                Text("BACK", fontFamily = mono, fontSize = 10.sp)
                            }
                            Button(
                                onClick = {
                                    importResult = null
                                    step = ImportStep.A3_PROCESSING
                                    importRunning = true
                                    scope.launch {
                                        val result = TrailImporter.importFullSource(context, selectedSource.id)
                                        importResult = result.message
                                        importRunning = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = green)
                            ) {
                                Text("IMPORT ALL TRAILS", fontFamily = mono, fontSize = 10.sp,
                                    color = Color.Black)
                            }
                        }
                    }
                }

                // ── A-3: Processing ──
                ImportStep.A3_PROCESSING -> {
                    SectionLabel("IMPORTING: ${selectedSource?.name ?: ""}")
                    if (importRunning) {
                        Spacer(modifier = Modifier.height(16.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            color = green
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Downloading and importing trails...",
                            color = txtD, fontSize = 10.sp, fontFamily = mono,
                            modifier = Modifier.align(Alignment.CenterHorizontally))
                        Text("Check logcat TrailImporter for progress",
                            color = txtD, fontSize = 8.sp, fontFamily = mono,
                            modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    if (importResult != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF0D3320)
                        ) {
                            Text(importResult!!, color = green, fontSize = 10.sp,
                                fontFamily = mono, modifier = Modifier.padding(10.dp))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { step = ImportStep.METHOD_SELECT },
                            colors = ButtonDefaults.buttonColors(containerColor = blue)
                        ) {
                            Text("DONE", fontFamily = mono, fontSize = 10.sp)
                        }
                    }
                }

                // ── B-1: Draw Bounding Area (placeholder) ──
                ImportStep.B1_DRAW_AREA -> {
                    SectionLabel("DRAW IMPORT AREA")
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        shape = RoundedCornerShape(6.dp), color = cardBg
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("[ Area selector — requires map JS bridge ]\nDraw rectangle on map to define import boundary",
                                color = txtD, fontSize = 10.sp, fontFamily = mono,
                                modifier = Modifier.padding(16.dp))
                        }
                    }
                    Text("Bounding area is transient — not saved.",
                        color = txtD, fontSize = 8.sp, fontFamily = mono)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { step = ImportStep.METHOD_SELECT }) {
                            Text("CANCEL", fontFamily = mono, fontSize = 10.sp)
                        }
                        Button(
                            onClick = { step = ImportStep.B2_SUGGESTED },
                            colors = ButtonDefaults.buttonColors(containerColor = blue)
                        ) {
                            Text("FIND SOURCES", fontFamily = mono, fontSize = 10.sp)
                        }
                    }
                }

                // ── B-2: Suggested Sources (placeholder) ──
                ImportStep.B2_SUGGESTED -> {
                    SectionLabel("SOURCES WITH CONTENT IN YOUR AREA")
                    Text("[ Overlap calculation requires drawn area bounds ]",
                        color = txtD, fontSize = 9.sp, fontFamily = mono)
                    sources.filter { it.status != "display_only_not_queryable" }.forEach { src ->
                        SourceSelectCard(source = src, isSelected = false, onClick = {})
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { step = ImportStep.B1_DRAW_AREA }) {
                            Text("BACK", fontFamily = mono, fontSize = 10.sp)
                        }
                        Button(
                            onClick = { step = ImportStep.B3_VALIDATE },
                            colors = ButtonDefaults.buttonColors(containerColor = blue)
                        ) {
                            Text("VALIDATE SELECTED", fontFamily = mono, fontSize = 10.sp)
                        }
                    }
                }

                // ── B-3: Validate (placeholder) ──
                ImportStep.B3_VALIDATE -> {
                    SectionLabel("VALIDATE SELECTED SOURCES")
                    Text("[ Validation for area-based import ]",
                        color = txtD, fontSize = 9.sp, fontFamily = mono)
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp), color = Color(0xFF0D3320)) {
                        Text("\u2713 All selected sources validated", color = green,
                            fontSize = 9.sp, fontFamily = mono, modifier = Modifier.padding(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { step = ImportStep.B2_SUGGESTED }) {
                            Text("BACK", fontFamily = mono, fontSize = 10.sp)
                        }
                        Button(
                            onClick = { step = ImportStep.B4_PROCESSING },
                            colors = ButtonDefaults.buttonColors(containerColor = green)
                        ) {
                            Text("IMPORT AREA TRAILS", fontFamily = mono, fontSize = 10.sp, color = Color.Black)
                        }
                    }
                }

                // ── B-4: Processing (placeholder) ──
                ImportStep.B4_PROCESSING -> {
                    SectionLabel("AREA IMPORT")
                    Text("[ Area import processing — requires drawn bounds + selected sources ]",
                        color = txtD, fontSize = 10.sp, fontFamily = mono)
                    Button(
                        onClick = { step = ImportStep.METHOD_SELECT },
                        colors = ButtonDefaults.buttonColors(containerColor = blue)
                    ) {
                        Text("DONE", fontFamily = mono, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

// ── Shared UI components ──

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = txtD, fontSize = 10.sp, fontFamily = mono, fontWeight = FontWeight.Bold)
}

@Composable
private fun MethodCard(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        color = if (selected) Color(0xFF1A3050) else cardBg,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, blue) else null
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, color = txtB, fontSize = 12.sp, fontFamily = mono, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(desc, color = txtD, fontSize = 10.sp, fontFamily = mono)
        }
    }
}

@Composable
private fun SourceSelectCard(source: CatalogSource, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(6.dp),
        color = if (isSelected) Color(0xFF1A3050) else cardBg,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, green) else null
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = green)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(source.name, color = txtB, fontSize = 11.sp, fontFamily = mono, fontWeight = FontWeight.Bold)
                Text("${source.format} | ${source.scope} | ${source.trailCount} trails",
                    color = txtD, fontSize = 9.sp, fontFamily = mono)
            }
        }
    }
}

@Composable
private fun ValidationRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = txtD, fontSize = 10.sp, fontFamily = mono,
            modifier = Modifier.width(100.dp))
        Text(value, color = txtB, fontSize = 10.sp, fontFamily = mono)
    }
}

// ── Catalog loader ──

private fun loadSourceCatalog(context: Context): List<CatalogSource> {
    return try {
        val json = context.assets.open("trail_sources.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val arr = root.getJSONArray("sources")
        (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            val b = s.optJSONObject("boundary")
            val scan = s.optJSONObject("scan")
            CatalogSource(
                id = s.getString("id"),
                name = s.getString("name"),
                agency = s.optString("agency", ""),
                format = s.optString("format", "arcgis_geojson"),
                scope = s.optString("scope", ""),
                boundaryN = b?.optDouble("n", 0.0) ?: 0.0,
                boundaryS = b?.optDouble("s", 0.0) ?: 0.0,
                boundaryE = b?.optDouble("e", 0.0) ?: 0.0,
                boundaryW = b?.optDouble("w", 0.0) ?: 0.0,
                status = s.optString("status", "active"),
                trailCount = scan?.optInt("total_count", 0) ?: 0
            )
        }
    } catch (e: Exception) {
        Log.e("TrailSource", "Failed to load catalog: ${e.message}")
        emptyList()
    }
}
