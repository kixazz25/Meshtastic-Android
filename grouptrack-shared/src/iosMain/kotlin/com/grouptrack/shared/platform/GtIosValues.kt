package com.grouptrack.shared.platform

import com.grouptrack.shared.imports.GtTrailClass
import com.grouptrack.shared.net.GtRandom
import com.grouptrack.shared.state.GtQueueRow
import com.grouptrack.shared.trail.GtTrailUse
import platform.posix.arc4random

/**
 * CERTPATHS-2026-11-16 batch 1: the iPad's PURE-VALUE services -- no paths, no environment.
 * Environment values are deliberately last and get their own harness (Fred 11-16 09:53).
 */

/** TrailClassifier.useOf, off the shared table. Identical result on both platforms, by construction. */
class IosTrailClass : GtTrailClass {
    override fun useOf(category: String): String = GtTrailUse.useOf(category)
}

/**
 * The iPad's row for the state-data queue.
 *
 * Graduated from Cert17e2.MapRow, whose own comment reads "a row on a plain map (the iPad; the
 * Droid's NEW uses its JSONObject rows)" -- the shape was already designed and already compiles.
 * Cert code is removed with GtCertRun before 2.8 ships (CODE RULE 3), so production needs this copy.
 *
 * ⛔ THE COERCION IS THE CONTRACT, NOT A CONVENIENCE. org.json's JSONObject, which the Droid wraps,
 * does this and the shared queue depends on it:
 *   optString on a MISSING key returns "" -- never null, never a throw
 *   optBoolean on a MISSING key returns false
 *   optBoolean on the STRING "true" returns TRUE
 * The queue writes _stop as a Boolean (GtStateDataQueue:103) and reads it back with optBoolean
 * (:128, :129), while every other field is a String, so BOTH types live in one map. A map of plain
 * Strings would read _stop correctly by luck and a string "true" wrongly.
 *
 * ⚠ Android's JsonRow wraps the SAME JSONObject the import record holds -- "the same object, not a
 * copy" -- so the queue's status changes are visible to the record. This map-backed row has no such
 * owner until the iPad has a state-import record; whatever constructs it there must hold the same
 * instance, or the record will not see the queue's writes.
 */
class IosQueueRow(initial: Map<String, String> = emptyMap()) : GtQueueRow {
    private val m = LinkedHashMap<String, Any>(initial)

    override fun optString(key: String): String = m[key]?.toString() ?: ""
    override fun optBoolean(key: String): Boolean = m[key] == true || m[key] == "true"
    override fun put(key: String, value: String) { m[key] = value }
    override fun put(key: String, value: Boolean) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }

    /** ⚠ TEST-ONLY, for the harness: the row's fields, so a leg can be compared. */
    fun snapshot(): Map<String, String> = m.mapValues { it.value.toString() }
}

/**
 * The iPad's random source.
 *
 * ⛔ THIS IS THE ONE SERVICE THAT CANNOT BE VALUE-COMPARED ACROSS PLATFORMS, and it must not be
 * treated as a port defect when it differs. Android uses java.security.SecureRandom; shared code
 * uses it for newKey() (32 bytes -> GtBase64) and pick() (characters from an alphabet), i.e. radio
 * NETWORK KEYS. The existing certs already avoid the problem by injecting java.util.Random(seed) on
 * BOTH sides (GtNetworks.kt:12), so the real generator has never been certified anywhere. Its
 * harness is a PROPERTY test: in range, right length, not constant.
 *
 * ⚠ CRYPTOGRAPHIC CHOICE, STATED RATHER THAN BURIED: arc4random is Darwin's CSPRNG, which is the
 * correct strength for a key. kotlin.random.Random would compile with less ceremony and is NOT
 * cryptographically secure -- it is not used here on purpose. SecRandomCopyBytes is the other right
 * answer; arc4random is chosen because it needs no pointer interop, and unproven Foundation interop
 * written from documentation is what broke the iOS build on 11-15.
 */
class IosRandom : GtRandom {

    /**
     * java.util.Random.nextInt(bound): uniform over [0, bound).
     * ⚠ REJECTION SAMPLING, not `% bound`. Plain modulo biases the low values whenever bound does not
     * divide 2^32, which for a key alphabet is a real skew. threshold is the largest exact multiple
     * of bound, so the accepted range holds whole cycles only.
     */
    override fun nextInt(bound: Int): Int {
        require(bound > 0) { "TRAILUSE/IosRandom: bound must be positive, was " + bound }
        val b = bound.toUInt()
        val threshold = (UInt.MAX_VALUE / b) * b
        while (true) {
            val r = arc4random()
            if (r < threshold) return (r % b).toInt()
        }
    }

    /** java.util.Random.nextBytes: fills the whole array, four bytes per draw. */
    override fun nextBytes(b: ByteArray) {
        var i = 0
        while (i < b.size) {
            var r = arc4random()
            var k = 0
            while (k < 4 && i < b.size) {
                b[i] = (r and 0xFFu).toByte()
                r = r shr 8
                i++
                k++
            }
        }
    }
}
