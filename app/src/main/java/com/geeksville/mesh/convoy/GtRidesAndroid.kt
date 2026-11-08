package com.geeksville.mesh.convoy

import android.content.Context
import com.geeksville.mesh.grouptrack.GtAppDb
import com.grouptrack.core.OwnerType
import com.grouptrack.shared.ride.GtRideEnv
import com.grouptrack.shared.ride.RideFiles
import com.grouptrack.shared.ride.RideMe
import com.grouptrack.shared.ride.RideNet
import com.grouptrack.shared.ride.RideNetOwner
import com.grouptrack.shared.ride.RideNetworks
import com.grouptrack.shared.ride.RideSpatial
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import com.grouptrack.shared.ride.GtRideStore as Shared

/*
 * STEP14C-2026-11-08 (2.8): the Android side of the SHARED ride model, in its own file so the certification can run while 2.7e's
 * ConvoyRideStore / ConvoyRideJsonWriter / RideApplySource / RideImport stay live. Nothing calls it except GtCertRides until the
 * switch (step 14e), when the four wrappers start using it. Same code as step 14b's wrapper held.
 */

/** STEP14: the Android side of the shared ride model -- the one place the app's stores, databases, files and clock are handed over. */
internal object GtRidesAndroid {

    init {
        Shared.env = GtRideEnv(
            // the same handles 2.7e used, as androidx connections (GtAppDb: the same SQLiteDatabase, typed binds, no second connection)
            spatialDb = { SpatialDbManager.getSpatialDb()?.let { GtAppDb.wrap(it) } },
            dataDb = { SpatialDbManager.getExtensionDb()?.let { GtAppDb.wrap(it) } },
            // 2.7e's nowUtc(), unchanged
            nowUtc = {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(Date())
            },
            today = { java.time.LocalDate.now().toString() },
            epochMsToLocalDate = { ms ->
                java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            },
            newId = { UUID.randomUUID().toString() },
            profile = {
                ConvoyProfileStore.load()?.let { p ->
                    RideMe(p.userId, p.firstName, p.lastName, p.callsign, p.vehicleType, p.team, p.email)
                }
            },
            networks = object : RideNetworks {
                override fun load(configId: String): RideNet? = ConvoyNetworkStore.load(configId)?.let { Net(it) }
                override fun create(owner: RideNetOwner, ownerId: String, name: String): RideNet? =
                    ConvoyNetworkStore.create(when (owner) {
                        RideNetOwner.RIDE -> OwnerType.RIDE
                        RideNetOwner.LEADER -> OwnerType.LEADER
                    }, ownerId, name)?.let { Net(it) }
                override fun deleteUnused(configId: String) { ConvoyNetworkStore.deleteUnused(configId) }
            },
            defaultChannel = com.grouptrack.core.NetworkGenerator.DEFAULT_CHANNEL,
            spatial = object : RideSpatial {
                override fun routeGpx(routeId: String): Pair<String?, String?>? =
                    SpatialDbManager.buildRouteGpxById(routeId)?.let { Pair(it.first, it.second) }
                override fun routeGeomHash(routeId: String): String? = SpatialDbManager.routeGeomHash(routeId)
                override fun routeRecipe(routeId: String): String? = SpatialDbManager.routeRecipe(routeId)?.toString()
                override fun waypointGpx(waypointId: String): String? = SpatialDbManager.buildWaypointGpxById(waypointId)?.second
                override fun routeNotes(routeId: String): String? = SpatialDbManager.readRouteNotes(routeId)?.toString()
            },
            // 2.7e stored r.json.toString(2) / j.toString(2): org.json writes the file, so the bytes are 2.7e's (indent, numbers, "\/").
            packageText = { json -> org.json.JSONObject(json).toString(2) },
        )
    }

    /** Touch to make sure the environment is set (object initialisation runs once, before any other thread sees it). */
    fun ready() {}

    /** STEP14: java.io.File calls exactly as 2.7e made them, for one Context. */
    class Files(private val context: Context) : RideFiles {
        override fun ridesDir(): String = GroupTrackStorage.dir("rides", context).path
        override fun stagingDir(): String = java.io.File(context.filesDir, "gpx_staging").path
        override fun child(dir: String, name: String): String = java.io.File(dir, name).path
        override fun sibling(path: String, name: String): String = java.io.File(java.io.File(path).parentFile, name).path
        override fun name(path: String): String = java.io.File(path).name
        override fun list(dir: String): List<String>? = java.io.File(dir).listFiles()?.map { it.name }
        override fun isFile(path: String): Boolean = java.io.File(path).isFile
        override fun exists(path: String): Boolean = java.io.File(path).exists()
        override fun delete(path: String): Boolean = java.io.File(path).delete()
        override fun mkdirs(path: String): Boolean = java.io.File(path).mkdirs()
        override fun readText(path: String): String = java.io.File(path).readText()
        override fun writeText(path: String, text: String) { java.io.File(path).writeText(text) }
        override fun rename(from: String, to: String): Boolean = java.io.File(from).renameTo(java.io.File(to))
        override fun length(path: String): Long = java.io.File(path).length()
        override fun readAsset(name: String): String = context.assets.open(name).bufferedReader().use { it.readText() }
    }

    /** STEP14: a NetworkConfig as the shared ride model reads it. Each j* value is the JSON text org.json writes for that field
     *  (2.7e's `.put(k, cfg.field)`), whatever its Kotlin type; [cfg] is handed back by createRideNetwork. */
    class Net(val cfg: com.grouptrack.core.NetworkConfig) : RideNet {
        override val configId: String? get() = cfg.configId
        override val displayName: String? get() = cfg.displayName
        override val jConfigId: String? get() = orgText(cfg.configId)
        override val jBaseVersion: String? get() = orgText(cfg.baseVersion)
        override val jOwnerType: String? get() = orgText(cfg.ownerType.db)
        override val jDisplayName: String? get() = orgText(cfg.displayName)
        override val jRegion: String? get() = orgText(cfg.region)
        override val jModemPreset: String? get() = orgText(cfg.modemPreset)
        override val jHopLimit: String? get() = orgText(cfg.hopLimit)
        override val jTxPower: String? get() = orgText(cfg.txPower)
        override val jFrequencySlot: String? get() = orgText(cfg.frequencySlot)
        override val jRole: String? get() = orgText(cfg.role)
        override val jPsk: String? get() = orgText(cfg.psk)
        override val jNetworkSsid: String? get() = orgText(cfg.networkSsid)
        override val jNetworkPassword: String? get() = orgText(cfg.networkPassword)
    }

    /** STEP14: the JSON text org.json writes for a value put into a JSONObject (`{"v":<this>}`), or null for null (org.json's put(k, null)
     *  removes the key -- the shared code does the same). org.json's own checks run here (NaN / Infinity throw JSONException, as 2.7e's
     *  put did). */
    private fun orgText(v: Any?): String? =
        if (v == null) null else org.json.JSONObject().put("v", v).toString().let { it.substring(5, it.length - 1) }
}
