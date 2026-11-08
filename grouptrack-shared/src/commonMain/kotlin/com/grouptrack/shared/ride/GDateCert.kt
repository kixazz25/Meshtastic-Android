package com.grouptrack.shared.ride

/*
 * STEP14C-2026-11-08: TEST-ONLY door to GDate for the app's ride certification (GtCertRides case 7: GDate against java.time on the Droid).
 * GDate is `internal` to grouptrack-shared and stays so; this object is in the same module, so it can reach it. Nothing else uses it.
 * Every function answers with the date's toString (LocalDate.toString's rules), or "ERR" where GDate threw / of() gave null --
 * java.time's side of the comparison answers "ERR" where it threw, so only the OUTCOME is compared, not the exception class
 * (PORT_NOTES (a) row 5: java.time threw DateTimeException, GDate throws IllegalArgumentException / ArithmeticException).
 * ⚠ TEST-ONLY (CODE RULE 3): remove with GtCertRun before 2.8 is released.
 */
object GDateCert {
    fun parse(t: String): String {
        return try { GDate.parse(t).toString() } catch (e: Exception) { "ERR" }
    }

    fun of(y: Int, m: Int, d: Int): String {
        val g = GDate.of(y, m, d) ?: return "ERR"
        return g.toString()
    }

    fun plusDays(y: Int, m: Int, d: Int, n: Long): String {
        val g = GDate.of(y, m, d) ?: return "ERR"
        return try { g.plusDays(n).toString() } catch (e: Exception) { "ERR" }
    }

    fun minusDays(y: Int, m: Int, d: Int, n: Long): String {
        val g = GDate.of(y, m, d) ?: return "ERR"
        return try { g.minusDays(n).toString() } catch (e: Exception) { "ERR" }
    }

    fun toEpochDay(y: Int, m: Int, d: Int): String {
        val g = GDate.of(y, m, d) ?: return "ERR"
        return g.toEpochDay().toString()
    }

    fun ofEpochDay(e: Long): String {
        return try { GDate.ofEpochDay(e).toString() } catch (x: Exception) { "ERR" }
    }

    /** parse(a).isBefore(parse(b)) as "true" / "false", or "ERR" when either does not parse. */
    fun isBefore(a: String, b: String): String {
        return try { GDate.parse(a).isBefore(GDate.parse(b)).toString() } catch (e: Exception) { "ERR" }
    }
}
