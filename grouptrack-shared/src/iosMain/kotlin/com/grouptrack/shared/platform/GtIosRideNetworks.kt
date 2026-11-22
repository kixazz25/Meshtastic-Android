package com.grouptrack.shared.platform

import androidx.sqlite.SQLiteConnection
import com.grouptrack.shared.imports.GtClock
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.comm.GtRadioDoc
import com.grouptrack.shared.net.GtMeshDefaults
import com.grouptrack.shared.net.GtNetworkConfig
import com.grouptrack.shared.net.GtNetworkGenerator
import com.grouptrack.shared.net.GtNetworkStore
import com.grouptrack.shared.net.GtOwnerType
import com.grouptrack.shared.net.GtRandom
import com.grouptrack.shared.ride.RideNet
import com.grouptrack.shared.ride.RideNetOwner
import com.grouptrack.shared.ride.RideNetworks
import com.grouptrack.shared.text.GtTime
import kotlinx.serialization.json.JsonObject

/**
 * IOSRIDENET-2026-11-17: RideNetworks on the iPad -- three delegations to the SHARED GtNetworkStore,
 * which already has load / create / deleteUnused with matching signatures and is certified OLD = NEW at
 * 803ecd4c0. Android's ConvoyNetworkStore is itself only a front door to the same object
 * (SWITCH17G2C1-2026-11-12), so both platforms now reach one implementation.
 *
 * ⚠ THE TWO FIDELITY TRAPS, both simulated before this file was written
 * (gt_ridenetsim_2026-11-17_v1.py: 35 SAME, 0 DIFF):
 *
 *  1. nowUtc -- 2.7e is SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US) in UTC: SECONDS, with a
 *     literal Z. ⛔ IosClock.nowInstant() is GtTime.instantTextMs and carries MILLISECONDS, so wiring
 *     the obvious clock member here would write "2026-11-17T15:50:33.456Z" into a column Android fills
 *     with "2026-11-17T15:50:33Z". [GtTime.formatUtcSeconds] is the seconds-only form and its own header
 *     says "as java.text.SimpleDateFormat". Verified across 11 epoch values including pre-epoch and
 *     sub-second truncation.
 *  2. the j* fields -- Android's orgText(v) is org.json JSONObject().put("v", v).toString() with the
 *     wrapper sliced off, i.e. ONE VALUE encoded by org.json. So a String arrives QUOTED and org.json
 *     escapes a FORWARD SLASH as \/, which no other writer does. GtOrgJson.quote is JSONStringer.string()
 *     ported, and compact() writes the value: verified over 18 strings (slash, quote, backslash, the five
 *     named escapes, control chars, accents, CJK, emoji) and 5 Ints.
 */
class IosRideNetworks(
    /** The extension database handle. Android passes SpatialDbManager.getExtensionDb(); null = no db,
     *  which GtNetworkStore already treats as "refuse, do not pretend" on each member. */
    private val db: () -> SQLiteConnection?,
    private val clock: GtClock = IosClock,
    rng: GtRandom = IosRandom(),
    /** Read at EACH create, because 2.7e reads MeshDefaults inside generate() and loadFrom() runs just
     *  before a ride is saved -- not once at startup. */
    private val mesh: () -> GtMeshDefaults = { IosMeshDefaults.now() },
) : RideNetworks {

    private val log = gtLogger("IosRideNetworks")   // NOCONFIG-2026-11-22: the refusal must be readable

    private val store = GtNetworkStore(
        db,
        GtNetworkGenerator(rng),
        mesh,
        { GtTime.formatUtcSeconds(clock.epochMs()) },   // ⚠ trap 1 -- NOT clock.nowInstant()
    )

    override fun load(configId: String): RideNet? = store.load(configId)?.let { IosRideNet(it) }

    /**
     * NOCONFIG-2026-11-22: the mesh values are read at EACH create (2.7e reads them inside generate()).
     * If they cannot be read, this REFUSES and names the reason -- it does not create a network from
     * guessed values, because that network is what a later apply writes to a radio.
     * CODE RULE 1: null = refused, which is this contract's existing meaning (the no-database case,
     * already certified "refuse, never pretend").
     */
    override fun create(owner: RideNetOwner, ownerId: String, name: String): RideNet? = try {
        createOrThrow(owner, ownerId, name)
    } catch (e: Exception) {
        log.e {
            "NOCONFIG-2026-11-22: ride network REFUSED -- the mesh configuration could not be read: " +
                e.message + " (nothing was written)"
        }
        null
    }

    private fun createOrThrow(owner: RideNetOwner, ownerId: String, name: String): RideNet? =
        store.create(
            when (owner) {
                RideNetOwner.RIDE -> GtOwnerType.RIDE
                RideNetOwner.LEADER -> GtOwnerType.LEADER
            },
            ownerId, name,
        )?.let { IosRideNet(it) }

    override fun deleteUnused(configId: String) { store.deleteUnused(configId) }
}

/**
 * IOSRIDENET-2026-11-17: GtNetworkConfig seen as a RideNet. The mirror of Android's
 * GtRidesAndroid.Net, field for field.
 *
 * ⚠ EVERY j* FIELD IS org.json TEXT, not a plain value -- see trap 2 on [IosRideNetworks]. jRegion for
 * "US" is the five characters "US" WITH QUOTES; jHopLimit for 3 is "3" with none. They land in a ride
 * package that the Droid and the iPad must write identically.
 */
class IosRideNet(val cfg: GtNetworkConfig) : RideNet {
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

    private fun orgText(v: String?): String? = if (v == null) null else GtOrgJson.compact(GtOrgJson.str(v))
    private fun orgText(v: Int): String = GtOrgJson.compact(GtOrgJson.num(v))
}

/**
 * IOSRIDENET-2026-11-17: 2.7e NetworkGenerator.MeshDefaults.loadFrom + meshNow, on the bundle.
 *
 * ⛔ NOCONFIG-2026-11-22: THERE ARE NO FALLBACKS ANY MORE. 2.7e read these with getString/getInt and
 * its loadFrom caught the throw and kept hardcoded literals -- five of which equalled the file, so a
 * total read failure looked like success. That is how hopLimit 3 reached every ride network the iPad
 * created. [stated] Fred: "no hard coded values, no values no write to the radio." The read now throws
 * naming the field and [IosRideNetworks.create] refuses. ⚠ Android's loadFrom still has the old catch;
 * by this rule that is the same defect, FLAGGED for 2.8a rather than changed mid-loop.
 * The log lines and the tag are 2.7e's, so the ONEDEFAULT line reads the same in both logs.
 */
object IosMeshDefaults {
    private val log = gtLogger("NetworkGenerator")

    /**
     * NOCONFIG-2026-11-22: ⛔ NO CATCH, NO FALLBACK. This THROWS, naming the field that failed
     * (GtRadioDoc's readers each name their own), and the caller refuses the operation. Hiding the
     * failure is what let hopLimit 3 reach every ride network the iPad created.
     */
    fun now(): GtMeshDefaults = run {
        // MESHFIELDS-2026-11-22 -- the field names are GtRadioDoc's, not this file's. The four keys
        // that used to be named here (modem_preset / hop_limit / tx_power / frequency_slot) were
        // GtNetworkStore's DB COLUMNS, not keys in the file.
        val d = GtRadioDoc.meshDefaults(iosBundleAsset("grouptrack_default.json"))
        log.i {
            "ONEDEFAULT: mesh values from grouptrack_default.json -- " + d.region + " " + d.modemPreset +
                " hop " + d.hopLimit + " tx " + d.txPower + " slot " + d.frequencySlot + " " + d.role
        }
        d
    }
}
