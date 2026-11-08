package com.grouptrack.shared.ride

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import com.grouptrack.shared.log.gtLogger
import com.grouptrack.shared.spatial.GtRead
import com.grouptrack.shared.spatial.GtWrite
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/*
 * STEP14-2026-11-08 (2.8): the ride model in shared code -- a line-for-line port of ConvoyRideStore / ConvoyRideJsonWriter / RideApplySource / RideImport. PORT, NOT REDESIGN.
 * Same SQL text, same values in the same order, same JSON keys in the same order, same file names, same log text; every comment below is
 * the 2.7e design record. What changed, and only this (PORT_NOTES.md has the why for each):
 *  - android SQLiteDatabase.rawQuery -> androidx SQLiteConnection prepare / bindText / step (every value still bound as TEXT, as rawQuery
 *    bound it); execSQL(sql, args) -> [exec], which binds as execSQL bound (null / Long / Double / text). Columns read through [str] / [int]
 *    so a column reads as Android's Cursor read it (GtRead.cellText; Cursor.getInt = (int) getLong).
 *  - SpatialDbManager.getSpatialDb() / getExtensionDb() -> [GtRideEnv.spatialDb] / [GtRideEnv.dataDb], asked at the same points.
 *    routes live in the SPATIAL db; rides, enrollments, ride_surveys, users and ride_waypoints in the DATA (extension) db -- as 2.7e.
 *  - org.json -> kotlinx.serialization.json. Reading: the opt* functions below are org.json's own rules (optString of NULL = "null",
 *    numbers as Android's JSONTokener reads them). Writing: [OrgObj] keeps org.json's put (a null value REMOVES the key; a re-put keeps
 *    the key's place). The ride FILE TEXT is made by the platform ([GtRideEnv.packageText] -- Android: org.json's toString(2), exactly
 *    2.7e's bytes).
 *  - java.time.LocalDate -> [GDate] (java.time's own ISO parse / plusDays / toString rules); "now" and the device time zone come from the
 *    platform ([GtRideEnv.today], [GtRideEnv.epochMsToLocalDate]).
 *  - nowUtc() / UUID.randomUUID() -> [GtRideEnv.nowUtc] / [GtRideEnv.newId], injected.
 *  - ConvoyProfileStore / ConvoyNetworkStore / NetworkGenerator.DEFAULT_CHANNEL / SpatialDbManager's route + waypoint helpers ->
 *    [GtRideEnv] (they are the app's, not this file's, and move in their own steps).
 *  - java.io.File / GroupTrackStorage.dir / context.assets / context.filesDir -> [RideFiles] (paths as text; Android: the same java.io calls).
 *  - MapSourceManager.init / RideImportLauncher.offer / bring-the-app-forward -> [RideImportPlatform].
 *  - android.util.Log -> gtLogger, same tags, same text.
 */

// =====================================================================================================================================
// ---- STEP14: what the platform gives the ride model ---------------------------------------------------------------------------------
// =====================================================================================================================================

/** STEP14: the rider profile fields 2.7e read from ConvoyProfileStore.load().
 *  CODE RULE 1: [email] is nullable on purpose -- 2.7e read it as `load()?.email?.takeIf { it.isNotBlank() }`, so a profile with no
 *  email is a real state (the file then says "email": null). The others 2.7e used as non-null Strings (isNotBlank / ifBlank on them). */
class RideMe(
    val userId: String,
    val firstName: String,
    val lastName: String,
    val callsign: String,
    val vehicleType: String,
    val team: String,
    val email: String?,
)

/** STEP14: com.grouptrack.core.OwnerType -- the two owners the ride model creates networks for. */
enum class RideNetOwner { RIDE, LEADER }

/**
 * STEP14: a stored network (com.grouptrack.core.NetworkConfig) as the ride model reads it.
 *  [configId] / [displayName] -- as TEXT, for the SQL and the "Fred K · GroupAB12CD" line.
 *  the j* values -- each field AS JSON TEXT exactly as org.json writes that value (Android: `JSONObject().put("v", value)`, cut to the
 *    value), so the package carries what 2.7e's `.put(k, cfg.field)` carried whatever the field's Kotlin type is (Int / Long / String /
 *    enum ...). Kept as text so the platform needs no kotlinx types.
 *  CODE RULE 1: every member is nullable on purpose -- the ride model must behave as 2.7e did for whatever NetworkConfig holds. A null
 *    j* value means the field held null: org.json's put(k, null) REMOVED the key, and [OrgObj.put] does the same. A null [configId] /
 *    [displayName] prints "null" in text, as a Kotlin template did.
 */
interface RideNet {
    val configId: String?
    val displayName: String?
    val jConfigId: String?
    val jBaseVersion: String?
    val jOwnerType: String?        // cfg.ownerType.db
    val jDisplayName: String?
    val jRegion: String?
    val jModemPreset: String?
    val jHopLimit: String?
    val jTxPower: String?
    val jFrequencySlot: String?
    val jRole: String?
    val jPsk: String?
    val jNetworkSsid: String?
    val jNetworkPassword: String?
}

/** STEP14: ConvoyNetworkStore -- load / create / deleteUnused, as 2.7e called them.
 *  CODE RULE 1: load / create return null exactly when ConvoyNetworkStore did (not stored / could not be made). */
interface RideNetworks {
    fun load(configId: String): RideNet?
    fun create(owner: RideNetOwner, ownerId: String, name: String): RideNet?
    fun deleteUnused(configId: String)
}

/** STEP14: the SpatialDbManager helpers the ride package uses (they move with SpatialDbManager, not here). Each returns what 2.7e's
 *  call returned; JSON objects come as their org.json text (JSONObject.toString()).
 *  CODE RULE 1: every null is 2.7e's own null -- no such route / waypoint / recipe / notes. */
interface RideSpatial {
    /** SpatialDbManager.buildRouteGpxById(routeId): (route name, GPX text). */
    fun routeGpx(routeId: String): Pair<String?, String?>?
    /** SpatialDbManager.routeGeomHash(routeId). */
    fun routeGeomHash(routeId: String): String?
    /** SpatialDbManager.routeRecipe(routeId)?.toString(). */
    fun routeRecipe(routeId: String): String?
    /** SpatialDbManager.buildWaypointGpxById(waypointId)?.second. */
    fun waypointGpx(waypointId: String): String?
    /** SpatialDbManager.readRouteNotes(routeId)?.toString() (read only when INCLUDE_NARRATIVE). */
    fun routeNotes(routeId: String): String?
}

/**
 * STEP14: the files the ride model touches, as java.io.File calls with paths as text. WHY an interface and not okio: 2.7e's behaviour
 * depends on java.io's own answers -- listFiles' ORDER (the heal's first-file-wins and loadAll's ties), renameTo's Boolean, a missing
 * folder giving null. okio's list() SORTS, and its atomicMove throws instead of returning false. The Android side implements each
 * member with the very java.io call 2.7e made; the iPad implements them on its own file system.
 * Paths are built only with [child] / [sibling], never by joining text here.
 */
interface RideFiles {
    /** GroupTrackStorage.dir("rides", context) -- the rides folder (NOT created). Asked exactly where 2.7e asked. */
    fun ridesDir(): String
    /** File(context.filesDir, "gpx_staging") -- where the import panel stages picked files. */
    fun stagingDir(): String
    /** File(dir, name).path */
    fun child(dir: String, name: String): String
    /** File(File(path).parentFile, name).path */
    fun sibling(path: String, name: String): String
    /** File(path).name */
    fun name(path: String): String
    /** File(dir).listFiles()?.map { it.name } -- every entry, in the platform's own order.
     *  CODE RULE 1: null = not a folder / not readable, exactly listFiles' null (2.7e returned early on it). */
    fun list(dir: String): List<String>?
    fun isFile(path: String): Boolean
    fun exists(path: String): Boolean
    fun delete(path: String): Boolean
    fun mkdirs(path: String): Boolean
    /** File.readText() (UTF-8). */
    fun readText(path: String): String
    /** File.writeText(text) (UTF-8). */
    fun writeText(path: String, text: String)
    /** File(from).renameTo(File(to)). */
    fun rename(from: String, to: String): Boolean
    fun length(path: String): Long
    /** context.assets.open(name).bufferedReader().use { it.readText() } */
    fun readAsset(name: String): String
}

/** STEP14: what RideImport did after staging the GPX -- platform work, in 2.7e's order. */
interface RideImportPlatform {
    /** MapSourceManager.init(context.applicationContext)  // MAPINIT-2026-09-24: real sources, even from cold */
    fun initMapSources()
    /** RideImportLauncher.offer(File(gpxPath)) */
    fun offer(gpxPath: String)
    /** packageManager.getLaunchIntentForPackage(..) + startActivity (NEW_TASK | CLEAR_TOP | SINGLE_TOP). */
    fun bringForward()
}

/**
 * STEP14: the platform's pieces, set ONCE (the Android wrapper's GtRidesAndroid initialiser) before any call -- the GtRoute.Env pattern,
 * so every app-facing call keeps its 2.7e signature.
 * @param spatialDb SpatialDbManager.getSpatialDb() -- CODE RULE 1: null = not open, 2.7e's `?: return ..` (the same fallbacks here).
 * @param dataDb SpatialDbManager.getExtensionDb() -- CODE RULE 1: null = not open, as above.
 * @param nowUtc 2.7e's nowUtc(): SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US), UTC, now.
 * @param today java.time.LocalDate.now().toString() (the device's zone).
 * @param epochMsToLocalDate Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString().
 * @param newId UUID.randomUUID().toString().
 * @param profile ConvoyProfileStore.load() -- CODE RULE 1: null = no rider profile (2.7e refused / returned on it).
 * @param defaultChannel com.grouptrack.core.NetworkGenerator.DEFAULT_CHANNEL.
 * @param packageText a ride package's FILE TEXT from its JSON text -- Android: org.json.JSONObject(json).toString(2), so the stored
 *   file is 2.7e's bytes (org.json's indent, its number forms, its "\/").
 */
