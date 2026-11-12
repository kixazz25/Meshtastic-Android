package com.grouptrack.shared.cert

import com.grouptrack.shared.config.GtEventConfig
import com.grouptrack.shared.config.GtEventStore
import com.grouptrack.shared.config.GtMasterConfig
import com.grouptrack.shared.config.GtMasterConfigStore
import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.user.GtUser
import com.grouptrack.shared.user.GtUserType
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path

/**
 * STEP17G2D-2026-11-12 ⚠ TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released): the G2d cert script, ONE copy for
 * every side. The Droid runs it on OLD (the generated 2.7e copies) and on NEW (GtMasterConfigStore / GtEventStore); the iPad on NEW.
 * Each case uses the side's own cert folder; the "asset" is text handed in (the app's real master_config.json, from the Droid).
 * The rider's own master_config.json and convoy_events/ files are only READ (by the Droid) and handed in as more cases.
 * Every value is shown as TEXT the side wrote itself (toJson().toString() / toString(2)), so the JSON writer is certified too.
 */
object Cert17d {
    interface Side {
        fun reset()                                  // the side's cert folder emptied
        fun putDevice(text: String?)                 // the device master_config.json (null = none)
        fun setAsset(text: String?)                  // the asset (null = assets.open throws)
        fun deviceText(): String?
        fun masterLoad(): String                     // load() -> toJson().toString(), or "null"
        fun masterExists(): String
        fun masterPretty(): String                   // load() -> toJson().toString(2), or "null"
        fun eventParse(text: String): String         // fromJson(JSONObject(text)).toJson().toString(2), or "EXC"
        fun eventPut(name: String, text: String)     // a raw file in the events folder ("dir:x.json" = a folder)
        fun eventCreate(eventName: String, eventDate: String, eventDescription: String, channelName: String, today: String, newId: String): String
        fun eventSaveCreated(): String               // save(the last created) -> the file name written
        fun eventFiles(): String                     // the events folder's names, sorted, with each file's text
        fun eventLoadAll(): String                   // loadAll() -> each toJson().toString(), SORTED (the order is the platform's)
        fun cleanup()
    }
    /** the organizer every createFromMaster uses */
    val ORG = GtUser("org-cert-1", GtUserType.ORGANIZER, "Fred", "K", "fred@example.com", "555-0100", "UTV", "2025-11-12", "2026-11-12")

    private fun withKeys(base: String, extra: String): String {          // base = an object's text; adds keys at the end
        val i = base.lastIndexOf('}'); return base.substring(0, i).trimEnd() + "," + extra + "\n}"
    }
    private const val EV_FULL = """{"eventId":"ev-1","eventName":"Kanab Ride","eventDate":"2026-11-14","eventDescription":"Coral Pink loop","organizerUserId":"org-1","organizerFirstName":"Fred","organizerLastName":"K","organizerEmail":"fred\/k@example.com","organizerPhone":"555-0100","organizerVehicleType":"UTV","hardwareId":"!a1b2c3d4","deviceId":"dev-1","hardwareModel":"tracker-t1000-e","firmwareVersion":"2.6.11","channelName":"GroupABC123","channelPsk":"MEJHR0hQQVliRFpVbDAwSW1ocUF5d2Y2S0lobExCaFo=","loraRegion":"US","loraModemPreset":"LONG_FAST","loraBandwidth":250,"loraSpreadFactor":11,"loraCodingRate":8,"loraHopLimit":3,"loraTxPower":27,"createdDate":"2026-11-12","expirationDate":"2026-12-14","mapAreaNorth":37.123456789,"mapAreaSouth":-112.5,"mapAreaEast":1e-5,"mapAreaWest":0,"mapZoomDepth":14}"""
    private const val EV_MIN = """{"eventId":"ev-2","eventName":"Min","eventDate":"x","organizerUserId":"o","organizerFirstName":"a","organizerLastName":"b","organizerEmail":"c","organizerPhone":"d","hardwareId":"h","channelName":"n","channelPsk":"p","createdDate":"2026-01-01","expirationDate":"2026-02-01"}"""
    private const val EV_COERCE = """{"eventId":1,"eventName":true,"eventDate":null,"eventDescription":null,"organizerUserId":"o","organizerFirstName":"a","organizerLastName":"b","organizerEmail":"c","organizerPhone":5550100,"hardwareId":"h","channelName":"n","channelPsk":"p","createdDate":"2026-01-01","expirationDate":"2026-02-01","loraBandwidth":"125","loraSpreadFactor":7.9,"loraHopLimit":"x","loraTxPower":null,"mapAreaNorth":"45.5","mapAreaSouth":"abc","mapAreaEast":true,"mapZoomDepth":14.9,"deviceId":{"a":[1,2.50]}}"""
    private const val EV_MISSING = """{"eventId":"ev-4","eventName":"No psk","eventDate":"2026-01-01","organizerUserId":"o","organizerFirstName":"a","organizerLastName":"b","organizerEmail":"c","organizerPhone":"d","hardwareId":"h","channelName":"n","createdDate":"2026-01-01","expirationDate":"2026-02-01"}"""

