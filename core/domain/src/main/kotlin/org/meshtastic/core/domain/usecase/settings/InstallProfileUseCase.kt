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
package org.meshtastic.core.domain.usecase.settings

import android.util.Log
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.model.Position
import org.meshtastic.core.model.RadioController
import org.meshtastic.core.model.util.toChannelSet
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceProfile
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.LocalModuleConfig
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.User
import javax.inject.Inject

/** Use case for installing a device profile onto a radio. */
open class InstallProfileUseCase @Inject constructor(private val radioController: RadioController) {

    private val TAG = "InstallProfileUseCase"

    /**
     * Installs the provided [DeviceProfile] onto the radio at [destNum].
     *
     * BL-03 FIX: channel_url is now decoded and written inside the same
     * beginEditSettings/commitEditSettings transaction as the rest of the config.
     * This eliminates the need for a separate Stage 2 channel write + reboot.
     */
    suspend operator fun invoke(destNum: Int, profile: DeviceProfile, currentUser: User?) {
        radioController.beginEditSettings(destNum)

        installOwner(destNum, profile, currentUser)
        installConfig(destNum, profile.config)
        installFixedPosition(destNum, profile.fixed_position)
        installModuleConfig(destNum, profile.module_config)
        // BL-03 FIX: install channels + PSK inside the same transaction
        installChannels(destNum, profile.channel_url)

        radioController.commitEditSettings(destNum)
    }

    private suspend fun installOwner(destNum: Int, profile: DeviceProfile, currentUser: User?) {
        if (profile.long_name != null || profile.short_name != null) {
            currentUser?.let {
                val user = it.copy(
                    long_name  = profile.long_name ?: it.long_name,
                    short_name = profile.short_name ?: it.short_name,
                )
                radioController.setOwner(destNum, user, radioController.getPacketId())
            }
        }
    }

    private suspend fun installConfig(destNum: Int, config: LocalConfig?) {
        config?.let { lc ->
            lc.device?.let    { radioController.setConfig(destNum, Config(device = it),    radioController.getPacketId()) }
            lc.position?.let  { radioController.setConfig(destNum, Config(position = it),  radioController.getPacketId()) }
            lc.power?.let     { radioController.setConfig(destNum, Config(power = it),     radioController.getPacketId()) }
            lc.network?.let   { radioController.setConfig(destNum, Config(network = it),   radioController.getPacketId()) }
            lc.display?.let   { radioController.setConfig(destNum, Config(display = it),   radioController.getPacketId()) }
            lc.lora?.let      { radioController.setConfig(destNum, Config(lora = it),      radioController.getPacketId()) }
            lc.bluetooth?.let { radioController.setConfig(destNum, Config(bluetooth = it), radioController.getPacketId()) }
            lc.security?.let  { radioController.setConfig(destNum, Config(security = it),  radioController.getPacketId()) }
        }
    }

    private suspend fun installFixedPosition(destNum: Int, fixedPosition: org.meshtastic.proto.Position?) {
        if (fixedPosition != null) {
            radioController.setFixedPosition(destNum, Position(fixedPosition))
        }
    }

    private suspend fun installModuleConfig(destNum: Int, moduleConfig: LocalModuleConfig?) {
        moduleConfig?.let { lmc ->
            installModuleConfigPart1(destNum, lmc)
            installModuleConfigPart2(destNum, lmc)
        }
    }

