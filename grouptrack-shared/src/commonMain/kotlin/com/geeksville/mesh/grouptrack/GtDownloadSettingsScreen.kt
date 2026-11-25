package com.geeksville.mesh.grouptrack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.grouptrack.shared.settings.GtSettings

/**
 * DLSETTINGS-2026-11-25 (2.8b, map row 2, map.dl_settings): Map > DOWNLOAD MAPS FOR OFFLINE USE > Download settings, SHARED.
 * Was Android's GtDownloadSettingsPanel (grouptrack/DownloadDefaults.kt, DLDEFAULTS-2026-10-24), which stays as Android's
 * host. Every map download starts from this choice; CHANGE on a download panel alters that download only.
 */

/** map.dl_settings' LAUNCH, the shared standard (QueuesLauncher, RidesLauncher): a host's actions entry calls open(). */
object DlSettingsLauncher {
    var showing by mutableStateOf(false)
    fun open() { showing = true }
    fun close() { showing = false }
}

/** The one rule both hosts read. Android: SharedPreferences("gt_download_defaults") -- the same store and key, so a
 *  rider's saved answer carries over. Replace is YES until saved (REPLACEDEFAULT-2026-11-03, Fred 11:10). */
object GtDlDefaults {
    const val STORE = "gt_download_defaults"
    fun replace(settings: GtSettings): Boolean = settings.store(STORE).getBoolean("replace", true)
    fun saveReplace(settings: GtSettings, replace: Boolean) { settings.store(STORE).edit { putBoolean("replace", replace) } }
}

/** The screen: Android's text and its one choice, on the chrome standard (HELP, ISSUE, close). One map, so no
 *  source list (ONESOURCEUI-2026-10-30). [replace] is where it starts; SAVE hands the choice to [onSave]. */
@Composable
fun GtDownloadSettingsScreen(replace: Boolean, onSave: (Boolean) -> Unit, onClose: () -> Unit) {
    val chrome = LocalGtPanelChrome.current
    var rep by remember { mutableStateOf(replace) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            GtPanel(
                title = "Download settings",
                helpId = "map.dl_settings",
                onHelp = { id -> chrome.openHelp(id) },
                onDone = onClose,
                stays = false,
                width = if (maxWidth < 360.dp) maxWidth else 360.dp,   // Android: width(360.dp)
                maxHeight = maxHeight,
            ) {
                Text("Every map download uses these. Each download shows them, and CHANGE there alters that download only.",
                    color = Gt.text, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().clickable { rep = !rep }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GtCheck(rep)
                    Text("Replace existing tiles", color = Gt.text, fontSize = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GtButton("SAVE", { onSave(rep) })
                    GtButton("CANCEL", onClose)
                }
            }
        }
    }
}