class GtRideEnv(
    val spatialDb: () -> SQLiteConnection?,
    val dataDb: () -> SQLiteConnection?,
    val nowUtc: () -> String,
    val today: () -> String,
    val epochMsToLocalDate: (Long) -> String,
    val newId: () -> String,
    val profile: () -> RideMe?,
    val networks: RideNetworks,
    val defaultChannel: String,
    val spatial: RideSpatial,
    val packageText: (json: String) -> String,
)

// =====================================================================================================================================
// ---- STEP14: the JVM / Android pieces, in common Kotlin, each doing what 2.7e's call did -----------------------------------------
// =====================================================================================================================================

/** STEP14: db.rawQuery(sql, args).use { c -> .. }. Every value bound as TEXT -- Android's rawQuery binds every selectionArg as a String,
 *  so SQLite's comparison affinity is 2.7e's. Closed whatever happens, as use{} did. 2.7e's `null` args pass emptyList(). */
private inline fun <T> rawQuery(db: SQLiteConnection, sql: String, args: List<String>, body: (SQLiteStatement) -> T): T {
    val st = db.prepare(sql)
    try {
        args.forEachIndexed { i, a -> st.bindText(i + 1, a) }
        return body(st)
    } finally { st.close() }
}

/** STEP14: db.execSQL(sql, bindArgs) -- bound as Android's execSQL binds (DatabaseUtils.bindObjectToProgram): null -> NULL, a Double /
 *  Float -> REAL, any other number -> INTEGER, a Boolean -> 1 / 0, anything else -> its text. 2.7e's execSQL(sql) passes emptyList(). */
private fun exec(db: SQLiteConnection, sql: String, args: List<Any?>) {
    val st = db.prepare(sql)
    try {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.bindNull(i + 1)
                is Double -> st.bindDouble(i + 1, a)
                is Float -> st.bindDouble(i + 1, a.toDouble())
                is Long -> st.bindLong(i + 1, a)
                is Int -> st.bindLong(i + 1, a.toLong())
                is Short -> st.bindLong(i + 1, a.toLong())
                is Byte -> st.bindLong(i + 1, a.toLong())
                is Boolean -> st.bindLong(i + 1, if (a) 1L else 0L)
                else -> st.bindText(i + 1, a.toString())
            }
        }
        st.step()
    } finally { st.close() }
}

/** STEP14: Cursor.getString -- NULL -> null, a REAL as CursorWindow writes it ("%g"), else SQLite's text (GtRead.cellText). */
private fun str(c: SQLiteStatement, i: Int): String? = GtRead.cellText(c, i)

/** STEP14: Cursor.getInt -- (int) of the column's long. */
private fun int(c: SQLiteStatement, i: Int): Int = c.getLong(i).toInt()

/** STEP14: org.json's JSONObject(text) -- the text must be one JSON object. */
private fun parseObject(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

/** STEP14: org.json's JSONObject.NULL as a value read back (String.valueOf(NULL) = "null"). */
private object OrgNull { override fun toString() = "null" }

/** STEP14: a value as Android's org.json holds it after parsing: a String, OrgNull, Boolean, Int, Long, Double, or the object / array. */
private fun orgValue(e: JsonElement): Any = when (e) {
    is JsonNull -> OrgNull
    is JsonPrimitive -> if (e.isString) e.content else orgLiteral(e.content)
    else -> e
}

/** STEP14: Android's JSONTokener.readLiteral for an unquoted token: null / true / false (any case); with no '.', a long in base 16
 *  ("0x"), 8 (a leading "0") or 10 -- an Integer when it fits, else a Long; else a Double; else the text itself. */
private fun orgLiteral(literal: String): Any {
    if (literal.equals("null", ignoreCase = true)) return OrgNull
    if (literal.equals("true", ignoreCase = true)) return true
    if (literal.equals("false", ignoreCase = true)) return false
    if (literal.indexOf('.') == -1) {
        var base = 10
        var number = literal
        if (number.startsWith("0x") || number.startsWith("0X")) { number = number.substring(2); base = 16 }
        else if (number.startsWith("0") && number.length > 1) { number = number.substring(1); base = 8 }
        number.toLongOrNull(base)?.let { v -> return if (v <= Int.MAX_VALUE && v >= Int.MIN_VALUE) v.toInt() else v }
    }
    literal.toDoubleOrNull()?.let { return it }
    return literal
}

/** STEP14: org.json optString(name, fallback): missing -> fallback; NULL -> "null"; a number / boolean -> String.valueOf; an object /
 *  array -> its compact text. CODE RULE 1: the default is org.json's own one-argument optString(name) (fallback ""). */
private fun JsonObject.optString(k: String, fallback: String = ""): String {
    val v = this[k] ?: return fallback
    return when (val o = orgValue(v)) { is JsonElement -> GtWrite.orgJson(o); else -> o.toString() }
}

/** STEP14: org.json optJSONObject(name): the value only when it is an object. */
private fun JsonObject.optJSONObject(k: String): JsonObject? = this[k] as? JsonObject

/** STEP14: org.json has(name): the key is there (even holding null). */
private fun JsonObject.has(k: String): Boolean = containsKey(k)

/** STEP14: org.json optBoolean(name, fallback): a Boolean, or the text "true" / "false" (any case); else fallback. */
private fun JsonObject.optBoolean(k: String, fallback: Boolean): Boolean {
    val v = this[k] ?: return fallback
    return when (val o = orgValue(v)) {
        is Boolean -> o
        is String -> if (o.equals("true", ignoreCase = true)) true else if (o.equals("false", ignoreCase = true)) false else fallback
        else -> fallback
    }
}

/** STEP14: org.json optInt(name, fallback): a number's intValue(), or (int) Double.parseDouble(text); else fallback. */
private fun JsonObject.optInt(k: String, fallback: Int): Int {
    val v = this[k] ?: return fallback
    return when (val o = orgValue(v)) {
        is Int -> o
        is Long -> o.toInt()
        is Double -> o.toInt()
        is String -> o.toDoubleOrNull()?.toInt() ?: fallback
        else -> fallback
    }
}

/** STEP14: org.json optDouble(name, fallback): a number's doubleValue(), or Double.valueOf(text); else fallback. */
private fun JsonObject.optDouble(k: String, fallback: Double): Double {
    val v = this[k] ?: return fallback
    return when (val o = orgValue(v)) {
        is Int -> o.toDouble()
        is Long -> o.toDouble()
        is Double -> o
        is String -> o.toDoubleOrNull() ?: fallback
        else -> fallback
    }
}

/** STEP14: org.json's JSONObject as 2.7e built it: put(k, null) REMOVES k; putting an existing key keeps its place (LinkedHashMap). */
private class OrgObj {
    private val m = LinkedHashMap<String, JsonElement>()
    fun put(k: String, v: JsonElement?): OrgObj { if (v == null) m.remove(k) else m[k] = v; return this }
    fun obj(): JsonObject = JsonObject(m)
}

/** STEP14: a String value as org.json's put held it (null -> no value, so put removes the key). */
private fun txt(s: String?): JsonElement? = s?.let { JsonPrimitive(it) }

/** STEP14: a value the platform gave as org.json-written JSON text (RideNet's j* members). */
private fun jtext(s: String?): JsonElement? = s?.let { Json.parseToJsonElement(it) }

/**
 * STEP14: java.time.LocalDate in common Kotlin -- only what the ride model used, each with java.time's own rule:
 *  [parse] = LocalDate.parse (ISO_LOCAL_DATE, STRICT: 4-digit year, or a sign and more digits for "+" / a "-" year; 2-digit month and
 *  day; the date must exist), [of] = LocalDate.of (null where it threw), [plusDays] / [minusDays] through the epoch day (the same
 *  algorithms), [isBefore], [toString] (LocalDate.toString). A failure throws, where java.time threw.
 */
internal class GDate private constructor(val year: Int, val month: Int, val day: Int) {
    fun isBefore(o: GDate): Boolean = compareTo0(o) < 0
    private fun compareTo0(o: GDate): Int {
        var cmp = year - o.year
        if (cmp == 0) { cmp = month - o.month; if (cmp == 0) cmp = day - o.day }
        return cmp
    }
    fun toEpochDay(): Long {
        val y = year.toLong()
        val m = month.toLong()
        var total = 0L
        total += 365 * y
        if (y >= 0) total += (y + 3) / 4 - (y + 99) / 100 + (y + 399) / 400
        else total -= y / -4 - y / -100 + y / -400
        total += (367 * m - 362) / 12
        total += day - 1
        if (m > 2) { total--; if (!isLeap(y)) total-- }
        return total - DAYS_0000_TO_1970
    }
    fun plusDays(n: Long): GDate {
        if (n == 0L) return this
        val e = toEpochDay()
        val r = e + n
        if (((e xor r) and (n xor r)) < 0) throw ArithmeticException("long overflow")   // Math.addExact
        return ofEpochDay(r)
    }
    fun minusDays(n: Long): GDate = if (n == Long.MIN_VALUE) plusDays(Long.MAX_VALUE).plusDays(1) else plusDays(-n)
    override fun toString(): String {
        val absYear = abs(year)
        val buf = StringBuilder(10)
        if (absYear < 1000) {
            if (year < 0) buf.append(year - 10000).deleteAt(1) else buf.append(year + 10000).deleteAt(0)
        } else {
            if (year > 9999) buf.append('+')
            buf.append(year)
        }
        return buf.append(if (month < 10) "-0" else "-").append(month).append(if (day < 10) "-0" else "-").append(day).toString()
    }

    companion object {
        private const val DAYS_PER_CYCLE = 146097L
        private const val DAYS_0000_TO_1970 = DAYS_PER_CYCLE * 5L - (30L * 365L + 7L)
        private const val MIN_YEAR = -999_999_999
        private const val MAX_YEAR = 999_999_999
        private const val MIN_EPOCH_DAY = -365243219162L
        private const val MAX_EPOCH_DAY = 365241780471L

        private fun isLeap(y: Long): Boolean = (y and 3L) == 0L && (y % 100L != 0L || y % 400L == 0L)
        private fun lengthOfMonth(y: Int, m: Int): Int = when (m) {
            2 -> if (isLeap(y.toLong())) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

        /** LocalDate.of(y, m, d) -- CODE RULE 1: null exactly where java.time threw DateTimeException (no such date). */
        fun of(y: Int, m: Int, d: Int): GDate? {
            if (y < MIN_YEAR || y > MAX_YEAR || m < 1 || m > 12 || d < 1 || d > 31) return null
            if (d > 28 && d > lengthOfMonth(y, m)) return null
            return GDate(y, m, d)
        }

        fun ofEpochDay(epochDay: Long): GDate {
            if (epochDay < MIN_EPOCH_DAY || epochDay > MAX_EPOCH_DAY) throw IllegalArgumentException("Invalid value for EpochDay: $epochDay")
            var zeroDay = epochDay + DAYS_0000_TO_1970
            zeroDay -= 60
            var adjust = 0L
            if (zeroDay < 0) {
                val adjustCycles = (zeroDay + 1) / DAYS_PER_CYCLE - 1
                adjust = adjustCycles * 400
                zeroDay += -adjustCycles * DAYS_PER_CYCLE
            }
            var yearEst = (400 * zeroDay + 591) / DAYS_PER_CYCLE
            var doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400)
            if (doyEst < 0) {
                yearEst--
                doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400)
            }
            yearEst += adjust
            val marchDoy0 = doyEst.toInt()
            val marchMonth0 = (marchDoy0 * 5 + 2) / 153
            val month = (marchMonth0 + 2) % 12 + 1
            val dom = marchDoy0 - (marchMonth0 * 306 + 5) / 10 + 1
            yearEst += marchMonth0 / 10
            if (yearEst < MIN_YEAR || yearEst > MAX_YEAR) throw IllegalArgumentException("Invalid value for Year: $yearEst")
            return GDate(yearEst.toInt(), month, dom)
        }

        private fun bad(t: String): Nothing = throw IllegalArgumentException("Text '$t' could not be parsed")

        /** LocalDate.parse(t): ISO_LOCAL_DATE, strict. ASCII digits only (java.time's DecimalStyle.STANDARD). */
        fun parse(t: String): GDate {
            val n = t.length
            var p = 0
            var neg = false
            var pos = false
            if (p < n && t[p] == '+') { pos = true; p++ } else if (p < n && t[p] == '-') { neg = true; p++ }
            val ys = p
            var y = 0L
            while (p < n && p - ys < 10 && t[p] in '0'..'9') { y = y * 10 + (t[p] - '0'); p++ }
            val yl = p - ys
            if (yl < 4) bad(t)
            if (pos && yl <= 4) bad(t)              // EXCEEDS_PAD: "+" only when the year needs more than 4 digits
            if (!pos && !neg && yl > 4) bad(t)      // ... and more than 4 digits need a sign
            if (neg) { if (y == 0L) bad(t); y = -y }
            if (y < MIN_YEAR || y > MAX_YEAR) bad(t)
            fun two(): Int {
                if (p + 2 > n || t[p] !in '0'..'9' || t[p + 1] !in '0'..'9') bad(t)
                val v = (t[p] - '0') * 10 + (t[p + 1] - '0')
                p += 2
                return v
            }
            if (p >= n || t[p] != '-') bad(t)
            p++
            val m = two()
            if (p >= n || t[p] != '-') bad(t)
            p++
            val d = two()
            if (p != n) bad(t)
            return of(y.toInt(), m, d) ?: bad(t)
        }
    }
}

