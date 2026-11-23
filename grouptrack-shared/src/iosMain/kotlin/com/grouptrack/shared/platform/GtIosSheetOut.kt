package com.grouptrack.shared.platform

import com.grouptrack.comm.ConfigSetting
import com.grouptrack.shared.comm.GtSheetOut
import com.grouptrack.shared.log.gtLogger

/**
 * IOSRADIOWIRE-2026-11-23: the iPad's half of the setup-sheet seam.
 *
 * CONFIGPDF-2026-10-26's sheet is DRAWN on Android with android.graphics.pdf.PdfDocument. There is no
 * iOS drawing surface yet, so [make] answers null -- which is this seam's own documented meaning, "no
 * sheet could be produced" -- and ConfigReviewScreen says so and stays put. Everything else on that
 * screen works on the iPad: the saved list, Apply and Compare all come from CommConfig.
 *
 * The same shape GtIosGrpAwareness uses for `network = null` while radio settings is unfinished: a
 * stated gap, not a dead button and not a silent one.
 *
 * ⚠ The iOS renderer is its own card. It is a drawing surface, not a seam, and when it lands the
 * CONTENT decision (which values, in which sections, in what order) has to be shared first, or the two
 * platforms print different sheets for the same config -- the same reason GtRadioDoc exists.
 */
class IosSheetOut : GtSheetOut {

    private val log = gtLogger("ConfigSheet")

    override fun make(title: String, detail: String, radio: String, rows: List<ConfigSetting>): String? {
        log.i {
            "IOSRADIOWIRE-2026-11-23: the setup sheet is not drawn on the iPad yet -- " +
                rows.size + " rows asked for \"" + title + "\""
        }
        return null
    }

    override fun open(handle: String): Boolean = false

    override fun email(handle: String, title: String): Boolean = false
}
