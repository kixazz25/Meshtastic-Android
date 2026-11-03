package com.geeksville.mesh.grouptrack

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.geeksville.mesh.convoy.GroupTrackStorage
import com.geeksville.mesh.convoy.VectorTiles
import java.io.File

/**
 * BACKUP-2026-11-03 (Fred 11:32-11:41): everything needed to recreate GroupTrack after a forced reload, a crash or a new phone -- the four
 * export-alls (routes, tracks, waypoints, rider trails) and the newest map recipe -- in ONE email the rider sends to himself.
 *  - every app start with internet: built in the background (cache only), fingerprinted; offered (SEND BACKUP / NOT NOW) only when it
 *    differs from the last one SENT;
 *  - Map > Back up my GroupTrack data: the same on demand (also saves the GPX files to Downloads).
 */
object GtBackup {
    private const val TAG = "Backup"
    private const val PREF = "gt_backup"
    private val TYPES = listOf("routes" to "routes", "tracks" to "tracks", "waypoints" to "waypoints", "trails" to "rider trails")

    /** The summary of a CHANGED backup waiting for SEND BACKUP / NOT NOW. "" = nothing to offer (CODE RULE 1). */
    val ready = androidx.compose.runtime.mutableStateOf("")

    private class Built(val files: List<File>, val list: String, val print: String)
    @Volatile private var waiting: Built? = null   // the backup behind [ready]; null = none built
    @Volatile private var checked = false           // the start-up check runs once per app start

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    private fun online(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) { false }

    /** Start-up: with internet, not NOT-NOW'd today -> build, compare with the last SENT, offer only a change. */
    fun autoCheck(ctx: Context) {
        if (checked) return
        checked = true
        if (!online(ctx)) { Log.i(TAG, "BACKUP start-up check: no internet"); return }
        if (prefs(ctx).getString("notNow", "") == today()) { Log.i(TAG, "BACKUP start-up check: NOT NOW today"); return }
        Thread {
            try {
                Thread.sleep(20_000)   // after the start-up work
                GtDbAudit.run(ctx)   // DBAUDIT-2026-11-03: read-only, logged
                val b = build(ctx, false)
                when {
                    b.files.isEmpty() -> Log.i(TAG, "BACKUP start-up check: nothing to back up")
                    b.print == prefs(ctx).getString("sent", "") -> Log.i(TAG, "BACKUP unchanged since the last backup sent -- nothing offered")
                    else -> {
                        // BACKUPDIAG-2026-11-03: which part changed since the backup sent
                        val was = (prefs(ctx).getString("sent", "") ?: "").split(';').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
                        val now = b.print.split(';').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
                        val diff = (was.keys + now.keys).filter { was[it] != now[it] }.joinToString(", ") { k -> "$k ${was[k]?.take(8) ?: "none"}->${now[k]?.take(8) ?: "none"}" }
                        Log.i(TAG, "BACKUP changed -- offering SEND BACKUP (${b.files.size} files); changed since sent: ${diff.ifEmpty { "(nothing sent yet)" }}")
                        waiting = b
                        android.os.Handler(android.os.Looper.getMainLooper()).post { ready.value = b.list }
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "BACKUP start-up check failed", e) }
        }.start()
    }

    /** Map > Back up my GroupTrack data: build now (GPX also to Downloads) and open the email. */
    fun start(ctx: Context) {
        android.widget.Toast.makeText(ctx, "Building your GroupTrack backup…", android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            try { GtDbAudit.run(ctx) } catch (e: Exception) { Log.e(TAG, "DBAUDIT", e) }   // DBAUDIT-2026-11-03
            val b = try { build(ctx, true) } catch (e: Exception) { Log.e(TAG, "BACKUP build", e); Built(emptyList(), "Backup failed: ${e.message}", "") }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (b.files.isEmpty()) android.widget.Toast.makeText(ctx, b.list.ifEmpty { "Nothing to back up yet." }, android.widget.Toast.LENGTH_LONG).show()
                else send(ctx, b)
            }
        }.start()
    }

    fun sendReady(ctx: Context) { val b = waiting; ready.value = ""; waiting = null; if (b != null) send(ctx, b) }
    fun notNow(ctx: Context) { prefs(ctx).edit().putString("notNow", today()).apply(); ready.value = ""; waiting = null; Log.i(TAG, "BACKUP NOT NOW") }

