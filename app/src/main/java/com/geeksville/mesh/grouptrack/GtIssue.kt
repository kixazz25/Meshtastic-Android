package com.geeksville.mesh.grouptrack

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ISSUE-2026-10-24 (Fred 11:04-11:11): an ISSUE button beside HELP and the close button on every screen -- capture the screen as it
 * is, add the next screens of the same function if wanted, write what is wrong, and SEND. Part of the screen standard: wherever
 * HELP is, ISSUE is (GtHelpButton and the GtPanel title bar draw both), and the screen's help id names the FUNCTION in the report.
 *
 * Where reports go -- Settings > Send issue reports to (Fred 11:03: "a switch in the process -- collect on device or send email"):
 *   EMAIL (the default): the tablet's email app opens to support@grouptrack.org with the screenshots attached and the function,
 *     version, mode and note filled in -- the rider taps send. support@grouptrack.org forwards to Fred (ImprovMX), and can be pointed
 *     at a ticket desk (Zoho Desk free) so every report becomes an open ticket.
 *   THIS TABLET: saved in the app's own files, issues/ (no permission needed); one adb pull brings them back.
 * Every report is also written to issues/ as <stamp>.txt beside its pictures, whichever way it goes.
 */
object GtIssue {
    const val TO = "support@grouptrack.org"
    private const val PREF = "gt_issue"
    private const val KEY = "send_to"          // "email" | "device"

