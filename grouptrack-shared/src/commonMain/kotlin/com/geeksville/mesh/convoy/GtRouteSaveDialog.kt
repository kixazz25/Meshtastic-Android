package com.geeksville.mesh.convoy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geeksville.mesh.grouptrack.GtRouteLaunch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SAVEDIALOGLIFT-2026-11-23: THE SAVE DIALOG -- RouteModule.kt:1425-1476, moved here so both platforms draw
 * the one dialog. Strings, validation and the order of work are Android's, unchanged.
 *
 * The four host pieces are parameters, the same way [ConvoyRouteToolbar]'s `help` is:
 * [title] (Android's GtDialogTitle needs LocalView for issue capture), [onToast] (Toast / the iPad's
 * own message channel), [rename] (the draft rename, on the host's IO) and [onSaveCompleted] (the
 * route commit).
 */
@Composable
fun GtRouteSaveDialog(
    title: @Composable () -> Unit,
    /** R.routeName.value */
    name: String,
    /** R.saveOrigName.value -- the name the draft currently has on disk. */
    origName: String,
    pointCount: Int,
    resumed: Boolean,
    onName: (String) -> Unit,
    onToast: (String) -> Unit,
    onDismiss: () -> Unit,
    /** RouteDraftStore.renameDraft on Android's IO; GtRouteDraft.renameDraft on the iPad. */
    rename: suspend (oldName: String, newName: String) -> Unit,
    /** saveCompleted() -- the route commit. */
    onSaveCompleted: () -> Unit,
    /** RouteManager.clearRoute(); draftTick++; endBuild() */
    onSaveInProgress: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (resumed) "Graduate to a saved route, or keep editing as in-progress."
                    else "Save as a completed route (needs 2+ points), or keep as in-progress."
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = if (name == GtRouteLaunch.UNNAMED) "" else name,
                    onValueChange = onName, singleLine = true,
                    label = { Text("Route name (required)") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val nm = name.trim()
                if (nm.isBlank() || nm == GtRouteLaunch.UNNAMED) onToast("Enter a route name")
                else if (pointCount < 2) onToast("Need at least 2 points")
                else {
                    onDismiss()
                    val oldNm = origName
                    scope.launch {
                        if (oldNm != nm) rename(oldNm, nm)
                        onName(nm)
                        delay(400)
                        onSaveCompleted()
                    }
                }
            }) { Text("Save as completed route") }
        },
        dismissButton = {
            TextButton(onClick = {
                val nm = name.trim()
                if (nm.isBlank() || nm == GtRouteLaunch.UNNAMED) onToast("Enter a route name")
                else {
                    onDismiss()
                    val oldNm = origName
                    scope.launch {
                        if (oldNm != nm) rename(oldNm, nm)
                        onName(nm)
                        onSaveInProgress()
                    }
                }
            }) { Text("Save as in progress") }
        }
    )
}
