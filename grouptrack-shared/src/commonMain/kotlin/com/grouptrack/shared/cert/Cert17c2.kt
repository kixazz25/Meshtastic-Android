package com.grouptrack.shared.cert

import com.grouptrack.shared.settings.GtSettingsStore
import com.grouptrack.shared.user.GtUser
import com.grouptrack.shared.user.GtUserStore
import com.grouptrack.shared.user.GtUserType
import okio.FileSystem
import okio.Path

/**
 * STEP17G2C2-2026-11-12 ⚠ TEST-ONLY (CODE RULE 3 -- remove with the certs before 2.8 is released): the G2c-2 cert script, ONE copy for
 * every side. The Droid runs it on OLD (the generated 2.7e copy) and on NEW (GtUserStore); the iPad runs it on NEW. Each case puts a
 * users file (or none) in the side's own cert folder and empties the side's own cert prefs; nothing touches the rider's file or prefs.
 * The rider's own convoy_users.json, when the Droid has one, is only READ (by the Droid) and handed in as one more case.
 */
object Cert17c2 {
    /** a user as both sides show it -- the same text for OLD and NEW */
    data class U(val id: String, val type: String, val first: String, val last: String, val email: String, val cell: String, val vehicle: String,
                 val created: String, val expiry: String, val devices: List<String>, val fullName: String) {
        fun text() = listOf(id, type, first, last, email, cell, vehicle, created, expiry, devices.joinToString(","), fullName).joinToString("|")
    }
    /** one side's operations, 2.7e's ConvoyUserStore API one for one */
    interface Side {
        fun reset(fileText: String?)              // the side's cert file = this text (null = no file); the side's cert prefs emptied
        fun fileText(): String?
        fun prefActive(): String?
        fun loadAll(): List<U>
        fun saveAllAsLoaded()                     // saveAll(loadAll()) -- the round trip
        fun saveUpdated(id: String, vehicle: String)   // save(the loaded user, vehicle changed) -- update in place
        fun create(type: String, first: String, last: String, email: String, cell: String, vehicle: String, today: String, newId: String): U
        fun saveCreated()                         // save(the last created user) -- add
        fun getActive(): U?
        fun setActive(id: String)
        fun isEnrolled(): Boolean
        fun addDevice(hw: String)
        fun resolvePairing(hw: String): U?
        fun isExpired(expiry: String, today: String): String   // "true" / "false" / "EXC"
        fun cleanup()
    }
    private const val Q = "\""
    val CASES: List<Pair<String, String?>> = listOf(
        "c00-none" to null,
        "c01-empty" to "[]",
        "c02-rider" to """[{"userId":"u-1","userType":"RIDER","firstName":"Fred","lastName":"K","email":"fred@example.com","cellPhone":"555-0100","vehicleType":"UTV","createdDate":"2025-11-12","expirationDate":"2026-11-12","knownDevices":["!a1b2c3d4"]}]""",
        "c03-two-escapes" to """[{"userId":"u-1","userType":"ORGANIZER","firstName":"José \"Joe\"","lastName":"O'Neil\tTab","email":"fred\/test@example.com","cellPhone":"+1 (555) 0100","vehicleType":"Can-Am \\ X3","createdDate":"2025-01-01","expirationDate":"2026-01-01","knownDevices":[]},""" +
            """{"userId":"u-2","userType":"RIDER","firstName":"Ann","lastName":"Lee","email":"ann@example.com","cellPhone":"5550101","vehicleType":"ATV","createdDate":"2026-02-29","expirationDate":"2027-02-28","knownDevices":["!e5f6a7b8","!00000001"]}]""",
        "c04-coerced" to """[{"userId":"u-4","userType":"RIDER","firstName":true,"lastName":null,"email":"x@example.com","cellPhone":5551234,"vehicleType":1.50,"createdDate":"2026-01-01","expirationDate":1e3,"knownDevices":[1,null,"x",{"a":1},[2]]}]""",
        "c05-no-devices" to """[{"userId":"u-5","userType":"RIDER","firstName":"A","lastName":"B","email":"a@example.com","cellPhone":"1","vehicleType":"UTV","createdDate":"2026-01-01","expirationDate":"2027-01-01"}]""",
        "c06-devices-not-array" to """[{"userId":"u-6","userType":"RIDER","firstName":"A","lastName":"B","email":"a@example.com","cellPhone":"1","vehicleType":"UTV","createdDate":"2026-01-01","expirationDate":"2027-01-01","knownDevices":"!a1b2c3d4"}]""",
        "c07-bad-type" to """[{"userId":"u-7","userType":"ADMIN","firstName":"A","lastName":"B","email":"a@example.com","cellPhone":"1","vehicleType":"UTV","createdDate":"2026-01-01","expirationDate":"2027-01-01","knownDevices":[]}]""",
        "c08-missing-email" to """[{"userId":"u-8","userType":"RIDER","firstName":"A","lastName":"B","cellPhone":"1","vehicleType":"UTV","createdDate":"2026-01-01","expirationDate":"2027-01-01","knownDevices":[]}]""",
        "c09-object" to "{}",
        "c10-broken" to "[{",
        "c11-pretty" to "[\n  {\n    ${Q}userId$Q: ${Q}u-11$Q,\n    ${Q}userType$Q: ${Q}RIDER$Q,\n    ${Q}firstName$Q: ${Q}P$Q,\n    ${Q}lastName$Q: ${Q}Q$Q,\n    ${Q}email$Q: ${Q}p@example.com$Q,\n    ${Q}cellPhone$Q: ${Q}2$Q,\n    ${Q}vehicleType$Q: ${Q}SxS$Q,\n    ${Q}createdDate$Q: ${Q}2026-03-01$Q,\n    ${Q}expirationDate$Q: ${Q}2027-03-01$Q,\n    ${Q}knownDevices$Q: [ ${Q}!0a0b0c0d$Q ]\n  }\n]\n",
        "c12-element-not-object" to """[{"userId":"u-1","userType":"RIDER","firstName":"A","lastName":"B","email":"a@example.com","cellPhone":"1","vehicleType":"UTV","createdDate":"2026-01-01","expirationDate":"2027-01-01","knownDevices":[]},5]""",
    )

