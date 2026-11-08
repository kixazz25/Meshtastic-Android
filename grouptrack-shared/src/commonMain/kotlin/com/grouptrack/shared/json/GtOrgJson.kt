package com.grouptrack.shared.json

import com.grouptrack.shared.text.GtJava
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/**
 * STEP15A-2026-11-08 (2.8 step 15a): Android's org.json, for the shared code -- the TEXT it writes and the VALUES it hands back.
 * 2.7e reads and writes JSON with org.json in the importers (TrailImporter, ImportConverter, the OSM ledger and history, the trail area
 * records, the route notes an imported GPX carries). A kotlinx JsonElement holds a number as the literal text it was parsed from
 * ("1.50"); org.json holds a boxed Integer / Long / Double and writes THAT back ("1.5"). This object keeps kotlinx elements and
 * answers every question the org.json way:
 *  - [parse]: kotlinx's parser; every number stays a literal whose org.json kind ([OrgNum]) is derived from its text exactly as
 *    Android's JSONTokener.readLiteral does (no '.' -> Long.parseLong, "0x" hex, a leading 0 = OCTAL, fitting Int -> Integer, else Long;
 *    failing that Double.valueOf; failing that an unquoted string).
 *  - [compact] / [pretty]: JSONObject.toString() / toString(2) -- JSONStringer: no spaces; with an indent each value on its own line and
 *    "key": value; empty {} []; quote, backslash AND "/" escaped; \t \b \n \r \f named; other controls \u00xx; numbers by
 *    JSONObject.numberToString (Android): -0.0 -> "-0", a double equal to its long -> the long ("1000"), else Double.toString.
 *  - [optString] / [valueText]: JSON.toString(Object) = String.valueOf(boxed value): a Double comes back as Double.toString ("1000.0"),
 *    JSON null as "null", an object or array as its compact text.
 *  - [optDouble] / [optInt] / [optLong] / [optBoolean]: JSON.toDouble / toInteger / toLong / toBoolean (strings are converted).
 *  - [num]: what JSONObject.put(k, double / long / int) stores (NaN / Infinity refused as org.json refuses them).
 * ⚠ REUSE NOTE (coordinator 11-08): GtRides (step 14) already reproduces org.json output with an orgValue/orgLiteral approach. That source
 *   was not in the 15a snapshot, so this is written from Android's org.json directly. If GtRides' helpers cover the same ground, make
 *   them call into this object (or this into them) so there is ONE copy -- see NOTES_15a.md. GtWrite.orgJson (step 10) writes number
 *   literals RAW (right for text org.json wrote itself; not for foreign input): new code uses [compact].
 * STEP15A-B-2026-11-08 (golden run 18:08): kotlinx hands an unquoted TRUE / FALSE / NULL through as a literal; it is now read as
 *   org.json reads it (any case: a boolean / JSONObject.NULL), written "true" / "false" / "null" -- as GtRides' orgLiteral already does.
 * ⚠ NOT REPRODUCED (kotlinx is strict, Android's JSONTokener is lenient): comments, single quotes, unquoted keys, '=' / ';'
 *   separators, and text after the first value. Android accepts those; [parse] throws (the golden run marks them EXPECTED). Server and GroupTrack JSON
 *   never carry them.
 */
@OptIn(ExperimentalSerializationApi::class)
object GtOrgJson {

    /** org.json's boxed kinds. [text] = the literal as parsed. */
    sealed class OrgNum {
        data class IntNum(val v: Int) : OrgNum()
        data class LongNum(val v: Long) : OrgNum()
        data class DoubleNum(val v: Double) : OrgNum()
        data class Unquoted(val text: String) : OrgNum()
    }

    fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    /** True for a primitive that is a boolean literal (kotlinx keeps them as unquoted "true"/"false"). */
    private fun isBool(p: JsonPrimitive) = !p.isString && (p.content.equals("true", ignoreCase = true) || p.content.equals("false", ignoreCase = true))
    /** An unquoted null literal kotlinx did not turn into JsonNull ("NULL", "Null"): org.json's JSONObject.NULL. */
    private fun isNullLit(p: JsonPrimitive) = p !is JsonNull && !p.isString && p.content.equals("null", ignoreCase = true)
    private fun boolText(p: JsonPrimitive) = if (p.content.equals("true", ignoreCase = true)) "true" else "false"

