package com.grouptrack.shared.cert

import com.grouptrack.shared.storage.GtStorage
import com.grouptrack.shared.storage.GtStorageBase
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * STEP17G2A-2026-11-12 ⚠ TEST-ONLY: the storage socket's rules on the iPad simulator -- the SAME joining as the Droid (a base laid out
 * like the Droid's), and the refusals. The iPad's own base (Documents) is step 18.
 */
class GtStorageMacTest {
    private val b = "/storage/emulated/0/Android/data/com.grouptrack.android/files"
    private val st = GtStorage(object : GtStorageBase {
        override fun root(): Path = "$b/GroupTrack".toPath()
        override fun tracksRoot(): Path = "$b/my_tracks".toPath()
        override fun cacheDir(): Path = "/data/user/0/com.grouptrack.android/cache".toPath()
        override fun filesDir(): Path = "/data/user/0/com.grouptrack.android/files".toPath()
    }, FileSystem.SYSTEM)

    @Test
    fun paths() {
        val want = mapOf("rides" to "$b/GroupTrack/rides", "data/trail_areas" to "$b/GroupTrack/data/trail_areas",
            "states/utah" to "$b/GroupTrack/states/utah", "" to "$b/GroupTrack", "rides/" to "$b/GroupTrack/rides",
            "a//b" to "$b/GroupTrack/a/b", "states/" to "$b/GroupTrack/states")
        for ((n, p) in want) assertEquals(p, st.dir(n).toString(), "dir(\"$n\")")
        assertEquals("$b/GroupTrack", st.tileRoot().toString())
        assertEquals("$b/my_tracks", st.tracksRoot().toString())
        for (n in listOf("./x", "../x", "a/../b")) assertFailsWith<IllegalArgumentException>("dir(\"$n\") must be refused") { st.dir(n) }
        println("GTS17A mac: ${want.size} paths SAME as the Droid's joining, 3 refusals -- done")
    }
}
