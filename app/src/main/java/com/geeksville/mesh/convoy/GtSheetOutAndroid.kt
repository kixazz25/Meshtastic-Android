// RADIOCONV-2026-11-23: Android's half of the setup-sheet seam. GtConfigPdf is NOT changed -- this
// only gives the shared screen a way to reach it.
package com.geeksville.mesh.convoy

import android.content.Context
import com.grouptrack.comm.ConfigSetting
import com.grouptrack.shared.comm.GtSheetOut
import com.grouptrack.shared.log.gtLogger
import java.io.File

class GtSheetOutAndroid(private val ctx: Context) : GtSheetOut {
    private val log = gtLogger("ConfigSheet")

    override fun make(title: String, detail: String, radio: String, rows: List<ConfigSetting>): String? =
        runCatching {
            com.geeksville.mesh.grouptrack.GtConfigPdf.build(ctx, title, detail, radio, rows).absolutePath
        }.getOrElse { e -> log.w { "RADIOCONV-2026-11-23: sheet not made: ${e.message}" }; null }

    override fun open(handle: String): Boolean = runCatching {
        com.geeksville.mesh.grouptrack.GtConfigPdf.view(ctx, File(handle)); true
    }.getOrElse { false }

    override fun email(handle: String, title: String): Boolean = runCatching {
        com.geeksville.mesh.grouptrack.GtConfigPdf.email(ctx, File(handle), title); true
    }.getOrElse { false }
}
