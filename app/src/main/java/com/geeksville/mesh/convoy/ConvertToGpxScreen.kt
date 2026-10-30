package com.geeksville.mesh.convoy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geeksville.mesh.grouptrack.Gt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** MAPIMPORTS-2026-10-30 (Fred 08:33): Convert files to GPX -- one step, then the rider picks the import that fits. Imports nothing. */
@Composable
fun ConvertToGpxScreen(onDone: () -> Unit) {   // CONVERTFIX-2026-10-30: DONE closes it
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<android.net.Uri> ->
        if (uris.isNotEmpty()) {
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { ImportConverter.convertAll(context.applicationContext, uris) }
                results = results + r
                busy = false
            }
        }
    }
    val notice = rememberPickerNotice("to choose the KML, KMZ or GeoJSON files to convert", "files")
    // ⚠ "*/*": KML / KMZ / GeoJSON are often reported as octet-stream; anything narrower shows an empty picker.
    val open = { notice.ask { pick.launch(arrayOf("*/*")) } }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!opened) { opened = true; open() } }   // as Import GPX: the picker opens itself

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Converts KML, KMZ and GeoJSON files to GPX and saves the GPX in Download. Nothing is imported here.",
            color = Gt.text, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text("Then import it with Import GPX (tracks, waypoints, routes) or Import trails from GPX files (a trail map).   /* TRAILSNAME-2026-10-30 */",
            color = Gt.dim, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        if (busy) Text("Converting…", color = Gt.accent, fontSize = 13.sp)
        results.forEach {
            Text(it, color = if (it.startsWith("FAILED")) Gt.bad else Gt.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 3.dp))
        }
        Spacer(Modifier.height(16.dp))
        // CONVERTFIX-2026-10-30 (Fred 08:47): conversion ENDS the screen -- results + DONE. SELECT FILES only before anything converted.
        val finished = results.isNotEmpty()
        if (!busy) Surface(modifier = Modifier.clickable { if (finished) onDone() else open() }, shape = RoundedCornerShape(10.dp), color = Gt.panel) {
            Text(if (finished) "  DONE  " else "  SELECT FILES  ", color = Gt.accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 10.dp))
        }
    }
}