    /** runs every case (+ the rider's own file as "c99-rider-own" when given) and the date checks; emit(key, value) */
    fun run(side: Side, riderOwn: String?, emit: (String, String) -> Unit) {
        val cases = if (riderOwn != null) CASES + ("c99-rider-own" to riderOwn) else CASES
        fun list(l: List<U>) = if (l.isEmpty()) "[]" else l.joinToString(" ; ") { it.text() }
        for ((c, text) in cases) {
            side.reset(text)
            val l = side.loadAll()
            emit("$c|loadAll", list(l))
            emit("$c|active.none", side.getActive()?.text() ?: "null")
            emit("$c|enrolled.none", "${side.isEnrolled()}")
            side.addDevice("!hw000001"); emit("$c|addDevice.noActive|file", side.fileText() ?: "NOFILE")
            emit("$c|resolve.noActive", side.resolvePairing("!hw000001")?.text() ?: "null")
            if (l.isNotEmpty()) {
                side.setActive(l[0].id); emit("$c|pref", side.prefActive() ?: "null")
                emit("$c|active", side.getActive()?.text() ?: "null"); emit("$c|enrolled", "${side.isEnrolled()}")
                side.saveAllAsLoaded(); emit("$c|saveAll|file", side.fileText() ?: "NOFILE")
                side.addDevice("!hw000001"); side.addDevice("!hw000001"); emit("$c|addDevice|file", side.fileText() ?: "NOFILE")
                emit("$c|resolve.known", side.resolvePairing("!hw000001")?.text() ?: "null")
                emit("$c|resolve.new", side.resolvePairing("!hw000002")?.text() ?: "null"); emit("$c|resolve.new|file", side.fileText() ?: "NOFILE")
                side.saveUpdated(l[0].id, "UTV2"); emit("$c|saveUpdated|file", side.fileText() ?: "NOFILE")
                for (u in l) emit("$c|isExpired(${u.id})@2026-11-12", side.isExpired(u.expiry, "2026-11-12"))
            }
            emit("$c|create", side.create("RIDER", "  New ", " Rider  ", " new@example.com ", " 555-0199 ", " UTV ", "2026-11-12", "cert-$c").text())
            side.saveCreated(); emit("$c|saveCreated|file", side.fileText() ?: "NOFILE")
            side.setActive("cert-$c"); emit("$c|active.created", side.getActive()?.text() ?: "null")
            side.resolvePairing("!hw000003"); emit("$c|final|file", side.fileText() ?: "NOFILE"); emit("$c|final|pref", side.prefActive() ?: "null")
        }
        side.reset(null)
        for (d in listOf("2028-02-29", "2026-12-31", "2026-01-01", "0999-06-15")) emit("date|create@$d", side.create("ORGANIZER", "O", "P", "o@example.com", "1", "X", d, "id-$d").text())
        for ((e, t) in listOf("2026-11-12" to "2026-11-12", "2026-11-11" to "2026-11-12", "2026-11-13" to "2026-11-12", "2027-02-28" to "2028-02-29",
                "2026-02-30" to "2026-01-01", "26-01-01" to "2026-01-01", "" to "2026-01-01", "1000.0" to "2026-01-01", "2026-13-01" to "2026-01-01",
                "2024-02-29" to "2024-03-01", "2025-02-29" to "2025-01-01"))
            emit("date|isExpired($e)@$t", side.isExpired(e, t))
        side.cleanup()
    }

