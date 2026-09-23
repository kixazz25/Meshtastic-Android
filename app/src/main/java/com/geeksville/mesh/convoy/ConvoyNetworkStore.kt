package com.geeksville.mesh.convoy

import android.util.Log
import com.grouptrack.core.NetworkConfig
import com.grouptrack.core.NetworkGenerator
import com.grouptrack.core.OwnerType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * NETGEN-2026-09-23 — stores network configs (network_configs). Same pattern as
 * ConvoyProfileStore: SpatialDbManager's extension handle, never a second connection;
 * failures are logged and return null.
 *
 * Configs are IMMUTABLE (09-22): created once, never edited. Rotation (later) makes a new one.
 */
object ConvoyNetworkStore {

    private const val TAG = "ConvoyNetworkStore"

    private const val COLS =
        "config_id, owner_type, owner_id, display_name, base_version, region, modem_preset, " +
            "hop_limit, tx_power, frequency_slot, psk, role, network_ssid, network_password"

    private fun nowUtc(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    /** True when this tablet already holds a config with this id. */
    fun exists(configId: String): Boolean {
        val db = SpatialDbManager.getExtensionDb() ?: return true   // unknown = treat as taken
        return try {
            db.rawQuery("SELECT 1 FROM network_configs WHERE config_id = ? LIMIT 1",
                arrayOf(configId)).use { c -> c.moveToFirst() }
        } catch (e: Exception) {
            Log.w(TAG, "exists() failed: ${e.message}")
            true
        }
    }

    /** Generates and saves a new config. Returns it, or null when nothing was written. */
    fun create(ownerType: OwnerType, ownerId: String, displayName: String): NetworkConfig? {
        val db = SpatialDbManager.getExtensionDb() ?: return null
        if (ownerId.isBlank() || displayName.isBlank()) {
            Log.w(TAG, "create() refused: owner id and display name are required")
            return null
        }
        val cfg = NetworkGenerator.generate(ownerType, ownerId, displayName) { exists(it) }
            ?: run { Log.e(TAG, "create(): no free channel id after 10 tries"); return null }
        return try {
            db.execSQL(
                "INSERT INTO network_configs ($COLS, tak_sdk_version, created_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,?)",
                arrayOf<Any?>(
                    cfg.configId, cfg.ownerType.db, cfg.ownerId, cfg.displayName, cfg.baseVersion,
                    cfg.region, cfg.modemPreset, cfg.hopLimit, cfg.txPower, cfg.frequencySlot,
                    cfg.psk, cfg.role, cfg.networkSsid, cfg.networkPassword, nowUtc()
                )
            )
            Log.i(TAG, "NETGEN-2026-09-23: ${cfg.ownerType.db} config ${cfg.configId} " +
                "created for '${cfg.displayName}'")
            cfg
        } catch (e: Exception) {
            Log.e(TAG, "create() failed: ${e.message}")
            null
        }
    }

    /**
     * Removes a config ONLY if no ride uses it. Used when a ride save fails after its config was
     * written, so a failed save leaves no ghost config. A config in use is never touched.
     */
    fun deleteUnused(configId: String) {
        val db = SpatialDbManager.getExtensionDb() ?: return
        try {
            db.execSQL(
                "DELETE FROM network_configs WHERE config_id = ? AND config_id NOT IN " +
                    "(SELECT config_id FROM rides WHERE config_id IS NOT NULL)",
                arrayOf<Any?>(configId)
            )
        } catch (e: Exception) {
            Log.w(TAG, "deleteUnused failed: ${e.message}")
        }
    }

    /** One config by id, or null. */
    fun load(configId: String): NetworkConfig? = query("config_id = ?", arrayOf(configId)).firstOrNull()

    /** Every config owned by [ownerType]/[ownerId], newest first. */
    fun listByOwner(ownerType: OwnerType, ownerId: String): List<NetworkConfig> =
        query("owner_type = ? AND owner_id = ? ORDER BY created_at DESC", arrayOf(ownerType.db, ownerId))

    private fun query(where: String, args: Array<String>): List<NetworkConfig> {
        val db = SpatialDbManager.getExtensionDb() ?: return emptyList()
        return try {
            db.rawQuery("SELECT $COLS FROM network_configs WHERE $where", args).use { c ->
                val out = mutableListOf<NetworkConfig>()
                while (c.moveToNext()) {
                    val type = OwnerType.values().firstOrNull { it.db == c.getString(1) } ?: continue
                    out += NetworkConfig(
                        configId = c.getString(0), ownerType = type, ownerId = c.getString(2),
                        displayName = c.getString(3), baseVersion = c.getInt(4),
                        region = c.getString(5), modemPreset = c.getString(6),
                        hopLimit = c.getInt(7), txPower = c.getInt(8), frequencySlot = c.getInt(9),
                        psk = c.getString(10), role = c.getString(11),
                        networkSsid = c.getString(12) ?: c.getString(0),
                        networkPassword = if (c.isNull(13)) null else c.getString(13)
                    )
                }
                out
            }
        } catch (e: Exception) {
            Log.w(TAG, "query() failed: ${e.message}")
            emptyList()
        }
    }
}