    /** Android JSONTokener.readLiteral for a non-string literal. */
    fun orgKind(literal: String): OrgNum {
        if (literal.indexOf('.') == -1) {
            var base = 10
            var number = literal
            if (number.startsWith("0x") || number.startsWith("0X")) { number = number.substring(2); base = 16 }
            else if (number.startsWith("0") && number.length > 1) { number = number.substring(1); base = 8 }
            val l = javaParseLong(number, base)
            if (l != null) return if (l <= Int.MAX_VALUE && l >= Int.MIN_VALUE) OrgNum.IntNum(l.toInt()) else OrgNum.LongNum(l)
        }
        javaParseDouble(literal)?.let { return OrgNum.DoubleNum(it) }
        return OrgNum.Unquoted(literal)
    }

    /** java.lang.Long.parseLong(s, radix): optional sign, at least one digit, no overflow. */
    private fun javaParseLong(s: String, radix: Int): Long? {
        if (s.isEmpty()) return null
        if ((s[0] == '-' || s[0] == '+') && s.length == 1) return null
        return s.toLongOrNull(radix)
    }

    private val PLAIN_DECIMAL = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")

    /**
     * java.lang.Double.valueOf(s): whitespace (chars <= ' ') trimmed, an optional trailing f/F/d/D, "NaN", "Infinity" with a sign.
     * ⚠ Java also reads hexadecimal floating literals ("0x1p3"); those are not handled here (never seen in GPS or server data).
     */
    fun javaParseDouble(s0: String): Double? {
        val s = s0.trim { it <= ' ' }
        if (s.isEmpty()) return null
        var body = s
        val last = body.last()
        if (last == 'f' || last == 'F' || last == 'd' || last == 'D') {
            val inner = body.dropLast(1)
            if (inner.isNotEmpty() && (inner.last().isDigit() || inner.last() == '.')) body = inner
        }
        val unsigned = body.removePrefix("+").removePrefix("-")
        if (unsigned == "NaN") return Double.NaN
        if (unsigned == "Infinity") return if (body.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        // plain decimal: digits [. digits] [e|E [sign] digits] -- refuse anything else so a platform parser's extras cannot leak in
        if (!PLAIN_DECIMAL.matches(body)) return null
        return body.toDoubleOrNull()
    }

    // ---- values (JSON.toString / toDouble / toInteger / toLong / toBoolean) -------------------------------------------------
    /** String.valueOf(the boxed value); null for a missing key (Java null). */
    fun valueText(e: JsonElement?): String? = when (e) {
        null -> null
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            e.isString -> e.content
            isBool(e) -> boolText(e)
            isNullLit(e) -> "null"
            else -> when (val k = orgKind(e.content)) {
                is OrgNum.IntNum -> k.v.toString()
                is OrgNum.LongNum -> k.v.toString()
                is OrgNum.DoubleNum -> GtJava.jd(k.v)
                is OrgNum.Unquoted -> k.text
            }
        }
        else -> compact(e)
    }

    fun optString(o: JsonObject?, key: String, fallback: String = ""): String = valueText(o?.get(key)) ?: fallback
    fun optString(a: JsonArray?, i: Int, fallback: String = ""): String = valueText(a?.getOrNull(i)) ?: fallback

    private fun toDouble(e0: JsonElement?): Double? {
        val e = e0 as? JsonPrimitive ?: return null
        if (e is JsonNull || isBool(e) || isNullLit(e)) return null
        if (e.isString) return javaParseDouble(e.content)
        return when (val k = orgKind(e.content)) {
            is OrgNum.IntNum -> k.v.toDouble(); is OrgNum.LongNum -> k.v.toDouble(); is OrgNum.DoubleNum -> k.v; is OrgNum.Unquoted -> javaParseDouble(k.text)
        }
    }
    fun optDouble(o: JsonObject?, key: String, fallback: Double = Double.NaN): Double = toDouble(o?.get(key)) ?: fallback
    fun optDouble(a: JsonArray?, i: Int, fallback: Double = Double.NaN): Double = toDouble(a?.getOrNull(i)) ?: fallback

