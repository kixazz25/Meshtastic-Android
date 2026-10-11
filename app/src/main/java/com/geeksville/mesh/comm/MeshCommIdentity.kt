package com.geeksville.mesh.comm

import com.grouptrack.comm.CommIdentity
import com.grouptrack.comm.DeviceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import org.meshtastic.core.model.MyNodeInfo
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.NodeRepository
import javax.inject.Inject

/**
 * COMMIDENT-2026-10-09 (2.7b cycle 1 P1) -- the MESH implementation of CommIdentity: THIS RADIO.
 * Lives in the app module (the comm side); GroupTrack only sees the contract (com.grouptrack.comm).
 * Source: Meshtastic's own records of the connected radio -- MyNodeInfo (node number, model, firmware, device id) and our
 * Node (the long name). Nothing new is asked of the radio.
 * id = the node number as text (decimal) -- the SAME form the Comm API uses for member ids (COMMRECV-2026-10-08).
 * null = Meshtastic has no radio record yet (no radio, or waiting for the link).
 */
class MeshCommIdentity @Inject constructor(
    private val nodeRepository: NodeRepository,
) : CommIdentity {

    override val device: Flow<DeviceInfo?> =
        combine(nodeRepository.myNodeInfo, nodeRepository.ourNodeInfo) { my, our -> toDevice(my, our) }
            .distinctUntilChanged()

    override suspend fun myDevice(): DeviceInfo? = device.first()

    private fun toDevice(my: MyNodeInfo?, our: Node?): DeviceInfo? {
        if (my == null) return null
        return DeviceInfo(
            id = my.myNodeNum.toString(),
            name = our?.user?.long_name.orEmpty(),          // "" until the radio's own user record arrives
            model = my.model?.takeIf { it.isNotBlank() },
            firmware = my.firmwareVersion?.takeIf { it.isNotBlank() },
            deviceId = my.deviceId?.takeIf { it.isNotBlank() },
        )
    }
}
