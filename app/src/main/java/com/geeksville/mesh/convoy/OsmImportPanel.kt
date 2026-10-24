package com.geeksville.mesh.convoy

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OsmImportPanel -- the container the four components drop into.
 *
 * Four rows, one green. Row state is DERIVED FROM DISK every refresh; nothing
 * here stores a stage. The ledger is written by the components and read only
 * for display -- it never decides what runs next.
 *
 * THE ONE HARD RULE: EXTRACT cannot launch unless a zip has been identified as
 * existing. Tapping it earlier is harmless and simply reports that nothing was
 * found.
 *
 * C2 / C3 / C4 are not built. Their actions call stubs so the panel and its
 * derivation can be exercised by moving files around before any component
 * exists -- push a dummy zip and EXTRACT goes green; delete it and DOWNLOAD
 * goes green again.
 */

// DEADCODE2-2026-10-23: PANEL_TAG (unused) moved to retired/2.7c_dead/pieces/convoy/OsmImportPanel.pieces.txt

// DEADCODE2-2026-10-23: GEOFABRIK_URL (unused) moved to retired/2.7c_dead/pieces/convoy/OsmImportPanel.pieces.txt

private val GREEN = Color(0xFF3FB950)
private val DIM = Color(0xFF8B949E)

// DEADCODE2-2026-10-23: RowSpec (unused) moved to retired/2.7c_dead/pieces/convoy/OsmImportPanel.pieces.txt

// DEADCODE2-2026-10-23: Row3Choice (unused) moved to retired/2.7c_dead/pieces/convoy/OsmImportPanel.pieces.txt

// DEADCODE2-2026-10-23: OsmImportPanel (unused) moved to retired/2.7c_dead/pieces/convoy/OsmImportPanel.pieces.txt

// DEADCODE2-2026-10-23: ExtractProgressList (unused) moved to retired/2.7c_dead/pieces/convoy/OsmImportPanel.pieces.txt

/** 134242 -> "134,242". Long counts are unreadable without separators. */
private fun grouped(v: Long): String =
    String.format(java.util.Locale.US, "%,d", v)

// DEADCODE2-2026-10-23: StageRow (unused) moved to retired/2.7c_dead/pieces/convoy/OsmImportPanel.pieces.txt

// DEADCODE2-2026-10-23: openGeofabrik (unused) moved to retired/2.7c_dead/pieces/convoy/OsmImportPanel.pieces.txt

// DEADCODE2-2026-10-23: runStub (unused) moved to retired/2.7c_dead/pieces/convoy/OsmImportPanel.pieces.txt