// =====================================================================================================================================
// ---- ConvoyRideStore ----------------------------------------------------------------------------------------------------------------
// =====================================================================================================================================

/**
 * RIDECREATE-2026-09-22 — local rides, first pass.
 *
 * A ride is a package of map, route and waypoint data with a leader and a date.
 * It is useful with NO network at all -- rides.config_id stays null until the
 * transport work lands (CODE RULE 1: a ride legitimately has no network, so the
 * reference is optional by design, not as a shortcut).
 *
 * Nothing here talks to AWS. Rides sit locally; the sync queue is 3.0 work.
 */
data class RouteSummary(
    val routeId: String,
    val name: String,
    val geomHash: String,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
)

data class RideRow(
    val rideId: String,
    val rideName: String,
    val rideDate: String,
    val startTime: String,
    val routeId: String,
    val routeName: String,
    val organizerName: String,
    val createdAt: String
)

object GtRideStore {

    private const val TAG = "ConvoyRideStore"
    private val log = gtLogger(TAG)   // STEP14: android.util.Log(TAG, ..) -- the same tag, so the logs read as 2.7e's

    /** STEP14: set once by the platform before any call (see [GtRideEnv]). lateinit, not nullable (CODE RULE 1): a platform that forgot
     *  to set it fails on the first call instead of silently doing nothing. */
    lateinit var env: GtRideEnv

    private fun nowUtc(): String = env.nowUtc()   // STEP14: SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US), UTC -- the platform's