    /** NEW on the shared code (the Droid with GtSettingsAndroid, the iPad with an in-memory store) */
    class NewSide(private val fs: FileSystem, private val dir: Path, private val prefs: GtSettingsStore) : Side {
        private val file = dir / GtUserStore.FILE_NAME
        private val store = GtUserStore(fs, file, prefs)
        private var created: GtUser? = null
        private fun u(g: GtUser) = U(g.userId, g.userType.name, g.firstName, g.lastName, g.email, g.cellPhone, g.vehicleType, g.createdDate,
            g.expirationDate, g.knownDevices.toList(), g.fullName)
        override fun reset(fileText: String?) {
            if (fs.exists(dir)) fs.deleteRecursively(dir)
            fs.createDirectories(dir); if (fileText != null) fs.write(file) { writeUtf8(fileText) }
            prefs.edit { clear() }; created = null
        }
        override fun fileText(): String? = if (fs.exists(file)) fs.read(file) { readUtf8() } else null
        override fun prefActive(): String? = prefs.getString(GtUserStore.ACTIVE_KEY, null)
        override fun loadAll() = store.loadAll().map { u(it) }
        override fun saveAllAsLoaded() = store.saveAll(store.loadAll())
        override fun saveUpdated(id: String, vehicle: String) { store.loadAll().firstOrNull { it.userId == id }?.let { store.save(it.copy(vehicleType = vehicle)) } }
        override fun create(type: String, first: String, last: String, email: String, cell: String, vehicle: String, today: String, newId: String): U =
            GtUser.create(GtUserType.valueOf(type), first, last, email, cell, vehicle, today, newId).also { created = it }.let { u(it) }
        override fun saveCreated() { created?.let { store.save(it) } }
        override fun getActive() = store.getActiveUser()?.let { u(it) }
        override fun setActive(id: String) = store.setActiveUser(id)
        override fun isEnrolled() = store.isEnrolled()
        override fun addDevice(hw: String) = store.addDeviceToActiveUser(hw)
        override fun resolvePairing(hw: String) = store.resolvePairing(hw)?.let { u(it) }
        override fun isExpired(expiry: String, today: String): String =
            try { "${GtUser("x", GtUserType.RIDER, "", "", "", "", "", "", expiry).isExpiredOn(today)}" } catch (e: Exception) { "EXC" }
        override fun cleanup() { if (fs.exists(dir)) fs.deleteRecursively(dir); prefs.edit { clear() } }
    }
}
