package com.geeksville.mesh.comm

import com.grouptrack.comm.CommMessaging
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * COMMSEND-2026-10-08 -- THE ONE WIRING POINT (design §R6.1): each GroupTrack Comm API interface is bound to its mesh
 * implementation here, and nowhere else. A Nucleus/IP or Apple implementation replaces these bindings, not GroupTrack.
 * ViewModelComponent for now: the only user is ConvoyViewModel (widened when a non-ViewModel caller needs it).
 */
@Module
@InstallIn(ViewModelComponent::class)
abstract class GroupTrackCommBindings {
    @Binds
    abstract fun messaging(impl: MeshCommMessaging): CommMessaging
}
