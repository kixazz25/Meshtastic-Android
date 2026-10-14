package com.geeksville.mesh.comm

import com.grouptrack.comm.CommIdentity
import com.grouptrack.comm.CommLink
import com.grouptrack.comm.CommMenus
import com.grouptrack.comm.CommMessaging
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * COMMSEND-2026-10-08 -- THE ONE WIRING POINT (design §R6.1): each GroupTrack Comm API interface is bound to its mesh
 * implementation here, and nowhere else. A Nucleus/IP or Apple implementation replaces these bindings, not GroupTrack.
 * COMMSINGLE-2026-10-11 (Fred 10-11): SingletonComponent -- keeping the radio link alive is the comm side's job and runs for the
 * life of the app (P2.3). Was ViewModelComponent (only ConvoyViewModel used it).
 */
@Module
@InstallIn(SingletonComponent::class)   // COMMSINGLE-2026-10-11
abstract class GroupTrackCommBindings {
    @Binds
    abstract fun messaging(impl: MeshCommMessaging): CommMessaging
    @Binds
    abstract fun menus(impl: MeshCommMenus): CommMenus   // COMMMENUS-2026-10-08
    @Binds
    abstract fun identity(impl: MeshCommIdentity): CommIdentity   // COMMIDENT-2026-10-09
    @Binds
    abstract fun link(impl: MeshCommLink): CommLink   // COMMLINK-2026-10-11
    @Binds
    abstract fun config(impl: MeshCommConfig): com.grouptrack.comm.CommConfig   // COMMCONFIG-2026-10-12
}

/** COMMSAVES-2026-10-14 (P5.4b): the contract for GroupTrack code that has no view model (the startup gate, the check-in panel).
 *  Returns the CONTRACT type -- callers never see the mesh implementation. */
@dagger.hilt.EntryPoint
@InstallIn(SingletonComponent::class)
interface GroupTrackCommEntryPoint { fun config(): com.grouptrack.comm.CommConfig }
object CommAccess {
    fun config(ctx: android.content.Context): com.grouptrack.comm.CommConfig =
        dagger.hilt.android.EntryPointAccessors.fromApplication(ctx.applicationContext, GroupTrackCommEntryPoint::class.java).config()
}
