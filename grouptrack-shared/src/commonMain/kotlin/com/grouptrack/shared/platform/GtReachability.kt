package com.grouptrack.shared.platform

/**
 * REACHABILITY-2026-11-21 (2.8b, E - STATUS): is there internet, and tell me when that changes.
 *
 * WHY IT IS WATCHABLE AND NOT A BOOLEAN. Android's own note on the code this replaces:
 * "⚠ A CALLBACK, NOT A ONE-TIME READ: a rider who loses signal mid-ride has to see the button change
 * without restarting." Two consumers need the EVENT, not the value: E's status toggle changes its
 * wording, and the map switches itself to local tiles when the network goes (GroupTrackScreen.kt:445) --
 * while coming back online deliberately does NOT switch back, because that is the rider's choice.
 *
 * WHAT THE ANSWER MEANS, precisely, because the two platforms must agree: "there is a default network
 * and it claims internet capability" -- Android's
 * `activeNetwork` + `NET_CAPABILITY_INTERNET`. It is NOT "a server answered". A captive-portal wifi
 * reports TRUE here, exactly as it does on Android today. Matching Android's answer is the point; a
 * better answer on one platform would be a difference, not an improvement.
 *
 * ⛔ NO POLICY HERE. This says whether the internet is there. What to DO about being offline belongs to
 * the caller -- E refuses a toggle and says the map is using downloaded maps, J would refuse a download,
 * and every error message uses it to tell "offline" apart from "failed". One question, one boolean.
 */
interface GtReachability {

    /** The current answer. Safe to call often; implementations keep the last known state. */
    fun online(): Boolean

    /**
     * Start reporting changes. [onChange] is called with the new value when the default network comes or
     * goes -- not on every packet. Calling it twice replaces the watcher rather than stacking one.
     */
    fun watch(onChange: (Boolean) -> Unit)

    /** Stop reporting. Android unregisters its network callback here; iOS cancels its path monitor. */
    fun stopWatching()
}
