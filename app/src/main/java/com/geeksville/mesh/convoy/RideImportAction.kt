package com.geeksville.mesh.convoy

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * GTRIDES-2026-10-22: IMPORT A RIDE as ONE shared action -- the file-picker notice, then the Android file picker, then
 * RideImport.importRide -- moved here from WorkWithRidesMenu so ONE MAP's Rides › Import a ride and the old menu run the same
 * code. [onDone] runs after a file was chosen (the old menu closes itself). Required (CODE RULE 1).
 */
@Composable
fun rememberRideImport(onDone: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val importPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        object : androidx.activity.result.contract.ActivityResultContracts.OpenDocument() {
            override fun createIntent(context: android.content.Context, input: Array<String>): android.content.Intent =
                super.createIntent(context, input).putExtra(
                    android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"),
                )
        }
    ) { uri ->
        if (uri != null) {
            val msg = try {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    ?: throw IllegalStateException("could not read the file")
                RideImport.importRide(context, text, bringForward = false)
            } catch (e: Exception) {
                "Could not read the file: ${e.message}"
            }
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            onDone()
        }
    }
    // FILEPICKNOTICE-2026-10-10: the panel before the picker.
    val importNotice = rememberPickerNotice("to choose the ride file you want to import", "file")
    return { importNotice.ask { importPicker.launch(arrayOf("*/*")) } }
}
