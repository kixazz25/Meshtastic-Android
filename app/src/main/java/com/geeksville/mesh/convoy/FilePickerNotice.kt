package com.geeksville.mesh.convoy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * FILEPICKNOTICE-2026-10-10 (Fred): a panel before EVERY GroupTrack file picker.
 *
 * Riders opened the Android picker, landed in the wrong place and could not find their way back. The panel says
 * where they are going and how to come back (select the file, or the system BACK -- arrow on the bottom bar or a
 * swipe in from the right-hand edge, possibly more than once) BEFORE they leave. CANCEL = the picker never opens.
 * The two small tablet pictures are DRAWN here (not a screenshot): no one else's image, works offline, any size.
 *
 * Use:  val notice = rememberPickerNotice("to choose the ride file you want to import", "file")
 *       ... onClick { notice.ask { launcher.launch(...) } }
 * Every picker launch in GroupTrack goes through this -- a new picker must too.
 */
class PickerNotice internal constructor() {
    internal var pending by mutableStateOf<(() -> Unit)?>(null)

    /** Shows the panel; [open] runs only on CONTINUE. */
    fun ask(open: () -> Unit) { pending = open }
}

@Composable
fun rememberPickerNotice(purpose: String, item: String): PickerNotice {
    val notice = remember { PickerNotice() }
    val open = notice.pending
    if (open != null) {
        AlertDialog(
            onDismissRequest = { notice.pending = null },
            title = { Text("Opening the Android file picker") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(pickerNoticeText(purpose, item), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(14.dp))
                    PickerBackPicture()
                }
            },
            confirmButton = { TextButton(onClick = { notice.pending = null; open() }) { Text("CONTINUE") } },
            dismissButton = { TextButton(onClick = { notice.pending = null }) { Text("CANCEL") } }
        )
    }
    return notice
}

/** Fred's text (10-10). [purpose] e.g. "to choose the ride file you want to import"; [item] "file" / "files" / "folder". */
fun pickerNoticeText(purpose: String, item: String): String =
    "You are leaving GroupTrack for the Android file picker, " + purpose + ".\n\n" +
        "To return to GroupTrack, select your " + item + ", or use the system BACK: the back arrow on the bar at " +
        "the bottom of the screen, or a swipe in from the right-hand edge. You may need to go back more than once.\n\n" +
        "If you chose this by mistake, tap CANCEL. Otherwise tap CONTINUE."

private val PicFrame = Color(0xFF8B938A)
private val PicScreen = Color(0xFF1B2229)
private val PicMark = Color(0xFFFFB74D)

/** Two tablets: the BACK arrow on the bottom bar, and the swipe in from the right edge. */
@Composable
private fun PickerBackPicture() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(width = 92.dp, height = 128.dp)) {
                tablet()
                val w = size.width; val h = size.height
                val bar = h - 24.dp.toPx(); val cy = h - 12.dp.toPx()
                drawLine(PicFrame, Offset(4.dp.toPx(), bar), Offset(w - 4.dp.toPx(), bar), 1.dp.toPx())
                val bx = w * 0.25f
                drawCircle(PicMark, radius = 9.dp.toPx(), center = Offset(bx, cy), style = Stroke(2.dp.toPx()))
                val c = Path().apply {
                    moveTo(bx + 3.dp.toPx(), cy - 5.dp.toPx()); lineTo(bx - 3.dp.toPx(), cy); lineTo(bx + 3.dp.toPx(), cy + 5.dp.toPx())
                }
                drawPath(c, PicMark, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(PicFrame, radius = 4.dp.toPx(), center = Offset(w * 0.5f, cy), style = Stroke(1.5.dp.toPx()))
                drawRect(PicFrame, topLeft = Offset(w * 0.75f - 4.dp.toPx(), cy - 4.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(8.dp.toPx(), 8.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            }
            Text("tap the back arrow", fontSize = 11.sp, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(width = 92.dp, height = 128.dp)) {
                tablet()
                val w = size.width; val y = size.height * 0.45f
                val from = Offset(w - 5.dp.toPx(), y); val to = Offset(w * 0.38f, y)
                drawCircle(PicMark.copy(alpha = 0.35f), radius = 9.dp.toPx(), center = from)
                drawLine(PicMark, from, to, 3.dp.toPx(), cap = StrokeCap.Round)
                drawLine(PicMark, to, Offset(to.x + 9.dp.toPx(), y - 8.dp.toPx()), 3.dp.toPx(), cap = StrokeCap.Round)
                drawLine(PicMark, to, Offset(to.x + 9.dp.toPx(), y + 8.dp.toPx()), 3.dp.toPx(), cap = StrokeCap.Round)
            }
            Text("or swipe in from\nthe right edge", fontSize = 11.sp, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun DrawScope.tablet() {
    val r = CornerRadius(8.dp.toPx(), 8.dp.toPx())
    drawRoundRect(PicScreen, cornerRadius = r)
    drawRoundRect(PicFrame, cornerRadius = r, style = Stroke(2.dp.toPx()))
}