    fun run(side: Side, asset: String, riderMaster: String?, riderEvents: List<Pair<String, String>>, emit: (String, String) -> Unit) {
        val v2 = withKeys(asset, "\"configVersion\": 2, \"loraHopLimit\": 5")
        val coerced = withKeys(asset, "\"configVersion\": \"3\", \"loraBandwidth\": \"125\", \"isManaged\": \"TRUE\", \"loraTxPower\": 27.9, \"gpsMode\": null, \"mqttAddress\": 123, \"positionFlags\": \"8.5e2\", \"serialBaud\": true, \"fixedPosition\": 1")
        val masters: List<Triple<String, String?, String?>> = listOf(
            Triple("m00-no-device", null, asset), Triple("m01-device-is-asset", asset, asset),
            Triple("m02-device-v2", v2, asset), Triple("m03-device-coerced", coerced, asset),
            Triple("m04-device-broken", "{", asset), Triple("m05-device-array", "[]", asset),
            Triple("m06-no-asset", null, null), Triple("m07-asset-broken", null, "{\"x\":"),
            Triple("m08-v2-no-asset", v2, null), Triple("m09-version-text", withKeys(asset, "\"configVersion\": \"2\""), asset),
            Triple("m10-empty-object-v2", "{\"configVersion\":2}", asset)) +
            (if (riderMaster != null) listOf(Triple("m99-rider-own", riderMaster, asset)) else emptyList())
        for ((c, dev, ast) in masters) {
            side.reset(); side.putDevice(dev); side.setAsset(ast)
            emit("$c|exists", side.masterExists())
            emit("$c|load", side.masterLoad())
            emit("$c|device-after", side.deviceText() ?: "NOFILE")
            emit("$c|load2.pretty", side.masterPretty())
        }
        val events = listOf("e00-full" to EV_FULL, "e01-min" to EV_MIN, "e02-coerced" to EV_COERCE, "e03-missing-psk" to EV_MISSING,
            "e04-array" to "[]", "e05-broken" to "{", "e06-pretty" to GtOrgJson.pretty(GtOrgJson.parse(EV_FULL), 2)) +
            riderEvents.mapIndexed { i, (n, t) -> "e9$i-rider-own($n)" to t }
        for ((c, t) in events) emit("$c|parse", side.eventParse(t))
        // the store: created events saved, plus raw files, then loadAll
        side.reset(); side.setAsset(asset)
        val made = listOf(
            Triple("Kanab Ride #1", "2026-11-14", "ev-a"), Triple("José's 🚙 run", "11/12/2026 9:00", "ev-b"),
            Triple("  Leap  ", "2028-02-29", "ev-c"), Triple("Year end", "2026-12-15", "ev-d"), Triple("Bad date", "2026-02-30", "ev-e"))
        for ((n, d, id) in made) {
            emit("create($id)", side.eventCreate(n, d, "  desc $id ", " GroupABC123 ", "2026-11-12", id))
            emit("save($id)|name", side.eventSaveCreated())
        }
        side.eventPut("broken.json", "{"); side.eventPut("notes.txt", EV_MIN); side.eventPut("min.json", EV_MIN); side.eventPut("dir:folder.json", "")
        side.eventPut(".json", EV_MISSING); side.eventPut("upper.JSON", EV_MIN)
        for ((n, t) in riderEvents) side.eventPut("rider-$n", t)
        emit("store|files", side.eventFiles())
        emit("store|loadAll", side.eventLoadAll())
        side.cleanup()
    }

