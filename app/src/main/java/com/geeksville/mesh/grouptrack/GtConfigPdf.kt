package com.geeksville.mesh.grouptrack

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File

/**
 * CONFIGPDF-2026-10-26 (Fred 10:34-10:40): RADIO SETUP > Archived setups -- any config as a PDF of the 25 values GroupTrack sets,
 * by section, so ANY mesh radio can be set up by hand for a ride (the manual refers to this PDF as that tool). From Archived setups
 * ONLY. VIEW PDF opens it; EMAIL PDF sends it as an attachment. The values come from the comm side (CommConfig.managedSettings).
 */
object GtConfigPdf {
    private const val W = 612
    private const val H = 792
    private const val M = 48f

    fun build(ctx: Context, title: String, detail: String, radio: String, rows: List<com.grouptrack.comm.ConfigSetting>): File {
        val doc = PdfDocument()
        val head = Paint().apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val sec = Paint().apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val body = Paint().apply { textSize = 11f; isAntiAlias = true }
        val mono = Paint().apply { textSize = 11f; typeface = Typeface.MONOSPACE; isAntiAlias = true }
        val small = Paint().apply { textSize = 9f; isAntiAlias = true; color = android.graphics.Color.DKGRAY }
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M
        }
        fun need(h: Float) { if (page == null || y + h > H - M) newPage() }
        fun line(text: String, p: Paint, gap: Float = 4f) {
            need(p.textSize + gap); y += p.textSize
            page!!.canvas.drawText(text, M, y, p); y += gap
        }
        fun wrap(text: String, p: Paint) {
            var cur = ""
            text.split(" ").forEach { w ->
                val t = if (cur.isEmpty()) w else "$cur $w"
                if (p.measureText(t) > W - 2 * M && cur.isNotEmpty()) { line(cur, p); cur = w } else cur = t
            }
            if (cur.isNotEmpty()) line(cur, p)
        }
        newPage()
        line("GroupTrack — mesh radio setup", head, 8f)
        line(title, sec)
        if (detail.isNotBlank()) line(detail, body)
        if (radio.isNotBlank()) line("From radio $radio", body)
        val ver = runCatching { com.geeksville.mesh.BuildConfig.VERSION_NAME }.getOrDefault("?")
        line("Made " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()) +
            " · GroupTrack " + ver, small, 10f)
        wrap("These are the ${rows.size} settings GroupTrack sets on a mesh radio for this config. To put any mesh radio on this " +
            "ride by hand, set each value below in the Meshtastic app's radio configuration, section by section. Everything else " +
            "on the radio can stay as it is.", body)
        rows.groupBy { it.section }.forEach { (s, list) ->
            need(sec.textSize + 12f + (body.textSize + 5f) * 2)
            y += 8f
            line(s, sec, 6f)
            list.forEach { r ->
                need(body.textSize + 5f)
                y += body.textSize
                page!!.canvas.drawText(r.name, M + 12f, y, body)
                page!!.canvas.drawText(r.value, M + 222f, y, mono)
                y += 5f
            }
        }
        y += 12f
        wrap("Keep this document private: it holds the channel key. Anyone with the key can see the group's positions.", small)
        page?.let { doc.finishPage(it) }
        val dir = File(ctx.cacheDir, "radio_configs").apply { mkdirs() }
        val safe = title.replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().ifBlank { "config" }.take(60)
        val f = File(dir, "GroupTrack radio setup - $safe.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    private fun uri(ctx: Context, f: File) = FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", f)

    /** VIEW PDF: open it in the tablet's PDF viewer. */
    fun view(ctx: Context, f: File) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri(ctx, f), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure {
            android.widget.Toast.makeText(ctx, "No PDF viewer on this tablet — use EMAIL PDF.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** EMAIL PDF: the share sheet with the PDF attached. */
    fun email(ctx: Context, f: File, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_SUBJECT, "GroupTrack radio setup · $title")
            putExtra(Intent.EXTRA_TEXT, "The mesh radio settings for “$title”, from GroupTrack. Set them by hand in the " +
                "Meshtastic app to put a radio on this ride.")
            putExtra(Intent.EXTRA_STREAM, uri(ctx, f))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { ctx.startActivity(Intent.createChooser(send, "Email the radio setup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { android.widget.Toast.makeText(ctx, "No email app on this tablet.", android.widget.Toast.LENGTH_LONG).show() }
    }
}
