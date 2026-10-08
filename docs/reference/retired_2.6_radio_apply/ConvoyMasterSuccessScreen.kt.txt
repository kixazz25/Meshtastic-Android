package com.geeksville.mesh.convoy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ConvoyMasterSuccessScreen — shown after radio read completes.
 * Displays captured config data.
 * SAVE & CHECKLIST saves and navigates to apply list.
 * CAPTURE NEW MASTER re-reads the radio.
 */
@Composable
fun ConvoyMasterSuccessScreen(
    onSaveAndChecklist: () -> Unit,
    onCaptureNew: () -> Unit
) {
    val context = LocalContext.current
    val master  = remember { ConvoyMasterConfig.load(context) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101510))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))

            Text(
                text          = "✓  MASTER CAPTURED",
                color         = Color(0xFF97D5A5),
                fontSize      = 14.sp,
                fontFamily    = FontFamily.Monospace,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 2.sp,
                textAlign     = TextAlign.Center,
                modifier      = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF262B26)))
            Spacer(Modifier.height(16.dp))

            if (master != null) {
                DataCard("HARDWARE",  master.hardwareModel)
                DataCard("FIRMWARE",  master.firmwareVersion)
                DataCard("REGION",    master.loraRegion)
                DataCard("PRESET",    master.loraModemPreset)
                DataCard("HOP LIMIT", master.loraHopLimit.toString())
                DataCard("TX POWER",  "${master.loraTxPower} dBm")
                DataCard("CAPTURED",  master.capturedDate)
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(10.dp),
                    color    = Color(0xFF1C211C)
                ) {
                    Text(
                        "No master config data available.",
                        color      = Color(0xFFFFB4AB),
                        fontSize   = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier   = Modifier.padding(14.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── CAPTURE NEW MASTER ────────────────────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCaptureNew() },
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1C211C)
            ) {
                Text(
                    text       = "CAPTURE NEW MASTER",
                    color      = Color(0xFFFFB4AB),
                    fontSize   = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier.padding(vertical = 14.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            // ── SAVE & CHECKLIST ──────────────────────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSaveAndChecklist() },
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF15512C)
            ) {
                Text(
                    text       = "SAVE & CHECKLIST",
                    color      = Color(0xFF97D5A5),
                    fontSize   = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center,
                    modifier   = Modifier.padding(vertical = 16.dp)
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DataCard(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape    = RoundedCornerShape(8.dp),
        color    = Color(0xFF1C211C)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                text       = label,
                color      = Color(0xFF8B938A),
                fontSize   = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text       = value,
                color      = Color(0xFFDFE4DC),
                fontSize   = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
