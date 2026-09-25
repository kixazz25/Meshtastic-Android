package org.meshtastic.core.data.manager

import com.github.luben.zstd.ZstdDecompressCtx
import com.github.luben.zstd.ZstdDictDecompress
import org.meshtastic.proto.CotType
import org.meshtastic.proto.Position
import org.meshtastic.proto.TAKPacketV2

/**
 * TAKV2-2026-09-25 (GroupTrack) -- decodes a TAK V2 wire payload exactly as TAKPacket-SDK v0.9.1 specifies
 * (WIRE_FORMAT.md, section 9) -- the SDK Natak's Nucleus bridge uses (V3_OS pyproject.toml pins v0.9.1).
 *
 *   byte 0     flags: 0xFF = raw TAKPacketV2 protobuf follows; otherwise bits 0-5 = dictionary id
 *   bytes 1..N zstd frame body compressed with that dictionary, the 4-byte zstd magic STRIPPED
 *
 * The SDK's own Kotlin build needs Kotlin 2.4 (its codec, kzstd, is built with 2.4); this app is on 2.3, so
 * step 4 uses zstd-jni -- the reference zstd -- instead. zstd is a standard format: any correct decoder gives
 * the same bytes, which the SDK's golden frames prove. The protobuf is decoded with OUR Wire classes
 * (protobufs v2.7.26, the version the SDK is built against), so no SDK proto classes enter the app.
 *
 * The two dictionaries are the SDK's files, byte-identical, shipped as Java resources under /tak/.
 */
object TakV2Decoder {
    const val DICT_ID_NON_AIRCRAFT = 0x00
    const val DICT_ID_AIRCRAFT = 0x01
    const val DICT_ID_UNCOMPRESSED = 0xFF
    const val MAX_DECOMPRESSED_SIZE = 4096
    /**
     * "No altitude" sentinels: ATAK sends hae=9999999, WebTAK 999999 (SDK golden frame pli_webtak).
     * Anything at or above this is treated as no altitude.
     */
    const val NO_ALTITUDE_MIN = 999_999

    private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    private val dictionaries: Map<Int, ZstdDictDecompress> by lazy {
        mapOf(
            DICT_ID_NON_AIRCRAFT to ZstdDictDecompress(loadDictionary("dict_non_aircraft.zstd")),
            DICT_ID_AIRCRAFT to ZstdDictDecompress(loadDictionary("dict_aircraft.zstd")),
        )
    }

    private fun loadDictionary(name: String): ByteArray =
        TakV2Decoder::class.java.getResourceAsStream("/tak/$name")?.use { it.readBytes() }
            ?: throw IllegalStateException("TAK dictionary missing: /tak/$name")

    /** Wire payload -> TAKPacketV2 protobuf bytes (WIRE_FORMAT.md section 9, steps 2-5). Throws on bad input. */
    fun unwrap(wire: ByteArray): ByteArray {
        require(wire.size >= 2) { "payload too short: ${wire.size} bytes" }
        val flags = wire[0].toInt() and 0xFF
        val body = wire.copyOfRange(1, wire.size)
        if (flags == DICT_ID_UNCOMPRESSED) {
            require(body.size <= MAX_DECOMPRESSED_SIZE) { "raw payload ${body.size} exceeds $MAX_DECOMPRESSED_SIZE" }
            return body
        }
        val dictId = flags and 0x3F
        val dictionary = dictionaries[dictId] ?: throw IllegalArgumentException("unknown dictionary id $dictId")
        val framed = ByteArray(ZSTD_MAGIC.size + body.size)
        ZSTD_MAGIC.copyInto(framed, 0)
        body.copyInto(framed, ZSTD_MAGIC.size)
        // The 4096-byte buffer IS the size cap: output that would exceed it makes zstd throw.
        val out = ByteArray(MAX_DECOMPRESSED_SIZE)
        val n = ZstdDecompressCtx().use { ctx ->
            ctx.loadDict(dictionary)
            ctx.decompressByteArray(out, 0, out.size, framed, 0, framed.size)
        }
        return out.copyOf(n)
    }

    /** Wire payload -> TAKPacketV2 (steps 2-6). Throws on malformed input; callers log and drop. */
    fun decode(wire: ByteArray): TAKPacketV2 = TAKPacketV2.ADAPTER.decode(unwrap(wire))

    /**
     * A POSITION report = a UNIT event (CoT type "a-...") with NO payload arm set. In V2, PLI is the implicit
     * payload (the old `bool pli`, tag 30, was dropped), so any arm -- chat, a marker, a route, a shape... --
     * is not a position. The unit test excludes other arm-less events such as a delete (t-x-d-d).
     * Proven on all 47 SDK golden frames: exactly the 7 pli_* frames pass.
     */
    fun isPosition(t: TAKPacketV2): Boolean = isUnit(t) && noPayloadArm(t)

    private fun isUnit(t: TAKPacketV2): Boolean =
        t.cot_type_id.name.startsWith("CotType_a_") ||
            (t.cot_type_id == CotType.CotType_Other && t.cot_type_str.startsWith("a-"))

    private fun noPayloadArm(t: TAKPacketV2): Boolean =
        t.chat == null && t.aircraft == null && t.raw_detail == null && t.shape == null &&
            t.marker == null && t.rab == null && t.route == null && t.casevac == null &&
            t.emergency == null && t.task == null && t.taktalk == null && t.taktalk_room == null

    /**
     * A V2 position as a Meshtastic Position, in Meshtastic's units: lat/lon copied (both 1e-7 degrees);
     * altitude HAE metres as sent, a sentinel (>= 999999) -> none; speed cm/s -> m/s; course degrees*100 ->
     * ground_track degrees*1e5; time = receive time. Null when there is no fix (0,0).
     */
    fun toPosition(t: TAKPacketV2, rxTimeMillis: Long): Position? {
        if (t.latitude_i == 0 && t.longitude_i == 0) return null
        return Position(
            latitude_i = t.latitude_i,
            longitude_i = t.longitude_i,
            altitude = if (t.altitude >= NO_ALTITUDE_MIN) null else t.altitude,
            ground_speed = t.speed / 100,
            ground_track = t.course * 1000,
            time = (rxTimeMillis / 1000L).toInt(),
        )
    }
}
