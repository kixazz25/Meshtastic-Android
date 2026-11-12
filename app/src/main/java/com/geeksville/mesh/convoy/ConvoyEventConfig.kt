package com.geeksville.mesh.convoy

import android.content.Context
import com.geeksville.mesh.grouptrack.GtStorageAndroid
import com.grouptrack.shared.config.GtEventConfig
import com.grouptrack.shared.config.GtEventStore
import com.grouptrack.shared.json.GtOrgJson
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * ConvoyEventConfig — V2 event/ride configuration
 *
 * Derived from ConvoyMasterConfig — LoRa settings come from master.
 * Only channel name and PSK change per event.
 * Hardware ID of organizer's active radio is the primary key.
 *
 * No manual radio config. No import. Master is the single source.
 *
 * SWITCH17G2D-2026-11-12 (2.8 step 17, G2d): front doors to the shared GtEventConfig / GtEventStore (certified at 0ed882ae7: Droid 69 SAME, 0 DIFF;
 * iPad = Droid). Every field, name and type kept; the same files in filesDir/convoy_events, the same text, File.listFiles' own order.
 */
data class ConvoyEventConfig(
    val eventId: String,
    val eventName: String,
    val eventDate: String,
    val eventDescription: String,
    // Organizer profile
    val organizerUserId: String,
    val organizerFirstName: String,
    val organizerLastName: String,
    val organizerEmail: String,
    val organizerPhone: String,
    val organizerVehicleType: String,
    // Radio identity — from connected device
    val hardwareId: String,
    val deviceId: String,
    val hardwareModel: String,
    val firmwareVersion: String,
    // Channel config — event-specific
    val channelName: String,
    val channelPsk: String,           // AES-256 PSK base64
    // LoRa config — derived from master, not user-entered
    val loraRegion: String,
    val loraModemPreset: String,
    val loraBandwidth: Int,
    val loraSpreadFactor: Int,
    val loraCodingRate: Int,
    val loraHopLimit: Int,
    val loraTxPower: Int,
    // Dates
    val createdDate: String,
    val expirationDate: String,       // 30 days after event date
    // Map area — placeholder for V3
    val mapAreaNorth: Double = 0.0,
    val mapAreaSouth: Double = 0.0,
    val mapAreaEast: Double = 0.0,
    val mapAreaWest: Double = 0.0,
    val mapZoomDepth: Int = 14
) {
    fun toJson(): JSONObject = JSONObject(GtOrgJson.compact(toShared().toJson()))

    fun toShared(): GtEventConfig = GtEventConfig(eventId, eventName, eventDate, eventDescription, organizerUserId, organizerFirstName, organizerLastName, organizerEmail, organizerPhone, organizerVehicleType, hardwareId, deviceId, hardwareModel, firmwareVersion, channelName, channelPsk, loraRegion, loraModemPreset, loraBandwidth, loraSpreadFactor, loraCodingRate, loraHopLimit, loraTxPower, createdDate, expirationDate, mapAreaNorth, mapAreaSouth, mapAreaEast, mapAreaWest, mapZoomDepth)

    companion object {
        fun fromShared(g: GtEventConfig): ConvoyEventConfig = ConvoyEventConfig(g.eventId, g.eventName, g.eventDate, g.eventDescription, g.organizerUserId, g.organizerFirstName, g.organizerLastName, g.organizerEmail, g.organizerPhone, g.organizerVehicleType, g.hardwareId, g.deviceId, g.hardwareModel, g.firmwareVersion, g.channelName, g.channelPsk, g.loraRegion, g.loraModemPreset, g.loraBandwidth, g.loraSpreadFactor, g.loraCodingRate, g.loraHopLimit, g.loraTxPower, g.createdDate, g.expirationDate, g.mapAreaNorth, g.mapAreaSouth, g.mapAreaEast, g.mapAreaWest, g.mapZoomDepth)

        fun fromJson(obj: JSONObject): ConvoyEventConfig = fromShared(GtEventConfig.fromJson(GtOrgJson.parse(obj.toString()) as JsonObject))

        /**
         * Create an event config derived from master config.
         * LoRa settings come from master — only channel name and PSK are event-specific.
         */
        fun createFromMaster(
            master: ConvoyMasterConfig,
            organizer: ConvoyUser,
            hardwareId: String,
            deviceId: String,
            eventName: String,
            eventDate: String,
            eventDescription: String,
            channelName: String,
            channelPsk: String
        ): ConvoyEventConfig = fromShared(GtEventConfig.createFromMaster(master.toShared(), organizer.toShared(), hardwareId, deviceId,
            eventName, eventDate, eventDescription, channelName, channelPsk, LocalDate.now().toString(), UUID.randomUUID().toString()))
    }
}

/**
 * ConvoyEventStore — local JSON file storage for event configs
 * Directory: filesDir/convoy_events (SWITCH17G2D-2026-11-12: the shared GtEventStore; File.listFiles' order, as 2.7e)
 */
object ConvoyEventStore {
    private const val EVENTS_DIR = "convoy_events"
    fun eventsDir(context: Context): File = File(context.filesDir, EVENTS_DIR).also { it.mkdirs() }
    private fun store(context: Context) = GtEventStore(FileSystem.SYSTEM, GtStorageAndroid.of(context).filesDir() / GtEventStore.EVENTS_DIR) { p ->
        File(p.toString()).listFiles()?.map { it.toOkioPath() }
    }
    fun save(context: Context, event: ConvoyEventConfig) = store(context).save(event.toShared())
    fun loadAll(context: Context): List<ConvoyEventConfig> = store(context).loadAll().map { ConvoyEventConfig.fromShared(it) }
}
