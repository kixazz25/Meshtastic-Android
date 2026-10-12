package com.geeksville.mesh.convoy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import com.grouptrack.comm.CommConfig   // SAVEDCFG-2026-10-12 (P4.3): the list and the comparison come from the comm side
import com.grouptrack.comm.CommResult
import com.grouptrack.comm.ConfigDifference
import com.grouptrack.comm.SavedSetup

/*
 * CONFIGREVIEW-2026-09-25 v3 (Fred) -- Work with Rides -> "Review / apply saved configs".
 * APPLY and COMPARE are two separate, distinct functions -- neither needs the other:
 *   tap a config -> APPLY          the radio configurator opens with it chosen (managed fields only, verify, backup)
 *   tap a config -> COMPARE WITH... -> tap a second -> the differences: MANAGED (what an apply would change) and
 *                                  EVERYTHING ELSE (information). The comparison never applies anything.
 * Lines are titled with the RIDE each save was applied from (SAVETITLE companion file); "Radio now" = live values.
 * There is no whole-image restore.
 */

object ConfigReviewLauncher {
    var showing by mutableStateOf(false)
    fun open() { showing = true }
    fun close() { showing = false }
}

private const val NOW = CommConfig.RADIO_NOW   // SAVEDCFG-2026-10-12
private val RBG = Color(0xF20F1216)
private val RCARD = Color(0xFF161B22)
private val RSEL = Color(0xFF1F4E79)
private val RINK = Color(0xFFE8EEF5)
private val RDIM = Color(0xFF8B949E)
private val RACC = Color(0xFF58A6FF)
private val RWARN = Color(0xFFE3B341)

@Composable
fun ConfigReviewScreen(
    onClose: () -> Unit,
    convoyViewModel: ConvoyViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nodeId = convoyViewModel.radioNodeNum()?.let { "!%08x".format(it) }
    val config = convoyViewModel.radioConfig   // SAVEDCFG-2026-10-12: the Comm API
    var saves by remember { mutableStateOf(listOf<SavedSetup>()) }
    androidx.compose.runtime.LaunchedEffect(nodeId) { saves = config.savedSetups() }
    val connected = convoyViewModel.link.value == com.grouptrack.comm.Link.CONNECTED   // COMMRADIOOPS-2026-10-12
    var selected by remember { mutableStateOf<String?>(null) } // NOW or a file path
    var comparing by remember { mutableStateOf(false) } // compare mode: waiting for the second line
    var compared by remember { mutableStateOf<Pair<List<ConfigDifference>, List<ConfigDifference>>?>(null) }
    var names by remember { mutableStateOf("" to "") }
    var message by remember { mutableStateOf("") }

    BackHandler(enabled = true) {
        when {
            compared != null -> { compared = null; selected = null }
            comparing -> comparing = false
            selected != null -> selected = null
            else -> onClose()
        }
    }

    fun titleOf(k: String) = if (k == NOW) "Radio now" else saves.firstOrNull { it.id == k }?.title ?: "a saved config"
    fun detailOf(k: String) = if (k == NOW) "live \u2014 what the radio holds at this moment" else saves.firstOrNull { it.id == k }?.detail.orEmpty()

    fun compare(a: String, b: String) {
        scope.launch {
            // SAVEDCFG-2026-10-12: the comm side compares; each row says if it is managed (what an apply would change).
            val (all, r) = config.compareConfigs(a, b)
            if (r is CommResult.Failed) message = "Cannot compare: ${r.reason}"
            else {
                compared = all.filter { it.managed } to all.filter { !it.managed }
                names = titleOf(a) to titleOf(b)
            }
            comparing = false
        }
    }

    fun tap(k: String) {
        message = ""
        val first = selected
        when {
            comparing && first != null && k != first -> compare(first, k)
            comparing -> {}
            else -> selected = if (selected == k) null else k
        }
    }

    fun apply(k: String) {
        RadioConfigLauncher.preselect = k   // SAVEDCFG-2026-10-12: the save's id
        onClose()
        RadioConfigLauncher.open()
    }

    Box(Modifier.fillMaxSize().background(RBG).clickable(enabled = false) {}) {
        // CFGTOP-2026-10-12 (Fred 10-12): the actions stay at the top; the list, or the comparison, scrolls underneath.
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Saved configs", color = RINK, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Radio ${nodeId ?: "(none)"} \u2022 ${saves.size} saved", color = RDIM, fontSize = 13.sp)
                }
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
            Spacer(Modifier.height(10.dp))
            val c = compared
            val sel = selected
            // the actions for the current step -- always in view
            when {
                c != null -> {
                    Text("A: ${names.first}", color = RINK, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("B: ${names.second}", color = RINK, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { compared = null; selected = null }) { Text("Back to the list") }
                }
                comparing -> {
                    Text("Comparing \u201c${titleOf(selected!!)}\u201d with\u2026 tap another line", color = RACC, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { comparing = false }) { Text("Cancel compare") }
                }
                sel != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { apply(sel) }, enabled = sel != NOW && connected) { Text("Apply") }
                        Button(onClick = { comparing = true }) { Text("Compare with\u2026") }
                        OutlinedButton(onClick = { selected = null }) { Text("Cancel") }
                    }
                    if (sel == NOW) Text("Radio now is the radio itself \u2014 it can be compared, not applied.", color = RDIM, fontSize = 12.sp)
                }
                else -> Text("Tap a config, then Apply or Compare.", color = RINK)
            }
            if (message.isNotEmpty()) Text(message, color = Color(0xFFF85149), fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            // the list, or the comparison -- scrolls under the actions
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                if (c == null) {
                    val entries = (if (connected) listOf(NOW) else emptyList()) + saves.map { it.id }
                    if (entries.isEmpty()) Text("No saved configs for this radio yet.", color = RDIM)
                    entries.forEach { k ->
                        val on = k == selected
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                .background(if (on) RSEL else RCARD, RoundedCornerShape(8.dp))
                                .clickable { tap(k) }.padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(titleOf(k), color = if (on) Color.White else RINK, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(detailOf(k), color = RDIM, fontSize = 12.sp)
                        }
                    }
                } else {
                    if (c.first.isEmpty() && c.second.isEmpty()) Text("Identical.", color = RACC)
                    if (c.first.isNotEmpty()) {
                        Text("Managed \u2014 what an apply would change (${c.first.size})", color = RWARN, fontWeight = FontWeight.Bold)
                        c.first.forEach { Text("  ${it.field}: ${it.a} \u2192 ${it.b}", color = RWARN, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (c.second.isNotEmpty()) {
                        Text("Everything else \u2014 information only (${c.second.size})", color = RINK, fontWeight = FontWeight.Bold)
                        c.second.forEach { Text("  ${it.field}: ${it.a} \u2192 ${it.b}", color = RDIM, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
        }
    }
}
