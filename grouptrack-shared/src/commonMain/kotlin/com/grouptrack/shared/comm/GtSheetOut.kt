package com.grouptrack.shared.comm

import com.grouptrack.comm.ConfigSetting

/**
 * RADIOCONV-2026-11-23: the SETUP SHEET's platform half, and only that.
 *
 * CONFIGPDF-2026-10-26 (Fred 10-26): from Saved configs, any config as a sheet of the managed values
 * so ANY mesh radio can be set up BY HAND for a ride. The rows come from CommConfig.managedSettings,
 * which both platforms already share, so NO content decision lives in this seam -- it is the draw and
 * the hand-off, nothing else.
 *
 * ANDROID IS THE SPECIFICATION and is UNCHANGED: GtConfigPdf.build/view/email, exactly as they are
 * today (PdfDocument, FileProvider, ACTION_VIEW, ACTION_SEND). GtSheetOutAndroid delegates to them.
 *
 * CODE RULE 1: [make] returns null when no sheet could be produced -- the screen says so and stays
 * put, which is what it did before the move. [open] / [email] return false when nothing took the
 * file; the caller reports that, it is not an error (same contract as GtArtifactSink).
 */
interface GtSheetOut {
    fun make(title: String, detail: String, radio: String, rows: List<ConfigSetting>): String?
    fun open(handle: String): Boolean
    fun email(handle: String, title: String): Boolean
}