    /** NEW on the shared code; [list] = the platform's folder listing (Android: File.listFiles order; iPad: fs.list) */
    class NewSide(private val fs: FileSystem, private val dir: Path, private val list: (Path) -> List<Path>?) : Side {
        private var asset: String? = null
        private val devFile = dir / "files" / GtMasterConfig.DEVICE_FILE
        private val evDir = dir / "files" / GtEventStore.EVENTS_DIR
        private fun master() = GtMasterConfigStore(fs, devFile) { asset ?: throw IllegalStateException("no asset") }
        private val events = GtEventStore(fs, evDir, list)
        private var created: GtEventConfig? = null
        override fun reset() { if (fs.exists(dir)) fs.deleteRecursively(dir); fs.createDirectories(dir / "files"); created = null }
        override fun putDevice(text: String?) { if (text != null) fs.write(devFile) { writeUtf8(text) } }
        override fun setAsset(text: String?) { asset = text }
        override fun deviceText(): String? = if (fs.exists(devFile)) fs.read(devFile) { readUtf8() } else null
        override fun masterLoad(): String = master().load()?.let { GtOrgJson.compact(it.toJson()) } ?: "null"
        override fun masterExists(): String = "${master().exists()}"
        override fun masterPretty(): String = master().load()?.let { GtOrgJson.pretty(it.toJson(), 2) } ?: "null"
        override fun eventParse(text: String): String = try {
            GtOrgJson.pretty(GtEventConfig.fromJson(GtOrgJson.parse(text) as? JsonObject ?: throw IllegalArgumentException("not an object")).toJson(), 2)
        } catch (e: Exception) { "EXC" }
        override fun eventPut(name: String, text: String) {
            fs.createDirectories(evDir)
            if (name.startsWith("dir:")) fs.createDirectories(evDir / name.removePrefix("dir:")) else fs.write(evDir / name) { writeUtf8(text) }
        }
        override fun eventCreate(eventName: String, eventDate: String, eventDescription: String, channelName: String, today: String, newId: String): String {
            val m = GtMasterConfig.fromJson(GtOrgJson.parse(asset!!) as JsonObject)
            val e = GtEventConfig.createFromMaster(m, ORG, "!a1b2c3d4", "dev-1", eventName, eventDate, eventDescription, channelName, "PSK==", today, newId)
            created = e; return GtOrgJson.compact(e.toJson())
        }
        override fun eventSaveCreated(): String { val e = created!!; events.save(e); return events.fileName(e) }
        override fun eventFiles(): String = if (!fs.exists(evDir)) "NODIR" else fs.list(evDir).sortedBy { it.name }.joinToString("\n") { p ->
            if (fs.metadata(p).isDirectory) "${p.name}/" else "${p.name}=" + fs.read(p) { readUtf8() } }
        override fun eventLoadAll(): String = events.loadAll().map { GtOrgJson.compact(it.toJson()) }.sorted().joinToString("\n")
        override fun cleanup() { if (fs.exists(dir)) fs.deleteRecursively(dir) }
    }
}
