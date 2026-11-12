package com.geeksville.mesh.grouptrack

import android.content.Context
import com.geeksville.mesh.convoy.GroupTrackStorage
import com.grouptrack.shared.storage.GtStorage
import com.grouptrack.shared.storage.GtStorageBase
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath

/** STEP17G2A-2026-11-12: Android's answers to the storage socket -- GroupTrackStorage as it is (unchanged), the Context's cache / files. */
object GtStorageAndroid {
    fun of(ctx: Context): GtStorage {
        val app = ctx.applicationContext
        return GtStorage(object : GtStorageBase {
            override fun root(): Path = GroupTrackStorage.root(app).toOkioPath()
            override fun tracksRoot(): Path = GroupTrackStorage.tracksRoot(app).toOkioPath()
            override fun cacheDir(): Path = app.cacheDir.toOkioPath()
            override fun filesDir(): Path = app.filesDir.toOkioPath()
        }, FileSystem.SYSTEM)
    }
}
