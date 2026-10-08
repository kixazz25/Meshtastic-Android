package com.geeksville.mesh.convoy

import android.content.Context
import android.util.Log
import org.meshtastic.proto.DeviceProfile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * ConvoyMasterApply — Function 1: Apply Master Config to Radio
 *
 * Process (no UI — called by write screen):
 *   1. Export current radio state to binary archive
 *   2. Load master.cfg from assets
 *   3. Install master.cfg to radio via InstallProfileUseCase
 *
 * Caller handles:
 *   - Connection state checking
 *   - Reboot/reconnect monitoring
 *   - UI progress display
 *
 * All file paths are hardwired — no user prompts.
 */
object ConvoyMasterApply {

    private const val TAG = "ConvoyMasterApply"
    private const val MASTER_CFG_ASSET = "master.cfg"

    /**
     * Step 1: Archive current radio state to binary .cfg file.
     * Returns the archive file path on success.
     */
    suspend fun archiveCurrentRadio(
        context: Context,
        nodeId: String,
        convoyViewModel: ConvoyViewModel
    ): Result<String> = runCatching {
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        val ts  = LocalDateTime.now().format(fmt)
        val archiveFile = java.io.File(
            context.filesDir,
            "convoy_backups/$nodeId/${nodeId}_${ts}_pre_master.cfg"
        )
        archiveFile.parentFile?.mkdirs()
        convoyViewModel.exportProfileToFile(context, archiveFile).getOrElse { throw it }
        Log.i(TAG, "Archived radio to: ${archiveFile.absolutePath}")
        archiveFile.absolutePath
    }

    /**
     * Step 2: Load master.cfg binary from assets.
     * Returns decoded DeviceProfile.
     */
    fun loadMasterProfile(context: Context): Result<DeviceProfile> = runCatching {
        context.assets.open(MASTER_CFG_ASSET).use { inputStream ->
            val bytes = inputStream.readBytes()
            DeviceProfile.ADAPTER.decode(bytes)
        }
    }

    /**
     * Step 3: Install master profile to radio.
     * Fire and forget — radio will reboot.
     * Caller monitors connection state for reconnect.
     */
    fun installMasterToRadio(
        nodeNum: Int,
        profile: DeviceProfile,
        convoyViewModel: ConvoyViewModel
    ) {
        Log.i(TAG, "Installing master.cfg to radio: ${"!%08x".format(nodeNum)}")
        convoyViewModel.installProfileToRadio(nodeNum, profile)
    }

    /**
     * Full apply sequence — archive then install.
     * Returns archive file path on success.
     * Caller must wait for radio to reboot and reconnect after this returns.
     */
    suspend fun applyMasterConfig(
        context: Context,
        nodeNum: Int,
        convoyViewModel: ConvoyViewModel
    ): Result<String> = runCatching {
        val nodeId = "!%08x".format(nodeNum)

        // Step 1: Archive current radio state
        val archivePath = archiveCurrentRadio(context, nodeId, convoyViewModel)
            .getOrElse { throw Exception("Archive failed: ${it.message}") }
        Log.i(TAG, "Archive complete: $archivePath")

        // Step 2: Load master.cfg from assets
        val profile = loadMasterProfile(context)
            .getOrElse { throw Exception("Failed to load master.cfg: ${it.message}") }
        Log.i(TAG, "master.cfg loaded — ${profile.config?.lora?.region?.name ?: "unknown"} region")

        // Step 3: Install to radio — radio will reboot
        installMasterToRadio(nodeNum, profile, convoyViewModel)
        Log.i(TAG, "master.cfg install initiated — radio rebooting")

        archivePath
    }
}