    fun sendsEmail(ctx: Context): Boolean = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "email") != "device"
    fun setSendsEmail(ctx: Context, email: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, if (email) "email" else "device").apply()
        sendsEmailState.value = email
    }
    /** the switch's current value, for the Settings row to redraw. */
    val sendsEmailState = mutableStateOf(true)

    /** the report panel is showing. */
    val open = mutableStateOf(false)
    /** between "+ NEXT SCREEN" and CAPTURE: the rider is moving to the next screen. */
    val nextPending = mutableStateOf(false)
    /** the pictures of this report, in order. */
    val shots = mutableStateListOf<File>()
    /** the function the report is about: the help id of the screen ISSUE was pressed on ("" before a report starts). */
    var function = ""
    val note = mutableStateOf("")
    private var stamp = ""

    private fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null), "issues").apply { mkdirs() }

    /** ISSUE pressed: a new report, the screen captured as it is. */
    fun start(view: View, fid: String) {
        val act = view.context.findActivity() ?: return
        sendsEmailState.value = sendsEmail(act)
        shots.forEach { it.delete() }; shots.clear()
        note.value = ""; function = fid
        stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        capture(act, windowOf(view, act)) { open.value = true }
    }

    /** "+ NEXT SCREEN" -> CAPTURE: the screen now showing. */
    fun captureNext(act: Activity) { capture(act, act.window) { open.value = true } }

    /** the window a view is drawn in -- a dialog's own window when the screen is a dialog, else the activity's. */
    private fun windowOf(view: View, act: Activity): Window {
        var v: Any? = view
        while (v is View) {
            if (v is DialogWindowProvider) return v.window
            v = v.parent
        }
        return act.window
    }

    private fun capture(act: Activity, w: Window, then: () -> Unit) {
        val v = w.decorView
        if (v.width <= 0 || v.height <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { then(); return }
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(w, bmp, { r ->
            if (r == PixelCopy.SUCCESS) {
                val f = File(dir(act), "issue_${stamp}_${shots.size + 1}.jpg")
                runCatching { f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                if (f.exists()) shots.add(f)
            }
            then()
        }, Handler(Looper.getMainLooper()))
    }

    private fun details(ctx: Context): String {
        val ver = runCatching { com.geeksville.mesh.BuildConfig.VERSION_NAME + " (" + com.geeksville.mesh.BuildConfig.VERSION_CODE + ")" }.getOrDefault("?")
        val mode = runCatching { if (com.geeksville.mesh.convoy.GroupMode.on.value) "Group" else "Android only" }.getOrDefault("?")
        return "Function: $function\nApp: $ver\nMode: $mode\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n" +
            "When: ${stamp.replace('_', ' ')}\nScreens: ${shots.size}\n\nWhat is wrong:\n${note.value.trim()}\n"
    }

    /** SEND (email) or SAVE (this tablet). */
    fun finish(ctx: Context) {
        val text = details(ctx)
        runCatching { File(dir(ctx), "issue_$stamp.txt").writeText(text) }
        open.value = false
        if (sendsEmail(ctx)) {
            val uris = ArrayList<Uri>(shots.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", it) })
            val send = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(TO))
                putExtra(Intent.EXTRA_SUBJECT, "GroupTrack issue \u00b7 $function \u00b7 " +
                    runCatching { com.geeksville.mesh.BuildConfig.VERSION_NAME }.getOrDefault("?"))
                putExtra(Intent.EXTRA_TEXT, text)
                if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) else if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { ctx.startActivity(Intent.createChooser(send, "Send the issue report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { android.widget.Toast.makeText(ctx, "No email app -- the report is saved on this tablet.", android.widget.Toast.LENGTH_LONG).show() }
        } else {
            android.widget.Toast.makeText(ctx, "Issue saved on this tablet (${shots.size} screen(s)).", android.widget.Toast.LENGTH_LONG).show()
        }
        shots.clear()
    }

    fun cancel() {
        shots.forEach { it.delete() }; shots.clear(); open.value = false; nextPending.value = false
    }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** ISSUE -- beside HELP and the close button (the screen standard). [id] = the screen's help id = the function in the report. */
@Composable
fun GtIssueButton(id: String, color: Color = Gt.accent) {
    val view = LocalView.current
    TextButton(onClick = { GtIssue.start(view, id) }) { Text("ISSUE", color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}

/** The report panel, and the CAPTURE bar while the rider moves to the next screen. Drawn by GtTheme on every screen. */
@Composable
fun GtIssueHost() {
    val ctx = LocalContext.current
    val s = GtScheme.current.value
    if (GtIssue.nextPending.value) {
        Popup(alignment = Alignment.BottomCenter, offset = IntOffset(0, -260)) {
            Row(Modifier.background(s.panel, RoundedCornerShape(12.dp)).border(1.dp, s.edge, RoundedCornerShape(12.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Go to the next screen, then", color = s.panelText, fontSize = 14.sp)
                GtButton("CAPTURE", {
                    GtIssue.nextPending.value = false
                    ctx.findActivity()?.let { a -> Handler(Looper.getMainLooper()).postDelayed({ GtIssue.captureNext(a) }, 250) }
                })
                GtButton("DONE", { GtIssue.nextPending.value = false; GtIssue.open.value = true })
            }
        }
    }
    if (!GtIssue.open.value) return
    val email = GtIssue.sendsEmailState.value
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Report an issue", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Screen: " + GtIssue.function, fontSize = 14.sp)
                Text("${GtIssue.shots.size} screen(s) captured", fontSize = 14.sp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GtIssue.shots.forEach { f ->
                        val bmp = remember(f.path) {
                            runCatching { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 8 }) }.getOrNull()
                        }
                        if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = "screen", modifier = Modifier.height(110.dp))
                    }
                }
                OutlinedTextField(value = GtIssue.note.value, onValueChange = { GtIssue.note.value = it },
                    label = { Text("What is wrong?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Text(if (email) "Sends to ${GtIssue.TO} from your email app." else "Saved on this tablet (Settings > Send issue reports to).",
                    fontSize = 12.sp)
            }
        },
        confirmButton = { GtButton(if (email) "SEND" else "SAVE", { GtIssue.finish(ctx) }) },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GtButton("+ NEXT SCREEN", { GtIssue.open.value = false; GtIssue.nextPending.value = true })
                GtButton("CANCEL", { GtIssue.cancel() })
            }
        },
    )
}
