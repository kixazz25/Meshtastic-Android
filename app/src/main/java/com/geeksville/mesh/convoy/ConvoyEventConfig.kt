package com.geeksville.mesh.convoy

import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * ConvoyEventConfig — V2 event/ride configuration
 *
 * Derived from ConvoyMasterConfig — LoRa settings come from master.
 * Only channel name and PSK change per event.
 * Hardware ID of organizer's active radio is the primary key.
 *
 * No manual radio config. No import. Master is the single source.
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
    fun toJson(): JSONObject = JSONObject().apply {
        put("eventId",              eventId)
        put("eventName",            eventName)
        put("eventDate",            eventDate)
        put("eventDescription",     eventDescription)
        put("organizerUserId",      organizerUserId)
        put("organizerFirstName",   organizerFirstName)
        put("organizerLastName",    organizerLastName)
        put("organizerEmail",       organizerEmail)
        put("organizerPhone",       organizerPhone)
        put("organizerVehicleType", organizerVehicleType)
        put("hardwareId",           hardwareId)
        put("deviceId",             deviceId)
        put("hardwareModel",        hardwareModel)
        put("firmwareVersion",      firmwareVersion)
        put("channelName",          channelName)
        put("channelPsk",           channelPsk)
        put("loraRegion",           loraRegion)
        put("loraModemPreset",      loraModemPreset)
        put("loraBandwidth",        loraBandwidth)
        put("loraSpreadFactor",     loraSpreadFactor)
        put("loraCodingRate",       loraCodingRate)
        put("loraHopLimit",         loraHopLimit)
        put("loraTxPower",          loraTxPower)
        put("createdDate",          createdDate)
        put("expirationDate",       expirationDate)
        put("mapAreaNorth",         mapAreaNorth)
        put("mapAreaSouth",         mapAreaSouth)
        put("mapAreaEast",          mapAreaEast)
        put("mapAreaWest",          mapAreaWest)
        put("mapZoomDepth",         mapZoomDepth)
    }

    companion object {
        fun fromJson(obj: JSONObject) = ConvoyEventConfig(
            eventId             = obj.getString("eventId"),
            eventName           = obj.getString("eventName"),
            eventDate           = obj.getString("eventDate"),
            eventDescription    = obj.optString("eventDescription", ""),
            organizerUserId     = obj.getString("organizerUserId"),
            organizerFirstName  = obj.getString("organizerFirstName"),
            organizerLastName   = obj.getString("organizerLastName"),
            organizerEmail      = obj.getString("organizerEmail"),
            organizerPhone      = obj.getString("organizerPhone"),
            organizerVehicleType= obj.optString("organizerVehicleType", ""),
            hardwareId          = obj.getString("hardwareId"),
            deviceId            = obj.optString("deviceId", ""),
            hardwareModel       = obj.optString("hardwareModel", ""),
            firmwareVersion     = obj.optString("firmwareVersion", ""),
            channelName         = obj.getString("channelName"),
            channelPsk          = obj.getString("channelPsk"),
            loraRegion          = obj.optString("loraRegion", "US"),
            loraModemPreset     = obj.optString("loraModemPreset", "LONG_FAST"),
            loraBandwidth       = obj.optInt("loraBandwidth", 250),
            loraSpreadFactor    = obj.optInt("loraSpreadFactor", 11),
            loraCodingRate      = obj.optInt("loraCodingRate", 8),
            loraHopLimit        = obj.optInt("loraHopLimit", 3),
            loraTxPower         = obj.optInt("loraTxPower", 27),
            createdDate         = obj.getString("createdDate"),
            expirationDate      = obj.getString("expirationDate"),
            mapAreaNorth        = obj.optDouble("mapAreaNorth", 0.0),
            mapAreaSouth        = obj.optDouble("mapAreaSouth", 0.0),
            mapAreaEast         = obj.optDouble("mapAreaEast", 0.0),
            mapAreaWest         = obj.optDouble("mapAreaWest", 0.0),
            mapZoomDepth        = obj.optInt("mapZoomDepth", 14)
        )

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
        ): ConvoyEventConfig {
            val fmt    = DateTimeFormatter.ISO_LOCAL_DATE
            val today  = LocalDate.now()
            val evDate = try { LocalDate.parse(eventDate, fmt) } catch (e: Exception) { today }
            return ConvoyEventConfig(
                eventId             = UUID.randomUUID().toString(),
                eventName           = eventName.trim(),
                eventDate           = eventDate,
                eventDescription    = eventDescription.trim(),
                organizerUserId     = organizer.userId,
                organizerFirstName  = organizer.firstName,
                organizerLastName   = organizer.lastName,
                organizerEmail      = organizer.email,
                organizerPhone      = organizer.cellPhone,
                organizerVehicleType= organizer.vehicleType,
                hardwareId          = hardwareId,
                deviceId            = deviceId,
                hardwareModel       = master.hardwareModel,
                firmwareVersion     = master.firmwareVersion,
                channelName         = channelName.trim(),
                channelPsk          = channelPsk,
                loraRegion          = master.loraRegion,
                loraModemPreset     = master.loraModemPreset,
                loraBandwidth       = master.loraBandwidth,
                loraSpreadFactor    = master.loraSpreadFactor,
                loraCodingRate      = master.loraCodingRate,
                loraHopLimit        = master.loraHopLimit,
                loraTxPower         = master.loraTxPower,
                createdDate         = today.format(fmt),
                expirationDate      = evDate.plusDays(30).format(fmt)
            )
        }
    }
}

/**
 * ConvoyEventStore — local JSON file storage for event configs
 * Directory: C:/ConvoyProto/events/
 */
object ConvoyEventStore {
    private const val EVENTS_DIR = "C:/ConvoyProto/events"

    fun eventsDir(): java.io.File = java.io.File(EVENTS_DIR).also { it.mkdirs() }

    fun save(event: ConvoyEventConfig) {
        val safeName = event.eventName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val fileName = "${event.eventId}_${safeName}_${event.eventDate}.json"
        java.io.File(eventsDir(), fileName).writeText(event.toJson().toString(2))
    }

    fun loadAll(): List<ConvoyEventConfig> {
        return eventsDir().listFiles { f -> f.extension == "json" }
            ?.mapNotNull {
                try { ConvoyEventConfig.fromJson(org.json.JSONObject(it.readText())) }
                catch (e: Exception) { null }
            } ?: emptyList()
    }
}
