/*
 * CMPPIPE-2026-11-18 -- THE FIRST SHARED COMPOSABLE IN GROUPTRACK.
 *
 * Measured 11-18: app/ holds 314 @Composable across 101 files; grouptrack-shared held ZERO.
 * So the point of this file is NOT to be a screen. It is to answer one question that no
 * amount of reading the build files can answer: does Compose actually compose, lay out, and
 * RECOMPOSE on iPadOS, through our STATIC framework?
 *
 * It therefore deliberately depends on nothing of ours -- no database, no assets, no comm.
 * If this renders and the counter increments, the panel path is real and screens can start
 * coming over. If it does not, we learn that before migrating 101 files.
 *
 * CODE RULE 3: this is probe code with no "done" marker. It goes when the panels land.
 */
package com.grouptrack.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun GtIpadProbeScreen() {
    // Not GtScheme/Gt.<role> on purpose: STD1-2026-10-24 lives in app/ and is not shared yet.
    // Pulling it in here would make this probe fail for a reason that has nothing to do with CMP.
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            var taps by remember { mutableStateOf(0) }
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "CMPPIPE-2026-11-18",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Compose Multiplatform is running inside GroupTrackShared on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                // The tap test is the real assertion: layout alone could be a static snapshot.
                // An incrementing counter proves STATE and RECOMPOSITION, which is what panels need.
                Text("recompositions proven: $taps", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { taps++ }) { Text("TAP TO PROVE RECOMPOSITION") }
                Spacer(Modifier.height(28.dp))
                Text(
                    if (taps == 0) "Tap once. If this number moves, the panel path is real."
                    else "State and recomposition both work on iPadOS.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
