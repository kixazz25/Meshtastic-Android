package com.grouptrack.shared.user

import com.grouptrack.shared.json.GtOrgJson
import com.grouptrack.shared.settings.GtSettingsStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path

/**
 * STEP17G2C2-2026-11-12 (2.8 step 17, G2c-2): the V2 local user (convoy/ConvoyUser.kt: ConvoyUser + ConvoyUserStore), ported AS IS.
 * Changes, and only these:
 *   org.json             -> GtOrgJson (the same text written: key order, no spaces, "/" escaped; getString's values: numbers as org.json
 *                           holds them, JSON null as "null"; optJSONArray = only a real array)
 *   LocalDate.now()      -> [today] given in (ISO yyyy-MM-dd); LocalDate.parse / plusYears -> [GtIsoDate] (STRICT ISO, Feb 29 -> Feb 28)
 *   UUID                 -> [newId] given in
 *   filesDir/convoy_users.json -> [file]; SharedPreferences "convoy_prefs" -> [prefs] (Android: GtSettingsAndroid, the same file and key)
 * ⚠ NOT REPRODUCED (as GtOrgJson): Android's lenient JSON (comments, single quotes, unquoted keys). The file is only ever written by
 *   saveAll below, so it never holds them; a file that did would load as no users on the iPad and as users on 2.7e.
 */
enum class GtUserType { ORGANIZER, RIDER }

/** ISO_LOCAL_DATE, STRICT, years 0000-9999 (LocalDate's own range beyond that needs a sign -- never written by create). */
object GtIsoDate {
    private val RX = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")
    fun leap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
    fun days(y: Int, m: Int) = when (m) { 2 -> if (leap(y)) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
    /** (year, month, day), or IllegalArgumentException as LocalDate.parse throws DateTimeParseException */
    fun parse(s: String): Triple<Int, Int, Int> {
        val m = RX.matchEntire(s) ?: throw IllegalArgumentException("Text '$s' could not be parsed")
        val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
        if (mo !in 1..12 || d !in 1..days(y, mo)) throw IllegalArgumentException("Text '$s' could not be parsed: invalid date")
        return Triple(y, mo, d)
    }
    fun format(y: Int, m: Int, d: Int): String = "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    /** LocalDate.plusYears: the day is clipped to the month's last day (Feb 29 -> Feb 28) */
    fun plusYears(s: String, n: Int): String { val (y, m, d) = parse(s); val ny = y + n; return format(ny, m, minOf(d, days(ny, m))) }
    /** a.isAfter(b) */
    fun isAfter(a: String, b: String): Boolean {
        val (ay, am, ad) = parse(a); val (by, bm, bd) = parse(b)
        return compareValuesBy(Triple(ay, am, ad), Triple(by, bm, bd), { it.first }, { it.second }, { it.third }) > 0
    }
}

data class GtUser(
    val userId: String, val userType: GtUserType, val firstName: String, val lastName: String, val email: String,
    val cellPhone: String, val vehicleType: String, val createdDate: String, val expirationDate: String,
    val knownDevices: MutableList<String> = mutableListOf()
) {
    val fullName: String get() = "$firstName $lastName"
    /** 2.7e isExpired: LocalDate.now().isAfter(LocalDate.parse(expirationDate)) -- the expiry is parsed FIRST, as 2.7e (a bad one throws) */
    fun isExpiredOn(today: String): Boolean { GtIsoDate.parse(expirationDate); return GtIsoDate.isAfter(today, expirationDate) }

    fun toJson(): JsonObject = JsonObject(linkedMapOf(
        "userId" to GtOrgJson.str(userId), "userType" to GtOrgJson.str(userType.name), "firstName" to GtOrgJson.str(firstName),
        "lastName" to GtOrgJson.str(lastName), "email" to GtOrgJson.str(email), "cellPhone" to GtOrgJson.str(cellPhone),
        "vehicleType" to GtOrgJson.str(vehicleType), "createdDate" to GtOrgJson.str(createdDate), "expirationDate" to GtOrgJson.str(expirationDate),
        "knownDevices" to JsonArray(knownDevices.map { GtOrgJson.str(it) })))

    companion object {
        /** JSONObject.getString: a missing key throws; any other value is String.valueOf(it) */
        private fun req(o: JsonObject, k: String): String = GtOrgJson.valueText(o[k]) ?: throw IllegalArgumentException("No value for $k")
        fun fromJson(o: JsonObject): GtUser {
            val devices = mutableListOf<String>()
            val arr = o["knownDevices"] as? JsonArray
            if (arr != null) for (e in arr) devices.add(GtOrgJson.valueText(e) ?: "null")
            return GtUser(userId = req(o, "userId"), userType = GtUserType.valueOf(req(o, "userType")), firstName = req(o, "firstName"),
                lastName = req(o, "lastName"), email = req(o, "email"), cellPhone = req(o, "cellPhone"), vehicleType = req(o, "vehicleType"),
                createdDate = req(o, "createdDate"), expirationDate = req(o, "expirationDate"), knownDevices = devices)
        }
        fun create(userType: GtUserType, firstName: String, lastName: String, email: String, cellPhone: String, vehicleType: String,
                   today: String, newId: String): GtUser {
            GtIsoDate.parse(today)
            return GtUser(userId = newId, userType = userType, firstName = firstName.trim(), lastName = lastName.trim(), email = email.trim(),
                cellPhone = cellPhone.trim(), vehicleType = vehicleType.trim(), createdDate = today, expirationDate = GtIsoDate.plusYears(today, 1))
        }
    }
}

/** 2.7e ConvoyUserStore: the users file and the active user. The pairing rules are 2.7e's (see ConvoyUser.kt). */
class GtUserStore(private val fs: FileSystem, private val file: Path, private val prefs: GtSettingsStore) {
    companion object { const val FILE_NAME = "convoy_users.json"; const val ACTIVE_KEY = "active_user_id"; const val PREFS_NAME = "convoy_prefs" }
    fun loadAll(): List<GtUser> {
        if (!fs.exists(file)) return emptyList()
        return try {
            val arr = GtOrgJson.parse(fs.read(file) { readUtf8() }) as? JsonArray ?: return emptyList()
            arr.map { e: JsonElement -> GtUser.fromJson(e as? JsonObject ?: throw IllegalArgumentException("not an object")) }
        } catch (e: Exception) { emptyList() }
    }
    fun saveAll(users: List<GtUser>) { fs.write(file) { writeUtf8(GtOrgJson.compact(JsonArray(users.map { it.toJson() }))) } }
    fun save(user: GtUser) {
        val users = loadAll().toMutableList()
        val idx = users.indexOfFirst { it.userId == user.userId }
        if (idx >= 0) users[idx] = user else users.add(user)
        saveAll(users)
    }
    fun getActiveUser(): GtUser? { val active = prefs.getString(ACTIVE_KEY, null) ?: return null; return loadAll().firstOrNull { it.userId == active } }
    fun setActiveUser(userId: String) { prefs.edit { putString(ACTIVE_KEY, userId) } }
    fun isEnrolled(): Boolean = getActiveUser() != null
    fun addDeviceToActiveUser(hardwareId: String) {
        val user = getActiveUser() ?: return
        if (!user.knownDevices.contains(hardwareId)) { user.knownDevices.add(hardwareId); save(user) }
    }
    fun resolvePairing(hardwareId: String): GtUser? {
        val activeUser = getActiveUser() ?: return null
        return if (activeUser.knownDevices.contains(hardwareId)) activeUser
        else { activeUser.knownDevices.add(hardwareId); save(activeUser); activeUser }
    }
}
