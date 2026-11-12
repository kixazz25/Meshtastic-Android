package com.grouptrack.shared.storage

import okio.FileSystem
import okio.Path

/**
 * STEP17G2A-2026-11-12 (2.8 step 17, G2a): ONE "where are my files" for the whole shared model -- the storage socket.
 * Before this, steps 15-16 had each socketed paths their own way (GtImportPaths, RideFiles, GtQueuePlatform.recipesDir), each wrapping
 * Android's GroupTrackStorage. Now the platform answers four questions (GtStorageBase) and every path is put together HERE, the same
 * way on every platform (V3 rule: anything stored or compared is produced identically).
 *   Android (GtStorageAndroid): root = GroupTrackStorage.root (<app files>/GroupTrack, the SAF layout since 09-13), tracksRoot = its
 *   my_tracks sibling, cacheDir / filesDir = the Context's. The iPad (step 18): <Documents>/GroupTrack and its siblings.
 * A folder name is joined to the root as written; a name with a "." or ".." part is REFUSED (IllegalArgumentException): java.io.File
 * keeps those parts and okio resolves them, so the two would name different places (sim17g2a_2026-11-12: every one of the 19 names the
 * app uses gives the identical path; only "./x" and "../x" differ, and no code asks for them).
 */
interface GtStorageBase {
    /** the GroupTrack folder (Android GroupTrackStorage.root(ctx); not created here -- see rootReady) */
    fun root(): Path
    /** my_tracks, a SIBLING of the root (Android GroupTrackStorage.tracksRoot(ctx)) */
    fun tracksRoot(): Path
    /** the app's cache folder (Android context.cacheDir) */
    fun cacheDir(): Path
    /** the app's private files folder (Android context.filesDir) */
    fun filesDir(): Path
}

class GtStorage(private val base: GtStorageBase, private val fs: FileSystem) {
    fun root(): Path = base.root()
    /** 2.7e GroupTrackStorage.tileRoot = the root (internal storage) */
    fun tileRoot(): Path = base.root()
    fun tracksRoot(): Path = base.tracksRoot()
    fun cacheDir(): Path = base.cacheDir()
    fun filesDir(): Path = base.filesDir()

    /** 2.7e GroupTrackStorage.dir(name) = File(root, name): a named child of the root ("rides", "data/trail_areas", "states/utah"). Not created. */
    fun dir(name: String): Path {
        require(name.split('/').none { it == "." || it == ".." }) {
            "GtStorage.dir(\"$name\"): a '.' or '..' part is refused -- a path is worked out one way only"
        }
        return root() / name
    }

    /** 2.7e GroupTrackStorage.rootReady: the root created, with its .nomedia (so the gallery never scans it); returns the root. */
    fun rootReady(): Path {
        val d = root()
        fs.createDirectories(d)
        val nm = d / ".nomedia"
        if (!fs.exists(nm)) runCatching { fs.write(nm) {} }
        return d
    }
}