    private fun sha(s: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    /** The backup's files (cache/backup), the email's list, and its fingerprint. */
    private fun build(ctx: Context, toDownloads: Boolean): Built {
        val dir = File(ctx.cacheDir, "backup").also { it.mkdirs(); it.listFiles()?.forEach { x -> x.delete() } }
        val files = ArrayList<File>(); val list = StringBuilder(); val print = StringBuilder()
        for ((type, label) in TYPES) {
            val msg = GtExportAll.run(ctx, type, toDownloads)
            val f = GtExportAll.takeWritten() ?: continue   // none of this type, or it could not be written
            val copy = File(dir, f.name); f.copyTo(copy, overwrite = true); files.add(copy)
            val n = Regex("Exported (\\d+)").find(msg)?.groupValues?.get(1) ?: "?"
            list.append("  • ").append(n).append(' ').append(label).append('\n')
            print.append(type).append('=').append(sha(f.readText().replace(Regex("GroupTrack_[A-Z_]+_\\d{4}-\\d{2}-\\d{2}"), ""))).append(';')
        }
        val recipe = GroupTrackStorage.dir("recipes", ctx).listFiles()
            ?.filter { it.name.startsWith("grouptrack_maps_") && it.name.endsWith(".gtmaps") }?.maxByOrNull { it.name }
        if (recipe != null) {
            val day = recipe.name.removePrefix("grouptrack_maps_").removeSuffix(".gtmaps")
            val copy = File(dir, "GroupTrack maps $day.json"); recipe.copyTo(copy, overwrite = true); files.add(copy)   // RECIPEJSON-2026-11-03
            list.append("  • your maps as of ").append(day).append('\n')
            // the recipe is rewritten daily with a new date -- its MAP BOXES are what changes
            print.append("maps=").append(sha(VectorTiles.boxesOf(recipe).joinToString("|") { it.joinToString(",") })).append(';')
        }
        Log.i(TAG, "BACKUP built ${files.size} file(s) toDownloads=$toDownloads: ${files.joinToString { "${it.name} ${it.length()}" }}")
        return Built(files, list.toString(), print.toString())
    }

    /** The email: to the rider's profile email, the files attached, the recovery steps. Its fingerprint becomes the one SENT. */
    private fun send(ctx: Context, b: Built) {
        val day = today()
        val me = try { com.geeksville.mesh.convoy.ConvoyProfileStore.load()?.email?.trim() ?: "" } catch (e: Exception) { "" }
        val uris = ArrayList(b.files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", it) })
        val body = "My GroupTrack backup, $day. Keep this email — it recreates GroupTrack on a new or reloaded phone.\n\n" +
            "In this backup:\n" + b.list + "\n" +
            "TO RECOVER, on the phone or tablet, with GroupTrack installed and Wi-Fi on:\n" +
            "1. Tap the maps file (GroupTrack maps <date>.json), choose GroupTrack, tap RESTORE MY MAPS. The maps download while you do the rest.\n" +
            "2. Tap the ROUTES, TRACKS and WAYPOINTS files one at a time, choose GroupTrack, untick MAPS, tap IMPORT.\n" +
            "3. Save the RIDER TRAILS file to Downloads and import it with Map › Import trails from GPX files.\n" +
            "Importing something that is already there is safe — it is not added twice.\n\n" +
            "Get GroupTrack on Google Play:\nhttps://play.google.com/store/apps/details?id=com.grouptrack.android&hl=en_US\n"
        val mail = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            if (me.isNotEmpty()) putExtra(Intent.EXTRA_EMAIL, arrayOf(me))
            putExtra(Intent.EXTRA_SUBJECT, "GroupTrack backup $day")
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            val gmail = Intent(mail).setPackage("com.google.android.gm")
            if (gmail.resolveActivity(ctx.packageManager) != null) ctx.startActivity(gmail)
            else ctx.startActivity(Intent.createChooser(mail, "Send or save your GroupTrack backup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            prefs(ctx).edit().putString("sent", b.print).apply()
            Log.i(TAG, "BACKUP email opened: ${b.files.size} attachment(s), to=${if (me.isEmpty()) "(none)" else "profile email"}")
        } catch (e: Exception) {
            Log.e(TAG, "BACKUP open failed", e)
            android.widget.Toast.makeText(ctx, "Could not open the email: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

/** BACKUP-2026-11-03: the start-up offer -- shown only when the backup CHANGED since the last one sent. */
@Composable
fun GtBackupReadyDialog() {
    val list = GtBackup.ready.value
    if (list.isEmpty()) return
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = { GtBackup.notNow(ctx) },
        title = { Text("Your GroupTrack data changed", fontWeight = FontWeight.Bold, color = Gt.text) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("A new backup is ready:", color = Gt.text, fontSize = 14.sp)
                Text(list, color = Gt.text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                Text("Email it to yourself to keep it safe. It recreates GroupTrack on a new or reloaded phone.", color = Gt.dim, fontSize = 12.sp)
            }
        },
        confirmButton = { GtButton("SEND BACKUP", { GtBackup.sendReady(ctx) }) },
        dismissButton = { GtButton("NOT NOW", { GtBackup.notNow(ctx) }) },
        containerColor = Gt.panel
    )
}