    /** Every stored route, newest first -- what the picker lists. */
    // CODE RULE 1 (2.7e's default): the picker omits [limit] (200); the heal asks for 500.
    fun listRoutes(limit: Int = 200): List<RouteSummary> {
        val db = env.spatialDb() ?: return emptyList()
        val out = mutableListOf<RouteSummary>()
        try {
            rawQuery(db,
                "SELECT route_id, name, geom_hash, min_lat, max_lat, min_lon, max_lon " +
                    "FROM routes ORDER BY updated_at DESC LIMIT ?",
                listOf(limit.toString())
            ) { c ->
                while (c.step()) {
                    out.add(
                        RouteSummary(
                            routeId = str(c, 0) ?: continue,
                            name = str(c, 1) ?: "Not Named",
                            geomHash = str(c, 2) ?: "",
                            minLat = c.getDouble(3), maxLat = c.getDouble(4),
                            minLon = c.getDouble(5), maxLon = c.getDouble(6)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            log.w { "listRoutes failed: ${e.message}" }
        }
        return out
    }

    /** The route's stored geometry (WKT LINESTRING) -- the vignette draws this. */
    fun routeGeometry(routeId: String): String? {
        val db = env.spatialDb() ?: return null
        return try {
            rawQuery(db, "SELECT geometry FROM routes WHERE route_id = ? LIMIT 1", listOf(routeId)) { c ->
                if (c.step()) str(c, 0) else null }
        } catch (e: Exception) {
            log.w { "routeGeometry failed: ${e.message}" }; null
        }
    }

    /** Rename a route in place -- the name travels, so it is editable where it is chosen. */
    fun renameRoute(routeId: String, name: String): Boolean {
        val db = env.spatialDb() ?: return false
        return try {
            exec(db,
                "UPDATE routes SET name = ?, updated_at = ? WHERE route_id = ?",
                listOf(name.trim().ifBlank { "Not Named" }, nowUtc(), routeId)
            ); true
        } catch (e: Exception) {
            log.w { "renameRoute failed: ${e.message}" }; false
        }
    }

    /**
     * Writes the ride. NOTHING is written until this is called -- an abandoned
     * screen leaves no row (no ghosts). The leader is always this tablet's rider.
     */
    fun saveRide(
        rideName: String,
        rideDate: String,
        startTime: String,
        description: String,
        zipCode: String,
        isPublic: Boolean,
        // FORMLAYOUT-2026-09-27: the network SHOWN on the form. REQUIRED, no default; CODE RULE 1: null is a real
        // choice -- the organizer's own network; a value is the ride-only network generated when the rider toggled.
        rideNetworkId: String?,
        routeId: String
    ): String? {
        val db = env.dataDb() ?: return null
        val me = env.profile() ?: run {
            log.w { "saveRide refused: no rider profile" }; return null
        }
        val leaderName = listOf(me.firstName, me.lastName).filter { it.isNotBlank() }
            .joinToString(" ").ifBlank { me.callsign }
        val now = nowUtc()
        val id = env.newId()
        // RIDENET-2026-09-27 (Fred): the ORGANIZER's network by default -- created with their first ride and reused for
        // every ride after (users.config_id, config_mode 'own') -- or, when asked, a NEW network for this ride only.
        // Every network is NAMED after the organizer. Only the rider's OWN network or a new one: never another
        // organizer's (theirs arrive only to JOIN their rides). Supersedes RIDECFG-2026-09-23's always-unique path.
        // DEFAULTNET-2026-10-10 (Fred): the GroupTrack default network -- the channel every radio carries from install.
        // It is not a stored config (nothing to create, nothing to delete): config_id = "GroupTrack", mode inherit_org.
        val useDefault = rideNetworkId == env.defaultChannel
        val cfgId: String = if (useDefault) env.defaultChannel
            else ((if (rideNetworkId != null) env.networks.load(rideNetworkId)
                   else organizerNetwork(me.userId, leaderName))?.configId ?: run {
                log.w { "saveRide refused: the ride's network was not available" }; return null
            })
        val mode = when {
            useDefault -> "inherit_org"
            rideNetworkId != null -> "unique"
            else -> "inherit_leader"
        }
        val expires = expiresFor(rideDate)
        return try {
            exec(db,
                "INSERT INTO rides (ride_id, organizer_id, organizer_name, route_id, " +
                    "ride_name, ride_date, start_time, description, zip_code, is_public, " +
                    "config_mode, config_id, expires_at, " +
                    "created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf<Any?>(
                    id, me.userId, leaderName, routeId,
                    rideName.trim(), rideDate.trim(), startTime.trim(), description.trim(),
                    zipCode.trim(), if (isPublic) 1 else 0,
                    mode, cfgId, expires, now, now
                )
            )
            log.i { "RIDECREATE-2026-09-22: ride saved $id name=$rideName route=$routeId" }
            markOrganizer(me.userId)   // RIDENET-2026-09-27: creating a ride makes you an organizer
            log.i { "RIDENET: ride $id on $cfgId ($mode)" }
            id
        } catch (e: Exception) {
            log.e { "saveRide failed: ${e.message}" }
            if (rideNetworkId != null && !useDefault) env.networks.deleteUnused(cfgId)   // RIDECFG: no ghost config (never the organizer's own)
            null
        }
    }

    // ---- CHECKIN-2026-09-27 (Fred): the pre-ride check-in and the end of the ride ------------------------------
    data class RideChoice(val rideId: String, val name: String, val date: String, val startTime: String,
                          val organizerName: String, val organizerId: String, val isPublic: Boolean)

    /** The check-in. CODE RULE 1: rideId null = "No scheduled ride" (a normal recording; no survey, no sharing). */
    data class CheckIn(val rideId: String?, val rideName: String?, val isPublic: Boolean, val callsign: String, val role: String)

    /** Open, recent rides: dated today or earlier and not expired -- today's first, then newest. */
    /**
     * CHECKINRIDES-2026-09-28 (Fred): EVERY ride not yet removed -- future ones too -- sorted by date, NEWEST FIRST (upcoming
     * above today, past below). "Not yet removed" is decided HERE, not by a text comparison in SQL: expires_at may be a
     * date, a timestamp or missing (then ride date + 30); a ride with no readable date is never treated as removed (the
     * expiry sweep's own rule). Logs what the table holds, so an empty list explains itself.
     */
    fun openRecentRides(): List<RideChoice> = try {
        expireRides()   // RIDESTATUS-2026-09-30: apply the rule, then read only open rides
        val today = GDate.parse(env.today())   // STEP14: java.time.LocalDate.now()
        var rows = 0; var sample = ""
        val out = ArrayList<RideChoice>()
        env.dataDb()?.let { db -> rawQuery(db,
            "SELECT ride_id, ride_name, ride_date, start_time, organizer_name, organizer_id, is_public, expires_at FROM rides WHERE COALESCE(status,'open') <> 'expired'", emptyList()) { c ->
            while (c.step()) {
                rows++
                val exp = str(c, 7)
                if (sample.isEmpty() && exp != null) sample = exp
                val date = str(c, 2) ?: ""
                // STEP14: `!!` where 2.7e passed Cursor.getString's platform String to a non-null parameter (a NULL ride_id threw there too).
                if (!rideRemovedBy(exp, date, today)) out += RideChoice(str(c, 0)!!, str(c, 1) ?: "Ride", date,
                    str(c, 3) ?: "", str(c, 4) ?: "", str(c, 5) ?: "", int(c, 6) == 1)
            }
        } }
        log.i { "CHECKINRIDES: $rows rides in the table, ${out.size} not removed (expires_at e.g. '$sample')" }
        out.sortedWith(compareByDescending<RideChoice> { it.date }.thenByDescending { it.startTime })
    } catch (e: Exception) { log.w { "CHECKIN: openRecentRides failed: ${e.message}" }; emptyList() }

    /** CHECKINRIDES: removed = past its expiry. expires_at as a date ("2026-10-28..."), a timestamp (s or ms), or missing
     *  (then ride date + 30). Nothing readable -> never removed. */
    private fun rideRemovedBy(exp: String?, date: String, today: GDate): Boolean {
        exp?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let { e ->
            e.toLongOrNull()?.let { n ->
                val ms = if (n < 100_000_000_000L) n * 1000 else n
                return GDate.parse(env.epochMsToLocalDate(ms)).isBefore(today)   // STEP14: Instant.ofEpochMilli(ms).atZone(systemDefault).toLocalDate()
            }
            runCatching { GDate.parse(e.take(10)) }.getOrNull()?.let { return it.isBefore(today) }
        }
        return runCatching { GDate.parse(date.take(10)).plusDays(30).isBefore(today) }.getOrDefault(false)
    }

    /** Checks in: the enrollment (ride, me, callsign and role FOR THIS RIDE; created_by 'login'), replacing any earlier
     *  check-in of mine to the same ride. No ride -> no enrollment. The profile is never changed. Null only on failure. */
    // STEP14 CODE RULE 1 (2.7e's): ride null = "No scheduled ride" (see CheckIn).
    fun checkIn(ride: RideChoice?, callsign: String, role: String): CheckIn? {
        val me = env.profile() ?: return null
        val cs = callsign.trim().ifBlank { me.callsign }
        if (ride == null) { log.i { "CHECKIN: no scheduled ride, callsign $cs, role $role" }; return CheckIn(null, null, false, cs, role) }
        return try {
            val db = env.dataDb() ?: return null
            exec(db, "DELETE FROM enrollments WHERE ride_id = ? AND user_id = ?", listOf<Any?>(ride.rideId, me.userId))
            exec(db,
                "INSERT INTO enrollments (enrollment_id, ride_id, user_id, callsign, display_name, role, vehicle_type, team, " +
                    "created_by, enrolled_at) VALUES (?,?,?,?,?,?,?,?,'login',?)",
                listOf<Any?>(env.newId(), ride.rideId, me.userId, cs,
                    listOf(me.firstName, me.lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { cs },
                    role, me.vehicleType.ifBlank { null }, me.team.ifBlank { null }, nowUtc()))
            log.i { "CHECKIN: ${ride.name} (${ride.rideId}) as $role, callsign $cs" }
            CheckIn(ride.rideId, ride.name, ride.isPublic, cs, role)
        } catch (e: Exception) { log.e { "CHECKIN: not saved: ${e.message}" }; null }
    }

    /** What the end-of-ride form collected on a PUBLIC ride. shareTrack is the rider's own required choice. */
    data class RideSurveyInput(val rideId: String, val rating: Int, val difficulty: String, val recommend: Boolean,
                               val notes: String, val shareTrack: Boolean)

    /** v2 (Fred): the survey belongs to the RIDE -- one per rider per ride; a re-save replaces it. trackId is the
     *  rider's own local recording, kept only as the link the table requires (the server keeps ONE track per ride). */
    fun saveSurvey(trackId: String, s: RideSurveyInput): Boolean {
        val me = env.profile() ?: return false
        return try {
            env.dataDb()?.let { exec(it,
                "DELETE FROM ride_surveys WHERE ride_id = ? AND user_id = ?", listOf<Any?>(s.rideId, me.userId)) }
            // CHECKIN-2026-09-27: the alternate key -- one survey per rider per ride, enforced by the database.
            env.dataDb()?.let { exec(it,
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_ride_surveys_ride_user ON ride_surveys(ride_id, user_id) WHERE ride_id IS NOT NULL", emptyList()) }
            env.dataDb()?.let { exec(it,
                "INSERT OR REPLACE INTO ride_surveys (survey_id, track_id, ride_id, user_id, rating, difficulty, recommend, " +
                    "notes, track_donated, submitted_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                listOf<Any?>(env.newId(), trackId, s.rideId, me.userId, s.rating, s.difficulty,
                    if (s.recommend) 1 else 0, s.notes.ifBlank { null }, if (s.shareTrack) 1 else 0, nowUtc())) }
            log.i { "ENDRIDE: survey saved track=$trackId ride=${s.rideId} rating=${s.rating} share=${s.shareTrack}" }
            true
        } catch (e: Exception) { log.e { "ENDRIDE: survey not saved: ${e.message}" }; false }
    }

    // ---- RIDENET-2026-09-27 (Fred): the organizer's network ------------------------------------------------------
    /** The rider's OWN organizer network id (users.config_id of the is_self row), or null before their first ride. */
    private fun myConfigId(): String? = try {
        env.dataDb()?.let { db -> rawQuery(db,
            "SELECT config_id FROM users WHERE is_self = 1 AND config_id IS NOT NULL LIMIT 1", emptyList()) { c ->
            if (c.step()) str(c, 0) else null
        } }
    } catch (e: Exception) { null }

    /** For the ride form: "Fred K · GroupAB12CD". CODE RULE 1: null is a real state -- no network yet. */
    fun myNetworkName(): String? =
        myConfigId()?.let { env.networks.load(it) }?.let { "${it.displayName} \u00b7 ${it.configId}" }

    /** RIDENETWORDS2-2026-09-27: the rider's OWN network, created now if missing (named after them), returned by name
     *  for the ride form. CODE RULE 1: null only when there is no rider profile or the network could not be made. */
    fun ensureMyNetworkName(): String? {
        val me = env.profile() ?: return null
        val name = listOf(me.firstName, me.lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { me.callsign }
        organizerNetwork(me.userId, name) ?: return null
        return myNetworkName()
    }

    /** FORMLAYOUT-2026-09-27: a ride-only network, generated when the rider toggles to it on the form (named after the
     *  organizer). Used only when the ride is added; unused ones are removed by deleteUnused. Null only on failure. */
    fun createRideNetwork(): RideNet? {
        val me = env.profile() ?: return null
        val name = listOf(me.firstName, me.lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { me.callsign }
        return env.networks.create(RideNetOwner.RIDE, "pending-" + env.newId(), name)
    }

    /** True once this rider has created a ride (users.is_organizer). */
    fun isOrganizer(): Boolean = try {
        env.dataDb()?.let { db -> rawQuery(db,
            "SELECT is_organizer FROM users WHERE is_self = 1 LIMIT 1", emptyList()) { c ->
            c.step() && int(c, 0) == 1
        } } ?: false
    } catch (e: Exception) { false }

    /** The organizer's OWN network: the existing one, or created now (owner LEADER, named after the organizer) and
     *  recorded on the rider (config_mode 'own'). Null only when it could not be created. */
    private fun organizerNetwork(userId: String, name: String): RideNet? {
        myConfigId()?.let { id -> env.networks.load(id)?.let { return it } }
        val cfg = env.networks.create(RideNetOwner.LEADER, userId, name) ?: return null
        try {
            env.dataDb()?.let { exec(it,
                "UPDATE users SET config_mode = 'own', config_id = ?, updated_at = ? WHERE user_id = ?",
                listOf<Any?>(cfg.configId, nowUtc(), userId)) }
            log.i { "RIDENET: organizer network ${cfg.configId} created for $name" }
        } catch (e: Exception) {
            log.e { "RIDENET: organizer network not recorded on the rider: ${e.message}" }
        }
        return cfg
    }

    /** Creating a ride makes you an organizer (users.is_organizer = 1). */
    private fun markOrganizer(userId: String) {
        try {
            env.dataDb()?.let { exec(it,
                "UPDATE users SET is_organizer = 1, updated_at = ? WHERE user_id = ? AND is_organizer = 0",
                listOf<Any?>(nowUtc(), userId)) }
        } catch (e: Exception) {
            log.w { "RIDENET: is_organizer not set: ${e.message}" }
        }
    }

    // ---- RIDESTATUS-2026-09-30 (Fred): expiry is a STATE, not a removal ------------------------------------
    /**
     * Marks every OPEN ride past its expiry (expires_at, or ride date + 30) as 'expired'. NOTHING is deleted: the
     * ride row, its file, its picture and its survey stay (3.0 removes a ride only when it is expired AND its survey
     * has been sent to AWS). A ride with no readable date is never expired (logged). Every reader of open rides runs
     * this first. Same date rule as the old RIDEEXPIRE sweep, which deleted.
     */
    fun expireRides() {
        ensureSchema()
        val db = env.dataDb() ?: return
        try {
            val today = env.today()   // STEP14: LocalDate.now().toString()
            val expired = mutableListOf<String>()
            rawQuery(db, "SELECT ride_id, ride_date, expires_at FROM rides WHERE COALESCE(status,'open') <> 'expired'", emptyList()) { c ->
                while (c.step()) {
                    val rid = str(c, 0) ?: continue
                    val exp = str(c, 2)?.takeIf { it.isNotBlank() } ?: expiresFor(padDate(str(c, 1) ?: ""))
                    if (exp == null) { log.w { "RIDESTATUS: $rid has no readable ride date -- kept open" }; continue }
                    if (exp < today) expired += rid
                }
            }
            expired.forEach { rid ->
                exec(db, "UPDATE rides SET status='expired', updated_at=? WHERE ride_id=?", listOf<Any?>(nowUtc(), rid))
                log.i { "RIDESTATUS: $rid expired (30 days after its ride date) -- kept, not deleted" }
            }
        } catch (e: Exception) { log.w { "RIDESTATUS: expiry failed: ${e.message}" } }
    }

    // ---- RIDEHEAL-2026-09-26 (Fred): the ride library heals itself ----------------------------------
    /**
     * Reconciles the ride FILES (rides/<id>.json) with the rides TABLE before any ride list is shown.
     * ADDS OR REPAIRS -- and deletes ONLY on EXPIRY (RIDEEXPIRE: 30 days after the ride date, the ride's own
     * published expiry). Never guesses at ghosts (Fred: a profile can be recreated; any other deletion is the
     * rider's own Delete). Everything is observable: every repair and expiry is logged (RIDEHEAL / RIDEDELETE).
     *  - a file with no row  -> a row created as an INCOMPLETE ride of its CREATOR (the file's originator
     *    userId + name; distributed_at empty); the route matched by name; config_mode 'unique' + the file's
     *    network id (a received ride's network lives ONLY in its file).
     *  - a short date (2026-10-5) -> padded to yyyy-MM-dd in the file and the row; the expiry recomputed.
     *  - a row with no file  -> LOGGED only (a file is rebuilt from a row only for this tablet's own rides).
     * Idempotent: a second run finds nothing to do.
     */
    // STEP14: `fs` stands where 2.7e took the Context (its only use was the rides folder). `files` holds the file NAMES (2.7e: the
    //  File objects -- only their name, text and nameWithoutExtension were used).
    fun healFromFiles(fs: RideFiles) {
        ensureSchema()
        val db = env.dataDb() ?: return
        val dir = fs.ridesDir()
        val files = fs.list(dir)?.filter { f -> fs.isFile(fs.child(dir, f)) && f.endsWith(".json") } ?: return
        val rows = mutableSetOf<String>()
        try {
            rawQuery(db, "SELECT ride_id FROM rides", emptyList()) { c -> while (c.step()) str(c, 0)?.let { rows += it } }
        } catch (e: Exception) { log.w { "RIDEHEAL: cannot read rides: ${e.message}" }; return }
        val routes by lazy { listRoutes(500) }
        for (f in files) {
            val path = fs.child(dir, f)
            // STEP14: j and ride are vars -- org.json's put changed them in place; here the changed object is put back (same key places).
            var j = try { parseObject(fs.readText(path)) } catch (e: Exception) { log.w { "RIDEHEAL: unreadable $f" }; continue }
            if (j.optString("kind") != "grouptrack.ride") continue
            var ride = j.optJSONObject("ride") ?: continue
            val id = ride.optString("rideId").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val s = { k: String -> ride.optString(k).takeIf { it != "null" }.orEmpty() }
            val rawDate = s("date")
            val date = padDate(rawDate)
            if (date != rawDate) {
                ride = LinkedHashMap<String, JsonElement>(ride).also {
                    it["date"] = JsonPrimitive(date); it["expiresAt"] = expiresFor(date)?.let { x -> JsonPrimitive(x) } ?: JsonNull
                }.let { JsonObject(it) }
                j = LinkedHashMap<String, JsonElement>(j).also { it["ride"] = ride }.let { JsonObject(it) }
                try { fs.writeText(path, env.packageText(j.toString())); log.i { "RIDEHEAL: $id date $rawDate -> $date (file)" } }
                catch (e: Exception) { log.w { "RIDEHEAL: $id date not rewritten: ${e.message}" } }
                if (id in rows) try {
                    exec(db, "UPDATE rides SET ride_date=?, expires_at=?, updated_at=? WHERE ride_id=?", listOf<Any?>(date, expiresFor(date), nowUtc(), id))
                    log.i { "RIDEHEAL: $id date $rawDate -> $date (row)" }
                } catch (e: Exception) { log.w { "RIDEHEAL: $id row date not updated: ${e.message}" } }
            }
            if (id in rows) continue
            val o = j.optJSONObject("originator")
            val creatorId = o?.optString("userId")?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()
            val creatorName = o?.optString("name")?.takeIf { it != "null" }.orEmpty()
            val routeName = j.optJSONObject("rideData")?.optJSONObject("route")?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
            val routeId = routeName?.let { n -> routes.firstOrNull { it.name == n }?.routeId }
            try {
                exec(db,
                    "INSERT INTO rides (ride_id, organizer_id, organizer_name, route_id, " +
                        "ride_name, ride_date, start_time, description, zip_code, is_public, " +
                        "config_mode, config_id, expires_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    listOf<Any?>(
                        id, creatorId, creatorName, routeId, s("name"), date, s("startTime"), s("description"), s("zipCode"),
                        if (j.optJSONObject("ride")?.optBoolean("isPublic", false) == true) 1 else 0,   // RIDEPUBLIC-2026-09-27: from the file (older files: private)
                        "unique", j.optJSONObject("network") /* CLOSEFIX-2026-09-26: the CHECK allows inherit_org|inherit_leader|unique */?.optString("id")?.takeIf { it.isNotBlank() && it != "null" },
                        expiresFor(date), s("createdAt").ifEmpty { nowUtc() }, nowUtc()
                    )
                )
                rows += id
                log.i { "RIDEHEAL: $id row created from $f -- incomplete ride of '$creatorName'" +
                    (if (creatorId.isEmpty()) " (no creator id in the file)" else "") +
                    ", route " + (routeId ?: "NOT FOUND ('$routeName')") }
            } catch (e: Exception) { log.w { "RIDEHEAL: $id row not created: ${e.message}" } }
        }
        rows.filter { r -> files.none { it.substringBeforeLast(".") == r } }   // STEP14: File.nameWithoutExtension
            .forEach { log.w { "RIDEHEAL: row $it has no ride file (logged only)" } }
        // RIDESTATUS-2026-09-30 (Fred): expiry flips the ride's STATE -- nothing is deleted
        // (was RIDEEXPIRE-2026-09-26: deleteRide 30 days after the ride date).
        expireRides()
    }

    /**
     * RIDEEXPIRE-2026-09-26: removes a ride COMPLETELY -- its row, its waypoint links, its network when no other
     * ride uses it, its ride file and its picture. Used by the expiry sweep AND by the rider's Delete (one
     * delete, so a ride can never be half-removed). NEVER touches the route, the waypoints themselves, or radio
     * backups (their ride titles live in the backups' own companion files). Logged (RIDEDELETE).
     */
    fun deleteRide(fs: RideFiles, rideId: String, reason: String): Boolean {
        val db = env.dataDb() ?: return false
        return try {
            val cfgId = rawQuery(db, "SELECT config_id FROM rides WHERE ride_id = ?", listOf(rideId)) { c ->
                if (c.step() && !c.isNull(0)) str(c, 0) else null
            }
            exec(db, "DELETE FROM ride_waypoints WHERE ride_id = ?", listOf<Any?>(rideId))
            exec(db, "DELETE FROM rides WHERE ride_id = ?", listOf<Any?>(rideId))
            cfgId?.let { env.networks.deleteUnused(it) }
            val dir = fs.ridesDir()
            listOf("$rideId.json", "$rideId.jpg").forEach { n -> fs.child(dir, n).takeIf { fs.exists(it) }?.let { fs.delete(it) } }
            log.i { "RIDEDELETE: $rideId removed ($reason)" }
            true
        } catch (e: Exception) {
            log.e { "RIDEDELETE: $rideId failed: ${e.message}" }; false
        }
    }

    /** yyyy-M-d -> yyyy-MM-dd (RIDEHEAL); anything else is returned unchanged. */
    private fun padDate(d: String): String {
        val m = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""").find(d.trim()) ?: return d
        val (y, mo, da) = m.destructured
        return y + "-" + d02(mo.toInt()) + "-" + d02(da.toInt())   // STEP14: "%s-%02d-%02d".format(y, mo.toInt(), da.toInt())
    }

    /** STEP14: "%02d" of a value 0..99 (padDate's \d{1,2}): at least two digits, zero-padded -- Java's Formatter on a US-locale device. */
    private fun d02(v: Int): String = v.toString().padStart(2, '0')

    // ---- RIDECFG-2026-09-23: ride state, edits, waypoints -------------------------------------

    /** Ride date + 30 days (the ride file's expiry), or null when the date is not yyyy-MM-dd yet. */
    private fun expiresFor(rideDate: String): String? =
        try { GDate.parse(rideDate.trim()).plusDays(30).toString() } catch (e: Exception) { null }

    @Volatile private var schemaChecked = false

    /**
     * ALTERREMOVED-2026-09-30 (Fred): the one-time ALTERs for rides.distributed_at (09-23) and rides.status (09-30)
     * were removed once Droid 1 and Droid 2 had run them. Both columns are in schema_device_additions.sql.
     */
    private fun ensureSchema() {
        if (schemaChecked) return
        val db = env.dataDb() ?: return
        try {
            exec(db,
                "CREATE TABLE IF NOT EXISTS ride_waypoints (ride_id TEXT NOT NULL, " +
                    "waypoint_id TEXT NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY (ride_id, waypoint_id))", emptyList()
            )
            schemaChecked = true
        } catch (e: Exception) {
            log.e { "ensureSchema: ride_waypoints not created: ${e.message}" }
        }
    }

    /** EDITRIDE-2026-09-27: what the edit form shows. unique = the ride has its own network (config_id). */
    // STEP14 CODE RULE 1 (2.7e's): configId null = the ride has no config_id (a ride legitimately has no network -- see the file head).
    data class EditRide(val name: String, val date: String, val startTime: String, val description: String,
                        val isPublic: Boolean, val routeId: String, val unique: Boolean, val configId: String?)

    /** EDITRIDE-2026-09-27: one ride, for the edit form. Null only if it is not on this tablet. */
    fun rideForEdit(rideId: String): EditRide? = try {
        env.dataDb()?.let { db -> rawQuery(db,
            "SELECT ride_name, ride_date, start_time, description, is_public, route_id, config_mode, config_id FROM rides WHERE ride_id = ?",
            listOf(rideId)) { c ->
            if (!c.step()) null
            else EditRide(str(c, 0) ?: "", str(c, 1) ?: "", str(c, 2) ?: "", str(c, 3) ?: "",
                int(c, 4) == 1, str(c, 5) ?: "", str(c, 6) == "unique", str(c, 7))
        } }
    } catch (e: Exception) { log.w { "EDITRIDE: rideForEdit failed: ${e.message}" }; null }

    /** In progress = distributed_at empty. DERIVED from the column, never a second flag. */
    fun isDistributed(rideId: String): Boolean {
        ensureSchema()
        val db = env.dataDb() ?: return true
        return try {
            rawQuery(db, "SELECT distributed_at FROM rides WHERE ride_id = ?", listOf(rideId)) { c ->
                c.step() && !c.isNull(0) && str(c, 0)!!.isNotBlank()
            }
        } catch (e: Exception) {
            log.w { "isDistributed failed: ${e.message}" }; true
        }
    }

    /** Changes an IN-PROGRESS ride. Refused once distributed (then only the date may change). */
    fun updateRide(
        rideId: String, rideName: String, rideDate: String, startTime: String,
        description: String, zipCode: String, isPublic: Boolean, routeId: String
    ): Boolean {
        if (isDistributed(rideId)) {
            log.w { "updateRide refused: $rideId is distributed -- only the date can change" }; return false
        }
        val db = env.dataDb() ?: return false
        return try {
            exec(db,
                "UPDATE rides SET ride_name=?, ride_date=?, start_time=?, description=?, zip_code=?, " +
                    "is_public=?, route_id=?, expires_at=?, updated_at=? " +
                    "WHERE ride_id=? AND distributed_at IS NULL",
                listOf<Any?>(
                    rideName.trim(), rideDate.trim(), startTime.trim(), description.trim(),
                    zipCode.trim(), if (isPublic) 1 else 0, routeId, expiresFor(rideDate), nowUtc(), rideId
                )
            )
            true
        } catch (e: Exception) {
            log.e { "updateRide failed: ${e.message}" }; false
        }
    }

    /** The ONLY change allowed after distribution. Moves the expiry with it. */
    fun updateRideDate(rideId: String, rideDate: String): Boolean {
        val db = env.dataDb() ?: return false
        return try {
            exec(db,
                "UPDATE rides SET ride_date=?, expires_at=?, updated_at=? WHERE ride_id=?",
                listOf<Any?>(rideDate.trim(), expiresFor(rideDate), nowUtc(), rideId)
            )
            true
        } catch (e: Exception) {
            log.e { "updateRideDate failed: ${e.message}" }; false
        }
    }

    /** Sending is the lock. Called when the ride is first distributed; later calls change nothing. */
    fun markDistributed(rideId: String): Boolean {
        ensureSchema()
        val db = env.dataDb() ?: return false
        return try {
            exec(db,
                "UPDATE rides SET distributed_at=?, updated_at=? WHERE ride_id=? AND distributed_at IS NULL",
                listOf<Any?>(nowUtc(), nowUtc(), rideId)
            )
            true
        } catch (e: Exception) {
            log.e { "markDistributed failed: ${e.message}" }; false
        }
    }

    /** Replaces the ride's waypoint list, in order. IN PROGRESS only. */
    fun setRideWaypoints(rideId: String, waypointIds: List<String>): Boolean {
        if (isDistributed(rideId)) {
            log.w { "setRideWaypoints refused: $rideId is distributed" }; return false
        }
        val db = env.dataDb() ?: return false
        return try {
            exec(db, "DELETE FROM ride_waypoints WHERE ride_id=?", listOf<Any?>(rideId))
            waypointIds.forEachIndexed { i, wp ->
                exec(db,
                    "INSERT OR IGNORE INTO ride_waypoints (ride_id, waypoint_id, seq) VALUES (?,?,?)",
                    listOf<Any?>(rideId, wp, i)
                )
            }
            true
        } catch (e: Exception) {
            log.e { "setRideWaypoints failed: ${e.message}" }; false
        }
    }

    /** Rides on this tablet, newest first. */
    // STEP14 CODE RULE 1 (2.7e's default): no caller passes [limit]; 100 is 2.7e's list length.
    fun listRides(limit: Int = 100): List<RideRow> {
        val db = env.dataDb() ?: return emptyList()
        val out = mutableListOf<RideRow>()
        try {
            rawQuery(db,
                "SELECT ride_id, ride_name, ride_date, COALESCE(start_time,''), " +
                    "COALESCE(route_id,''), COALESCE(organizer_name,''), created_at " +
                    "FROM rides ORDER BY created_at DESC LIMIT ?",
                listOf(limit.toString())
            ) { c ->
                while (c.step()) {
                    out.add(
                        RideRow(
                            rideId = str(c, 0) ?: continue,
                            rideName = str(c, 1) ?: "",
                            rideDate = str(c, 2) ?: "",
                            startTime = str(c, 3) ?: "",
                            routeId = str(c, 4) ?: "",
                            routeName = "",
                            organizerName = str(c, 5) ?: "",
                            createdAt = str(c, 6) ?: ""
                        )
                    )
                }
            }
        } catch (e: Exception) {
            log.w { "listRides failed: ${e.message}" }
        }
        return out
    }

    /** WKT LINESTRING -> points, for the vignette. Returns lon/lat pairs. */
    fun parseWktLine(wkt: String): List<Pair<Double, Double>> {
        val inner = wkt.substringAfter('(', "").substringBeforeLast(')', "")
        if (inner.isBlank()) return emptyList()
        return inner.split(',').mapNotNull { p ->
            val t = p.trim().split(' ').filter { it.isNotBlank() }
            if (t.size < 2) null else {
                val x = t[0].toDoubleOrNull(); val y = t[1].toDoubleOrNull()
                if (x == null || y == null) null else Pair(x, y)
            }
        }
    }
}

// =====================================================================================================================================
// ---- ConvoyRideJsonWriter -----------------------------------------------------------------------------------------------------------
// =====================================================================================================================================

/**
 * RIDEJSON-2026-09-23 — writes the ride file (format 3) from the rows that already hold it.
 *
 * THE CONTRACT (Fred 09-23): kind · formatVersion · ride · originator · network{mesh,nucleus} · rideData.
 * ORIGINATOR-2026-09-24: the ride LEADER is a ride-time role (enrollments); the file records who CREATED the ride.
 * No map block (the receiver runs a corridor download on the route). Every attribute comes from an
 * EXISTING provider; this object generates nothing. A value with no provider yet is written as an
 * explicit null placeholder, so the file's shape never changes as features fill them in.
 *
 * COMPLETE = route + trailhead + network all resolve. build() reports what is missing; the send
 * step refuses an incomplete ride. Writing the file never changes the ride's state.
 */
object GtRideJson {

    private const val TAG = "ConvoyRideJson"
    private val log = gtLogger(TAG)
    private val env: GtRideEnv get() = GtRideStore.env

    const val KIND = "grouptrack.ride"
    const val FORMAT_VERSION = 3
    const val COT_SA = "239.2.3.1:6969"        // Nathan 09-22: ATAK situational-awareness multicast
    const val COT_CHAT = "224.10.10.1:17012"   // Nathan 09-22: ATAK chat multicast

    /**
     * ⚠ OPEN (Fred): the narrative payload carries OSM feature names and classes. Until that is
     * decided it is NOT written. The recipe is cleared: every field is a value the rider chose.
     */
    const val INCLUDE_NARRATIVE = false

    /** STEP14: [text] is the package as JSON text (the Android wrapper makes its org.json JSONObject from it). */
    data class Result(val json: JsonObject, val missing: List<String>) {
        val complete: Boolean get() = missing.isEmpty()
        val text: String get() = json.toString()
    }

    private fun nul(v: JsonElement?): JsonElement = v ?: JsonNull

    // STEP14: `fs` stands where 2.7e took the Context (used for the asset). 2.7e's first line, SpatialDbManager.init(context), is the
    //  platform's: the Android wrapper calls it just before this.
    fun build(fs: RideFiles, rideId: String): Result? {
        val db = env.dataDb() ?: return null
        val missing = mutableListOf<String>()

        // ---- the ride row ------------------------------------------------------------------
        val r: Array<String?> = try {
            rawQuery(db,
                "SELECT ride_id, organizer_name, org_name, route_id, ride_name, ride_date, " +
                    "start_time, description, zip_code, config_id, expires_at, created_at, is_public " +   // RIDEPUBLIC-2026-09-27
                    "FROM rides WHERE ride_id = ?", listOf(rideId)
            ) { c ->
                if (!c.step()) null
                else Array(13) { i -> str(c, i) }   // STEP14: `if (c.isNull(i)) null else c.getString(i)` -- cellText is null for NULL
            }
        } catch (e: Exception) {
            log.e { "ride read failed: ${e.message}" }; null
        } ?: run { log.w { "no ride $rideId" }; return null }

        val ride = OrgObj()
            .put("rideId", txt(r[0]))
            .put("name", nul(txt(r[4])))
            .put("date", nul(txt(r[5])))
            .put("startTime", nul(txt(r[6])))
            .put("description", nul(txt(r[7])))
            .put("zipCode", nul(txt(r[8])))
            .put("isPublic", JsonPrimitive(r[12] == "1"))   // RIDEPUBLIC-2026-09-27: every rider's tablet needs it at the end of the ride
            .put("expiresAt", nul(txt(r[10])))
            .put("createdAt", nul(txt(r[11])))

        // COMPLETE-2026-09-24 (Fred): a ride is COMPLETE with a route, a waypoint (the trailhead), a FUTURE
        // date (today or later) and a description. Route and trailhead are checked further down.
        if (r[7].isNullOrBlank()) missing += "description"
        val rideDate = r[5]
        if (rideDate.isNullOrBlank() || !GtRideApply.isCurrent(rideDate, false)) missing += "future date"

        // ---- originator: who created the ride (the creator's own tablet writes the file) --------
        // ORIGINATOR-2026-09-24: fixed forever. The ride LEADER is a role claimed at ride time.
        val originator = OrgObj()
            .put("name", nul(txt(r[1])))
            .put("callsign", nul(txt(env.profile()?.callsign)))
            .put("orgName", nul(txt(r[2])))
            // RIDEHEAL-2026-09-26: stable identity for "mine" + the owner's email (forwarding line and CC)
            .put("userId", nul(txt(env.profile()?.userId?.takeIf { it.isNotBlank() })))
            .put("email", nul(txt(env.profile()?.email?.takeIf { it.isNotBlank() })))

        // ---- network: the config the ride points at ---------------------------------------------
        // DEFAULTNET-2026-10-10: the GroupTrack default network is not a stored config -- its block IS
        // grouptrack_default.json's network (channel GroupTrack, the default key), exactly what every radio carries.
        val defaultNet: JsonObject? = if (r[9] == env.defaultChannel) try {
            // STEP14: org.json's getJSONObject("network") -- it threw when the key was missing or not an object.
            parseObject(fs.readAsset("grouptrack_default.json")).let { o ->
                val n = o["network"] ?: throw IllegalStateException("No value for network")
                n as? JsonObject ?: throw IllegalStateException("Value $n at network cannot be converted to JSONObject")
            }
        } catch (e: Exception) {
            log.e { "DEFAULTNET: grouptrack_default.json network unreadable: ${e.message}" }; null
        } else null
        val cfg = if (defaultNet != null) null else r[9]?.let { env.networks.load(it) }
        val network: JsonElement = if (defaultNet != null) defaultNet else if (cfg == null) {
            missing += "network"; JsonNull
        } else OrgObj()
            .put("id", jtext(cfg.jConfigId))
            .put("baseVersion", jtext(cfg.jBaseVersion))
            .put("owner", OrgObj().put("type", jtext(cfg.jOwnerType)).put("name", jtext(cfg.jDisplayName)).obj())
            .put("mesh", OrgObj()
                .put("region", jtext(cfg.jRegion)).put("preset", jtext(cfg.jModemPreset))
                .put("hopLimit", jtext(cfg.jHopLimit)).put("txPower", jtext(cfg.jTxPower))
                .put("frequencySlot", jtext(cfg.jFrequencySlot)).put("role", jtext(cfg.jRole))
                .put("channelName", jtext(cfg.jConfigId)).put("key", jtext(cfg.jPsk)).obj())
            .put("nucleus", OrgObj()
                .put("ssid", jtext(cfg.jNetworkSsid))
                .put("password", nul(jtext(cfg.jNetworkPassword)))
                .put("cot", OrgObj().put("sa", JsonPrimitive(COT_SA)).put("chat", JsonPrimitive(COT_CHAT)).obj())
                .put("meshName", JsonNull)        // ⏳ Natak, Thursday
                .put("wifiChannel", JsonNull)     // ⏳ Natak, Thursday
                .put("takSdkVersion", JsonNull).obj())  // ⏳ Natak, Thursday
            .obj()

        // ---- rideData: route identity, trailhead, GPX, recipe -------------------------------------
        val routeId = r[3]
        val rideData = OrgObj()
        if (routeId == null) {
            missing += "route"
            rideData.put("route", JsonNull).put("gpx", JsonNull)
                .put("narrative", JsonNull).put("recipe", JsonNull)
        } else {
            val routeGpx = env.spatial.routeGpx(routeId)
            val geomHash = env.spatial.routeGeomHash(routeId)
            if (routeGpx == null || geomHash == null) missing += "route"
            rideData.put("route", OrgObj()
                .put("type", JsonPrimitive("route"))                    // identity = type + hash (09-22)
                .put("geomHash", nul(txt(geomHash)))
                .put("name", nul(txt(routeGpx?.first))).obj())

            val recipe = env.spatial.routeRecipe(routeId)?.let { parseObject(it) }
            val th = trailheadWpt(recipe)
            // THTEXT-2026-09-27 (Fred): INTERIM -- a trailhead DESCRIBED at the front of the description also counts.
            if (th == null && !(r[7] ?: "").trimStart().startsWith("Trailhead:")) missing += "trailhead"

            val wpts = StringBuilder()
            if (th != null) wpts.append(th)
            rideWaypointIds(rideId).forEach { wpId ->
                env.spatial.waypointGpx(wpId)?.let { g ->
                    WPT.findAll(g).forEach { m -> wpts.append(m.value).append('\n') }
                }
            }
            rideData.put("gpx", nul(txt(routeGpx?.second?.let { mergeWpts(it, wpts.toString()) })))
            rideData.put("recipe", nul(recipe))
            rideData.put("narrative",
                if (INCLUDE_NARRATIVE)
                    nul(env.spatial.routeNotes(routeId)?.let { n ->
                        JsonObject(LinkedHashMap<String, JsonElement>(parseObject(n)).also { it.remove("recipe") })
                    })
                else JsonNull)
        }

        val json = OrgObj()
            .put("kind", JsonPrimitive(KIND))
            .put("formatVersion", JsonPrimitive(FORMAT_VERSION))
            .put("ride", ride.obj())
            .put("originator", originator.obj())
            .put("network", network)
            .put("rideData", rideData.obj())
            .obj()
        log.i { "RIDEJSON-2026-09-23: $rideId built, missing=$missing" }
        return Result(json, missing)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private val WPT = Regex("<wpt\\b[\\s\\S]*?</wpt>")

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** The trailhead = the recipe's anchor (the waypoint the route was anchored on). */
    private fun trailheadWpt(recipe: JsonObject?): String? {
        if (recipe == null || !recipe.has("anchorLat") || !recipe.has("anchorLon")) return null
        val lat = recipe.optDouble("anchorLat", Double.NaN)
        val lon = recipe.optDouble("anchorLon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        val name = recipe.optString("anchorName", "").ifBlank { "Trailhead" }
        return "<wpt lat=\"$lat\" lon=\"$lon\"><name>${esc(name)}</name><type>trailhead</type></wpt>\n"
    }

    private fun rideWaypointIds(rideId: String): List<String> {
        val db = env.dataDb() ?: return emptyList()
        return try {
            rawQuery(db, "SELECT waypoint_id FROM ride_waypoints WHERE ride_id = ? ORDER BY seq",
                listOf(rideId)) { c ->
                val out = mutableListOf<String>()
                while (c.step()) out += str(c, 0)!!   // STEP14: waypoint_id is NOT NULL (ensureSchema's table)
                out
            }
        } catch (e: Exception) {
            emptyList()   // table not created yet on this tablet = no waypoints attached
        }
    }

    // ---- RIDEFILE-2026-09-23: the ride JSON is a STORED FILE ----------------------------------
    /** GroupTrack storage / rides / <rideId>.json -- one file per ride, on every device that holds it. */
    fun rideFile(fs: RideFiles, rideId: String): String =
        fs.child(fs.ridesDir().also { fs.mkdirs(it) }, "$rideId.json")

    /**
     * Builds the ride JSON and STORES it. Called on every Save (and on a date change after
     * distribution). Send attaches this file, apply reads it, import stores a received file here
     * unchanged. Atomic: .tmp, then rename -- a half-written ride file never exists.
     * Returns what is missing (empty = complete), or null when nothing was written.
     */
    fun save(fs: RideFiles, rideId: String): List<String>? {
        val r = build(fs, rideId) ?: return null
        return try {
            val f = rideFile(fs, rideId)
            val tmp = fs.sibling(f, fs.name(f) + ".tmp")
            fs.writeText(tmp, env.packageText(r.text))   // STEP14: r.json.toString(2) -- the platform's org.json text
            if (fs.exists(f)) fs.delete(f)
            if (!fs.rename(tmp, f)) {
                log.e { "save: rename failed for ${fs.name(f)}" }; return null
            }
            log.i { "RIDEFILE-2026-09-23: ${fs.name(f)} written, missing=${r.missing}" }
            r.missing
        } catch (e: Exception) {
            log.e { "save failed: ${e.message}" }; null
        }
    }

    /** One GPX: waypoints go after <metadata> (or the <gpx> tag), before the route — GPX order. */
    // STEP14: public (2.7e: internal to the app module, which now delegates here).
    fun mergeWpts(routeGpx: String, wpts: String): String {
        if (wpts.isBlank()) return routeGpx
        val meta = routeGpx.indexOf("</metadata>")
        val at = if (meta >= 0) meta + "</metadata>".length
                 else routeGpx.indexOf('>', routeGpx.indexOf("<gpx")).let { if (it < 0) -1 else it + 1 }
        if (at <= 0) return routeGpx
        return routeGpx.substring(0, at) + "\n" + wpts + routeGpx.substring(at)
    }

    // STEP14E-2026-11-08 (Fred 12:48): the two GPX attachments for riders WITHOUT GroupTrack, added with the switch so the package's
    // standard (what a send carries) changes only with a database change. They are cut from the package's OWN gpx -- the same bytes
    // the package carries -- so they can never disagree with it: the route alone, and the trailhead + ride waypoints alone.
    /** CODE RULE 1: [waypoints] is null when the ride carries no waypoints, or the GPX has no <gpx> tag to copy -- no file is attached. */
    class GpxAttachments(val route: String, val waypoints: String?)

    fun splitGpx(gpx: String): GpxAttachments {
        val w = WPT.findAll(gpx).map { it.value }.toList()
        if (w.isEmpty()) return GpxAttachments(gpx, null)
        val route = Regex("<wpt\\b[\\s\\S]*?</wpt>\\n?").replace(gpx, "")
        // the header up to where mergeWpts puts waypoints: after </metadata>, else after the <gpx ...> tag
        val meta = route.indexOf("</metadata>")
        val at = if (meta >= 0) meta + "</metadata>".length
                 else route.indexOf('>', route.indexOf("<gpx")).let { if (it < 0) -1 else it + 1 }
        if (at <= 0) return GpxAttachments(route, null)
        return GpxAttachments(route, route.substring(0, at) + "\n" + w.joinToString("\n") + "\n</gpx>\n")
    }
}

// =====================================================================================================================================
// ---- RideApplySource ----------------------------------------------------------------------------------------------------------------
// =====================================================================================================================================

/**
 * RIDESEAM-2026-09-24 — the ride list for the standalone T1000-E write ("Apply Ride"), read from the
 * STORED ride JSONs (rides/<rideId>.json -- rides created on this tablet, and later imported ones, which are
 * stored in the same folder). The field names match what ConvoyApplyRadioScreen already reads from
 * the old event store (eventId, eventName, eventDate, channelName, channelPsk), so the screen changes
 * only where the list is loaded. The LoRa values come from the ride's network.mesh, so a ride on a
 * different preset writes that preset. "Apply Master Config" is untouched: it still applies the asset.
 */
data class RideForApply(
    val eventId: String,
    val eventName: String,
    val eventDate: String,
    val channelName: String,
    val channelPsk: String,          // base64, as the old store held it and the builder decodes it
    val loraRegion: String,
    val loraModemPreset: String,
    val loraHopLimit: Int,
    val loraTxPower: Int,
    val loraChannelNum: Int
)

object GtRideApply {

    private val log = gtLogger("RideApplySource")   // STEP14: android.util.Log.w("RideApplySource", ..)

    /**
     * DATEFILTER-2026-09-24 (Fred): which rides the Send and Apply lists offer. Today or later by
     * default; with includeRecent, the last 30 days too (the ride file's own lifetime). A date that
     * cannot be read is kept -- Send and Apply judge the ride themselves.
     */
    fun isCurrent(date: String, includeRecent: Boolean): Boolean {
        val d = parseDate(date) ?: return true
        val cutoff = GDate.parse(GtRideStore.env.today()).minusDays(if (includeRecent) 30L else 0L)   // STEP14: LocalDate.now()
        return !d.isBefore(cutoff)
    }

    /** Accepts 2026-09-30 and also older typed forms such as 2026-9-3. */
    private fun parseDate(s: String): GDate? = try {
        val p = s.trim().split("-").map { it.toInt() }
        if (p.size == 3) GDate.of(p[0], p[1], p[2]) else null   // STEP14: LocalDate.of -- null where it threw (caught below in 2.7e)
    } catch (e: Exception) { null }

    /** Every stored ride with a complete network block, earliest date first. Incomplete files are skipped. */
    // STEP14 CODE RULE 1 (2.7e's default): includeRecent = false is the Apply list (today or later); Send passes true.
    //  `fs` stands where 2.7e took the Context.
    fun loadAll(fs: RideFiles, includeRecent: Boolean = false): List<RideForApply> {
        GtRideStore.healFromFiles(fs) // RIDEHEAL-2026-09-26: every ride list sees a reconciled library
        val dir = fs.ridesDir()
        val files = fs.list(dir)?.filter { f -> fs.isFile(fs.child(dir, f)) && f.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                val j = parseObject(fs.readText(fs.child(dir, f)))
                if (j.optString("kind") != "grouptrack.ride") return@mapNotNull null
                val ride = j.optJSONObject("ride") ?: return@mapNotNull null
                val mesh = j.optJSONObject("network")?.optJSONObject("mesh") ?: return@mapNotNull null
                val name = mesh.optString("channelName", "")
                val key = mesh.optString("key", "")
                if (name.isBlank() || key.isBlank()) return@mapNotNull null
                RideForApply(
                    eventId = ride.optString("rideId", f.substringBeforeLast(".")),   // STEP14: f.nameWithoutExtension
                    eventName = ride.optString("name", "Ride"),
                    eventDate = ride.optString("date", ""),
                    channelName = name,
                    channelPsk = key,
                    loraRegion = mesh.optString("region", "US"),
                    loraModemPreset = mesh.optString("preset", "LONG_FAST"),
                    loraHopLimit = mesh.optInt("hopLimit", 3),
                    loraTxPower = mesh.optInt("txPower", 27),
                    loraChannelNum = mesh.optInt("frequencySlot", 0)
                )
            } catch (e: Exception) {
                log.w { "skipped $f: ${e.message}" }
                null
            }
        }.filter { isCurrent(it.eventDate, includeRecent) }.sortedBy { it.eventDate }   // DATEFILTER-2026-09-24
    }
}

// =====================================================================================================================================
// ---- RideImport (from ConvoyFileReceiver.kt) ----------------------------------------------------------------------------------------
// =====================================================================================================================================

/**
 * RIDEIMPORT3-2026-09-25 (Fred): THE ride import -- one path for an emailed ride (ConvoyFileReceiver) and for
 * Work with Rides -> Import a ride (a file picked from Downloads). Moved here UNCHANGED from the receiver's
 * RIDEIMPORT2 block: store the ride file as rides/<rideId>.json (tmp + rename), stage its GPX in gpx_staging,
 * load the real map sources, and offer it to the import panel. [bringForward] = true only from outside the app
 * (the email path), which must bring GroupTrack to the front; from the menu the app is already open.
 * Returns the message to show the rider.
 */
object GtRideImport {
    private const val TAG = "RideImport"
    private val log = gtLogger(TAG)

    // STEP14: `fs` stands where 2.7e took the Context (the rides folder, filesDir); [platform] does what stays platform work
    //  (MapSourceManager.init, RideImportLauncher.offer, bringing the app forward) at the same points, in the same order.
    fun importRide(fs: RideFiles, content: String, bringForward: Boolean, platform: RideImportPlatform): String {
        val rideJson = try { parseObject(content) } catch (e: Exception) { null }
        val rideObj = rideJson?.optJSONObject("ride")
        val rideId = rideObj?.optString("rideId", "")?.takeIf { it.isNotBlank() && it != "null" }
        val rideName = rideObj?.optString("name", "")?.takeIf { it.isNotBlank() && it != "null" } ?: "Ride"
        return if (rideJson == null || rideJson.optString("kind") != "grouptrack.ride" || rideId == null) {
            log.w { "RIDEIMPORT3-2026-09-25: not a readable GroupTrack ride file" }
            "That isn't a GroupTrack OG ride file."
        } else try {
            val dir = fs.ridesDir()
            fs.mkdirs(dir)   // RIDEMKDIR-2026-09-24: a tablet that never saved a ride has no rides folder yet
            val out = fs.child(dir, rideId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")
            val tmp = fs.child(dir, fs.name(out) + ".tmp")
            fs.writeText(tmp, content)
            if (fs.exists(out)) fs.delete(out)
            if (!fs.rename(tmp, out)) throw IllegalStateException("rename failed")
            val outLength = fs.length(out)   // STEP14: read here, where 2.7e's log line read out.length()
            log.i { "RIDEIMPORT3-2026-09-25: stored ${fs.name(out)} ($outLength bytes)" }
            val gpx = rideJson.optJSONObject("rideData")?.optString("gpx", "")
                ?.takeIf { it.isNotBlank() && it != "null" }
            if (gpx == null) {
                "Ride \"$rideName\" imported (it has no route yet)."
            } else {
                // The panel's own policy: staging holds only the current selection.
                val stage = fs.stagingDir()
                if (fs.exists(stage)) fs.list(stage)?.forEach { fs.delete(fs.child(stage, it)) }
                fs.mkdirs(stage)
                val g = fs.child(stage, rideName.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifBlank { "ride" } + ".gpx")
                fs.writeText(g, gpx)
                platform.initMapSources()   // MAPINIT-2026-09-24: real sources, even from cold
                platform.offer(g)
                if (bringForward) {
                    platform.bringForward()
                }
                "Ride \"$rideName\" imported \u2014 choose maps and tap IMPORT to add its route."
            }
        } catch (e: Exception) {
            log.e { "RIDEIMPORT3-2026-09-25: store failed: ${e.message}" }
            "Could not store the ride: ${e.message}"
        }
    }
}
