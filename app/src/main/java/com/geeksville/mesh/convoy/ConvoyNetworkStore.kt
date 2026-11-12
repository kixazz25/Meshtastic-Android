package com.geeksville.mesh.convoy

import com.geeksville.mesh.grouptrack.GtAppDb
import com.grouptrack.shared.net.GtNetworkStore
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

    // SWITCH17G2C1-2026-11-12 (2.8 step 17 G2c-1): front doors to the shared GtNetworkStore (certified OLD = NEW at 803ecd4c0) on SpatialDbManager's
    // existing extension handle; the generator is NetworkGenerator's own (one SecureRandom); the mesh values are read at each create,
    // as 2.7e read MeshDefaults inside generate(). Same SQL, same refusals, same log lines (tag ConvoyNetworkStore).

    private fun nowUtc(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    private val store = GtNetworkStore({ SpatialDbManager.getExtensionDb()?.let { GtAppDb.wrap(it) } }, NetworkGenerator.shared,
        { NetworkGenerator.meshNow() }, { nowUtc() })

    /** True when this tablet already holds a config with this id (no database = treat as taken). */
    fun exists(configId: String): Boolean = store.exists(configId)

    /** Generates and saves a new config. Returns it, or null when nothing was written. */
    fun create(ownerType: OwnerType, ownerId: String, displayName: String): NetworkConfig? =
        store.create(NetworkGenerator.toShared(ownerType), ownerId, displayName)?.let { NetworkGenerator.fromShared(it) }

    /** Removes a config ONLY if nothing uses it (no ride, not the rider's own, not an organization's). */
    fun deleteUnused(configId: String) = store.deleteUnused(configId)

    /** One config by id, or null. */
    fun load(configId: String): NetworkConfig? = store.load(configId)?.let { NetworkGenerator.fromShared(it) }

    /** Every config owned by [ownerType]/[ownerId], newest first. */
    fun listByOwner(ownerType: OwnerType, ownerId: String): List<NetworkConfig> =
        store.listByOwner(NetworkGenerator.toShared(ownerType), ownerId).map { NetworkGenerator.fromShared(it) }
}