    /** JSON.toInteger: Integer as is, other Number.intValue() (Double -> (int), saturating, NaN 0), String -> (int) Double.parseDouble. */
    fun optInt(o: JsonObject?, key: String, fallback: Int = 0): Int {
        val e = o?.get(key) as? JsonPrimitive ?: return fallback
        if (e is JsonNull || isBool(e) || isNullLit(e)) return fallback
        if (e.isString) return javaParseDouble(e.content)?.toInt() ?: fallback
        return when (val k = orgKind(e.content)) {
            is OrgNum.IntNum -> k.v; is OrgNum.LongNum -> k.v.toInt(); is OrgNum.DoubleNum -> k.v.toInt(); is OrgNum.Unquoted -> javaParseDouble(k.text)?.toInt() ?: fallback
        }
    }
    fun optLong(o: JsonObject?, key: String, fallback: Long = 0L): Long {
        val e = o?.get(key) as? JsonPrimitive ?: return fallback
        if (e is JsonNull || isBool(e) || isNullLit(e)) return fallback
        if (e.isString) return javaParseDouble(e.content)?.toLong() ?: fallback
        return when (val k = orgKind(e.content)) {
            is OrgNum.IntNum -> k.v.toLong(); is OrgNum.LongNum -> k.v; is OrgNum.DoubleNum -> k.v.toLong(); is OrgNum.Unquoted -> javaParseDouble(k.text)?.toLong() ?: fallback
        }
    }
    /** JSON.toBoolean: a boolean, or the string "true"/"false" in any case. */
    fun optBoolean(o: JsonObject?, key: String, fallback: Boolean = false): Boolean {
        val e = o?.get(key) as? JsonPrimitive ?: return fallback
        if (e is JsonNull || isNullLit(e)) return fallback
        if (isBool(e)) return boolText(e) == "true"
        if (e.isString) { if (e.content.equals("true", true)) return true; if (e.content.equals("false", true)) return false }
        return fallback
    }

    // ---- building (JSONObject.put) ------------------------------------------------------------------------------------------
    /** put(k, double): checkDouble refuses NaN / Infinity (JSONException) -- IllegalArgumentException here. Stored as Double. */
    fun num(d: Double): JsonElement {
        require(!d.isNaN() && !d.isInfinite()) { "Forbidden numeric value: $d" }
        return JsonUnquotedLiteral(GtJava.jd(d))   // jd always has '.' or 'E' -> orgKind = Double, as the box was
    }
    fun num(l: Long): JsonElement = JsonUnquotedLiteral(l.toString())
    fun num(i: Int): JsonElement = JsonUnquotedLiteral(i.toString())
    fun str(s: String): JsonElement = JsonPrimitive(s)
    fun bool(b: Boolean): JsonElement = JsonPrimitive(b)
    val NULL: JsonElement get() = JsonNull

    // ---- writing ------------------------------------------------------------------------------------------------------------
    /** JSONObject.numberToString (Android) for a number literal of kind [k]. */
    fun numberText(k: OrgNum): String = when (k) {
        is OrgNum.IntNum -> k.v.toString()
        is OrgNum.LongNum -> k.v.toString()
        is OrgNum.DoubleNum -> {
            val d = k.v
            require(!d.isNaN() && !d.isInfinite()) { "Forbidden numeric value: $d" }
            if (d == 0.0 && 1.0 / d < 0) "-0"
            else { val l = d.toLong(); if (d == l.toDouble()) l.toString() else GtJava.jd(d) }
        }
        is OrgNum.Unquoted -> quote(k.text)   // an unquoted string is a String to org.json
    }

    fun compact(e: JsonElement): String = StringBuilder().also { write(it, e, null, 0) }.toString()
    /** toString(indentSpaces) -- JSONObject.toString(2) / JSONArray.toString(2). */
    fun pretty(e: JsonElement, indentSpaces: Int = 2): String = StringBuilder().also { write(it, e, " ".repeat(indentSpaces), 0) }.toString()

    private fun newline(sb: StringBuilder, indent: String?, depth: Int) {
        if (indent == null) return
        sb.append('\n'); repeat(depth) { sb.append(indent) }
    }

    /** JSONStringer: [depth] = how many scopes are open around [e]'s contents once it is opened. */
    private fun write(sb: StringBuilder, e: JsonElement, indent: String?, depth: Int) {
        when (e) {
            is JsonObject -> {
                sb.append('{')
                var first = true
                for ((k, v) in e) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, indent, depth + 1)
                    sb.append(quote(k))
                    sb.append(if (indent == null) ":" else ": ")
                    write(sb, v, indent, depth + 1)
                }
                if (!first) newline(sb, indent, depth)
                sb.append('}')
            }
            is JsonArray -> {
                sb.append('[')
                e.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    newline(sb, indent, depth + 1)
                    write(sb, v, indent, depth + 1)
                }
                if (e.isNotEmpty()) newline(sb, indent, depth)
                sb.append(']')
            }
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> when {
                e.isString -> sb.append(quote(e.content))
                isBool(e) -> sb.append(boolText(e))
                isNullLit(e) -> sb.append("null")
                else -> sb.append(numberText(orgKind(e.content)))
            }
        }
    }

    /** JSONStringer.string(). */
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) when (c) {
            '"', '\\', '/' -> sb.append('\\').append(c)
            '\t' -> sb.append("\\t"); '\b' -> sb.append("\\b"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\u000c' -> sb.append("\\f")
            else -> if (c.code <= 0x1F) sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }
}
