package com.geeksville.mesh.grouptrack

import com.grouptrack.core.NetworkGenerator
import com.grouptrack.shared.net.GtMeshDefaults
import com.grouptrack.shared.net.GtRandom

/** STEP17G2C1-2026-11-12: Android's answers for the shared profile / network stores. */
object GtNetAndroid {
    /** a java.util.Random (the app: SecureRandom, as 2.7e; the cert: a seeded Random) as the shared random source */
    class JavaRng(private val r: java.util.Random) : GtRandom {
        override fun nextInt(bound: Int): Int = r.nextInt(bound)
        override fun nextBytes(b: ByteArray) = r.nextBytes(b)
    }
    /** the mesh values 2.7e's NetworkGenerator.MeshDefaults holds now (grouptrack_default.json, or the fallback) */
    fun mesh(): GtMeshDefaults = NetworkGenerator.MeshDefaults.let { GtMeshDefaults(it.REGION, it.MODEM_PRESET, it.HOP_LIMIT, it.TX_POWER, it.FREQUENCY_SLOT, it.ROLE) }
}
