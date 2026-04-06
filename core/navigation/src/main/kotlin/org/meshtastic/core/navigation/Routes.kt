/*
 * Copyright (c) 2025-2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.core.navigation

import kotlinx.serialization.Serializable

const val DEEP_LINK_BASE_URI = "meshtastic://meshtastic"

interface Route

interface Graph : Route

object ChannelsRoutes {
    @Serializable data object ChannelsGraph : Graph

    @Serializable data object Channels : Route
}

object ConnectionsRoutes {
    @Serializable data object ConnectionsGraph : Graph

    @Serializable data object Connections : Route
}

object ContactsRoutes {
    @Serializable data object ContactsGraph : Graph

    @Serializable data object Contacts : Route

    @Serializable data class Messages(val contactKey: String, val message: String = "") : Route

    @Serializable data class Share(val message: String) : Route

    @Serializable data object QuickChat : Route
}

object MapRoutes {
    @Serializable data class Map(val waypointId: Int? = null) : Route
}

object NodesRoutes {
    @Serializable data object NodesGraph : Graph

    @Serializable data object Nodes : Route

    @Serializable data class NodeDetailGraph(val destNum: Int? = null) : Graph

    @Serializable data class NodeDetail(val destNum: Int? = null) : Route
}

object NodeDetailRoutes {
    @Serializable data class DeviceMetrics(val destNum: Int) : Route

    @Serializable data class NodeMap(val destNum: Int) : Route

    @Serializable data class PositionLog(val destNum: Int) : Route

    @Serializable data class EnvironmentMetrics(val destNum: Int) : Route

    @Serializable data class SignalMetrics(val destNum: Int) : Route

    @Serializable data class PowerMetrics(val destNum: Int) : Route

    @Serializable data class TracerouteLog(val destNum: Int) : Route

    @Serializable data class TracerouteMap(val destNum: Int, val requestId: Int, val logUuid: String? = null) : Route

    @Serializable data class HostMetricsLog(val destNum: Int) : Route

    @Serializable data class PaxMetrics(val destNum: Int) : Route

    @Serializable data class NeighborInfoLog(val destNum: Int) : Route
}

object SettingsRoutes {
    @Serializable data class SettingsGraph(val destNum: Int? = null) : Graph

    @Serializable data class Settings(val destNum: Int? = null) : Route

    @Serializable data object DeviceConfiguration : Route

    @Serializable data object ModuleConfiguration : Route

    @Serializable data object Administration : Route

    // region radio Config Routes

    @Serializable data object User : Route

    @Serializable data object ChannelConfig : Route

    @Serializable data object Device : Route

    @Serializable data object Position : Route

    @Serializable data object Power : Route

    @Serializable data object Network : Route

    @Serializable data object Display : Route

    @Serializable data object LoRa : Route

    @Serializable data object Bluetooth : Route

    @Serializable data object Security : Route

    // endregion

    // region module config routes

    @Serializable data object MQTT : Route

    @Serializable data object Serial : Route

    @Serializable data object ExtNotification : Route

    @Serializable data object StoreForward : Route

    @Serializable data object RangeTest : Route

    @Serializable data object Telemetry : Route

    @Serializable data object CannedMessage : Route

    @Serializable data object Audio : Route

    @Serializable data object RemoteHardware : Route

    @Serializable data object NeighborInfo : Route

    @Serializable data object AmbientLighting : Route

    @Serializable data object DetectionSensor : Route

    @Serializable data object Paxcounter : Route

    @Serializable data object StatusMessage : Route

    @Serializable data object TrafficManagement : Route

    @Serializable data object TAK : Route

    // endregion

    // region advanced config routes

    @Serializable data object CleanNodeDb : Route

    @Serializable data object DebugPanel : Route

    @Serializable data object About : Route

    @Serializable data object FilterSettings : Route

    // endregion
}

object FirmwareRoutes {
    @Serializable data object FirmwareGraph : Graph

    @Serializable data object FirmwareUpdate : Route
}

object ConvoyRoutes {
    @Serializable data object Convoy : Route
    @Serializable data object ConvoySettings : Route
    @Serializable data object ConvoyEnrollment : Route
    @Serializable data object ConvoyEmailGate   : Route
    @Serializable data object ConvoyCreateEvent : Route
    @Serializable data object ConvoySettingsPanel : Route
    @Serializable data object ConvoyMasterCapture : Route
    @Serializable data object ConvoyMasterSuccess : Route
    @Serializable data object ConvoyApplyList     : Route
    @Serializable data object ConvoyApplyRadio    : Route
    @Serializable data object ConvoyWriteArchive  : Route
    @Serializable data object ConvoyWriteDevice   : Route
    @Serializable data object ConvoyWriteLoRa     : Route
    @Serializable data object ConvoyWritePosition : Route
    @Serializable data object ConvoyWriteChannel  : Route
    @Serializable data object ConvoyWriteVerify   : Route
    @Serializable data object ConvoyReconnectWait : Route
    @Serializable data object ConvoyArchiveRestore  : Route
    @Serializable data object ConvoyTransferRide     : Route
    @Serializable data object ConvoySignIn           : Route  // V3 Phase A
    @Serializable data object ConvoySubscription      : Route  // V3 Phase B
    @Serializable data object ConvoyDashboard         : Route  // V3 Phase B
    @Serializable data object ConvoyFieldRadio        : Route  // V3 Phase B
    @Serializable data object ConvoyTerms            : Route  // V3 Phase B
    @Serializable data object ConvoyPrivacy          : Route  // V3 Phase B
    @Serializable data object ConvoyMyRides          : Route  // V3 Phase B
    @Serializable data object ConvoyCreateRide       : Route  // V3 Phase B
    @Serializable data object ConvoyRideDetail       : Route  // V3 Phase B
    @Serializable data object ConvoyProfile          : Route  // V3 Phase B
    @Serializable data object ConvoyExplore          : Route  // V3 Phase B
    @Serializable data object ConvoyTracks           : Route  // V3 Phase B
}