    private suspend fun installModuleConfigPart1(destNum: Int, lmc: LocalModuleConfig) {
        lmc.mqtt?.let               { radioController.setModuleConfig(destNum, ModuleConfig(mqtt = it),                 radioController.getPacketId()) }
        lmc.serial?.let             { radioController.setModuleConfig(destNum, ModuleConfig(serial = it),               radioController.getPacketId()) }
        lmc.external_notification?.let { radioController.setModuleConfig(destNum, ModuleConfig(external_notification = it), radioController.getPacketId()) }
        lmc.store_forward?.let      { radioController.setModuleConfig(destNum, ModuleConfig(store_forward = it),        radioController.getPacketId()) }
        lmc.range_test?.let         { radioController.setModuleConfig(destNum, ModuleConfig(range_test = it),           radioController.getPacketId()) }
        lmc.telemetry?.let          { radioController.setModuleConfig(destNum, ModuleConfig(telemetry = it),            radioController.getPacketId()) }
        lmc.canned_message?.let     { radioController.setModuleConfig(destNum, ModuleConfig(canned_message = it),       radioController.getPacketId()) }
        lmc.audio?.let              { radioController.setModuleConfig(destNum, ModuleConfig(audio = it),                radioController.getPacketId()) }
    }

    private suspend fun installModuleConfigPart2(destNum: Int, lmc: LocalModuleConfig) {
        lmc.remote_hardware?.let    { radioController.setModuleConfig(destNum, ModuleConfig(remote_hardware = it),      radioController.getPacketId()) }
        lmc.neighbor_info?.let      { radioController.setModuleConfig(destNum, ModuleConfig(neighbor_info = it),        radioController.getPacketId()) }
        lmc.ambient_lighting?.let   { radioController.setModuleConfig(destNum, ModuleConfig(ambient_lighting = it),     radioController.getPacketId()) }
        lmc.detection_sensor?.let   { radioController.setModuleConfig(destNum, ModuleConfig(detection_sensor = it),     radioController.getPacketId()) }
        lmc.paxcounter?.let         { radioController.setModuleConfig(destNum, ModuleConfig(paxcounter = it),           radioController.getPacketId()) }
        lmc.statusmessage?.let      { radioController.setModuleConfig(destNum, ModuleConfig(statusmessage = it),        radioController.getPacketId()) }
        lmc.traffic_management?.let { radioController.setModuleConfig(destNum, ModuleConfig(traffic_management = it),   radioController.getPacketId()) }
        lmc.tak?.let                { radioController.setModuleConfig(destNum, ModuleConfig(tak = it),                  radioController.getPacketId()) }
    }

    /**
     * BL-03 FIX: Decode channel_url from DeviceProfile and write each channel
     * slot including PSK via setRemoteChannel — inside the same edit transaction.
     *
     * Writing channels inside beginEditSettings/commitEditSettings means the
     * firmware receives channel + PSK in the same atomic commit that triggers
     * the reboot — PSK active immediately after the single reboot.
     *
     * Non-fatal if it fails — Stage 2 channel write remains as fallback.
     */
    private suspend fun installChannels(destNum: Int, channelUrl: String?) {
        if (channelUrl.isNullOrBlank()) {
            Log.w(TAG, "installChannels: no channel_url in profile — skipping")
            return
        }
        try {
            val channelSet = CommonUri.parse(channelUrl).toChannelSet()
            if (channelSet.settings.isEmpty()) {
                Log.w(TAG, "installChannels: channel_url decoded but settings list is empty")
                return
            }
            Log.i(TAG, "installChannels: writing ${channelSet.settings.size} channel(s) to node $destNum")
            channelSet.settings.forEachIndexed { index, settings ->
                val channel = Channel(
                    index    = index,
                    settings = settings,
                    role     = if (index == 0) Channel.Role.PRIMARY else Channel.Role.SECONDARY
                )
                Log.i(TAG, "  channel[$index]: name=${settings.name} psk=${settings.psk?.size ?: 0} bytes")
                radioController.setRemoteChannel(destNum, channel, radioController.getPacketId())
            }
            Log.i(TAG, "installChannels: all channels queued — committing with rest of config")
        } catch (e: Exception) {
            Log.e(TAG, "installChannels: failed to decode/write channel_url: ${e.message}")
            // Non-fatal — rest of config still commits. Stage 2 remains as fallback.
        }
    }
}
