package com.geeksville.mesh.comm

// COMMCONFIG-2026-10-12: RadioOps moved to the comm side (same package)
import kotlinx.coroutines.flow.first
import org.meshtastic.proto.DeviceProfile
import javax.inject.Inject

/**
 * COMMRADIOOPS-2026-10-12 (2.7b cycle 1 P4.1) -- THE RADIO ADAPTER, on the comm side: everything the radio configurator (the writer)
 * needs from the radio, as Meshtastic does it. Moved UNCHANGED from ConvoyViewModel.radioOps(ui) (RADIOCFG4-2026-09-25):
 * the same calls, in the same order, awaited; the forced cycle (setDeviceAddress "n" / the saved radio) for the restart waits.
 * Every call tells the link that a settings apply is running, so its keep-alive holds and never fights the restart cycle.
 * TRANSITIONAL: GroupTrack's configurator still drives these calls; P4.2 moves the configurator here, behind
 * CommConfig.applyConfig, and this adapter stops being visible to GroupTrack.
 */
@javax.inject.Singleton
class MeshRadioOps @Inject constructor(
    private val radioController: org.meshtastic.core.model.RadioController,
    private val nodeRepository: org.meshtastic.core.repository.NodeRepository,
    private val radioConfigRepository: org.meshtastic.core.repository.RadioConfigRepository,
    private val serviceRepository: org.meshtastic.core.repository.ServiceRepository,
    private val radioInterfaceService: org.meshtastic.core.repository.RadioInterfaceService,
    private val link: MeshCommLink,
) {
    /** The adapter for the radio connected now. CODE RULE 1: null = no radio connected (no node number or no saved
     *  radio) -- the screen says so. */
    fun ops(): RadioOps? {
        val destNum = nodeRepository.myNodeInfo.value?.myNodeNum ?: return null
        val saved = radioInterfaceService.getDeviceAddress()?.takeIf { it.isNotBlank() && it != "n" } ?: return null
        return object : RadioOps {
            override val connection = serviceRepository.connectionState
            override suspend fun setOwner(longName: String) {
                link.holdReconnects()
                val user = requireNotNull(nodeRepository.ourNodeInfo.value?.user) { "no user record for this radio" }
                radioController.setOwner(destNum, user.copy(long_name = longName), radioController.getPacketId())
                org.meshtastic.core.data.manager.RadioNameStore.put(destNum, longName, "config written")   // RADIONAMES-2026-10-03
            }
            override suspend fun beginEdit() { link.holdReconnects(); radioController.beginEditSettings(destNum) }
            override suspend fun commitEdit() { link.holdReconnects(); radioController.commitEditSettings(destNum) }
            override suspend fun writeConfig(config: org.meshtastic.proto.Config) {
                link.holdReconnects(); radioController.setConfig(destNum, config, radioController.getPacketId())
            }
            override suspend fun writeChannel(channel: org.meshtastic.proto.Channel) {
                link.holdReconnects(); radioController.setRemoteChannel(destNum, channel, radioController.getPacketId())
            }
            override suspend fun writeModuleConfig(config: org.meshtastic.proto.ModuleConfig) {   // RADIODEFAULTS-2026-10-06
                link.holdReconnects(); radioController.setModuleConfig(destNum, config, radioController.getPacketId())
            }
            override suspend fun disconnect() { link.holdReconnects(); radioController.setDeviceAddress("n") }
            override suspend fun reconnect() { link.holdReconnects(); radioController.setDeviceAddress(saved) }
            override suspend fun retrieve(): DeviceProfile { link.holdReconnects(); return radioConfigRepository.deviceProfileFlow.first() }
            // OWNNODE-2026-09-26: the app's own radio as it holds it now -- its own entry in the node list.
            override fun ownNodeNum(): Int? = nodeRepository.ourNodeInfo.value?.num
        }
    }
}
