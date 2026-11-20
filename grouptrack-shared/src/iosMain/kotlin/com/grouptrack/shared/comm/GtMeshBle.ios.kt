/*
 * Copyright (c) 2026 GroupTrack
 */
package com.grouptrack.shared.comm

import com.juul.kable.Peripheral
import org.meshtastic.sdk.transport.ble.BleConstants
import org.meshtastic.sdk.transport.ble.BleTransport

/**
 * IOSSDK-2026-11-20 v2 (Path B, step 1): the iPad's BLE link to a Meshtastic radio, on the official SDK.
 *
 * Written against the PINNED 0.2.0 API, not main: at 0.2.0 transport-ble exposes only [BleConstants] and
 * [BleTransport], so radio DISCOVERY is ours to write with Kable (main has a BleScanner; it is unreleased and
 * Fred's rule keeps us off alpha versions). This file is the transport half -- the piece CommLink.connect
 * needs -- and is deliberately the smallest thing that proves sdk-core, sdk-transport-ble and Kable all
 * resolve and compile for the iOS simulator together.
 *
 * On CommLink's own terms (COMMRADIOS-2026-10-11): `address` here is the radioId GroupTrack already keys
 * radios by, and the SDK uses it for "storage identity keying" -- the same identity, so no second notion of
 * which radio this is. Scanning, the radios list and scanProblem come next, with Kable's Scanner.
 */
internal object GtMeshBle {

    /** The service a Meshtastic radio advertises -- from the SDK, never a UUID copied into our source. */
    val meshServiceUuid = BleConstants.MESH_SERVICE_UUID

    /**
     * The transport for one radio. [address] is GroupTrack's radioId for it.
     * Not connected here: the SDK's RadioClient owns connect/disconnect, so the lifecycle stays in one place.
     */
    fun transportFor(peripheral: Peripheral, address: String): BleTransport =
        BleTransport(peripheral = peripheral, address = address)
}
