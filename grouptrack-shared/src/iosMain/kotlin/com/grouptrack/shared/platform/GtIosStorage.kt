package com.grouptrack.shared.platform

import com.grouptrack.shared.imports.GtImportPaths
import com.grouptrack.shared.storage.GtStorage
import com.grouptrack.shared.storage.GtStorageBase
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * CERTPATHS-2026-11-16: the iPad's answer to the four GtStorageBase questions, built to REPRODUCE
 * ANDROID'S OWN LAYOUT rather than an iOS-idiomatic one.
 *
 * Fred 11-16 09:35: "we will address certpaths today by using the default android behavior paths to
 *   this app on the iPad. that is the assertion we will be testing."
 * Fred 11-16 09:27: "lets not introduce a implementation specific difference when we do not have to."
 *
 * THE ASSERTION. GroupTrackStorage (Android, SAF layout since 09-13, useInternal defaults true) resolves:
 *     internalBase()/GroupTrack   = root()          internalBase() = ctx.getExternalFilesDir(null)
 *     internalBase()/my_tracks    = tracksRoot()    a FLAT SIBLING of the root
 *     File(root, name)            = dir(name)
 *     the Context's BARE cacheDir / filesDir
 * This class gives the same shape with iOS's container directories substituted for Android's, so
 * every path's tail below the container is IDENTICAL on both platforms and only the OS-owned prefix
 * differs. CertPaths is what proves or breaks that claim.
 *
 * TWO THINGS IT DELIBERATELY DOES NOT COPY FROM IosPaths (GtIosEnv.kt), WHICH WOULD FAIL IT:
 *  1. IosPaths returns Caches/GroupTrack as cacheDir() and Library/GroupTrack as filesDir(),
 *     appending "GroupTrack" where Android returns the BARE directory. Here they are bare.
 *  2. IosPaths assembles its own paths instead of going through GtStorage, so GtStorage.dir()'s
 *     refusal of a "." or ".." part (GtStorage.kt:13, sim17g2a_2026-11-12) never applied on iOS:
 *     Android throws IllegalArgumentException and the iPad silently resolved elsewhere. Routing
 *     through GtStorage is what makes the V3 rule -- "every path is put together HERE, the same way
 *     on every platform" -- true for iOS and not only for Android.
 *
 * OWNERSHIP CARRIES ACROSS, which is the whole point of the mapping:
 *     getExternalFilesDir(null) -> Documents        app-owned, user-reachable, dies with the app
 *     ctx.cacheDir              -> Library/Caches   truly private, invisible
 *     ctx.filesDir              -> Library          truly private, invisible
 *     public Download/          -> Documents/Download/   the rider's exports, visible (Fred 09:27)
 * tileRoot() is NOT a separate place: Android's body is identical to root()'s, so shared
 * GtStorage.tileRoot() = base.root() is right, and tiles land at root/maps/tiles on both.
 *
 * ⚠ IosPaths is left in place and untouched: the 32 passing cert classes construct it, and breaking
 * a green cert to tidy a name is not a trade worth making today.
 *
 * ⛔ NEVER build my_tracks as dir("my_tracks"). GroupTrackStorage carries the same warning: it would
 * nest the folder INSIDE GroupTrack and silently change a layout that has been flat since the
 * beginning.
 *
 * The Foundation calls are copied from GtIosEnv.kt:63, which compiles today -- not from Apple's
 * documentation. Writing Foundation calls from documentation is what broke the iOS build on 11-15.
 */
private fun iosSysDir(which: ULong): String =
    (NSSearchPathForDirectoriesInDomains(which, NSUserDomainMask, true).firstOrNull() as? String)
        ?: throw IllegalStateException("CERTPATHS-2026-11-16: iOS returned no directory for $which")

/** The four platform questions, answered as Android answers them. */
class IosStorageBase : GtStorageBase {

    /**
     * Android's internalBase() is ctx.getExternalFilesDir(null) -- app-OWNED but USER-REACHABLE
     * (a file manager and USB can see it; it is deleted on uninstall). Its faithful iOS analogue is
     * the app's Documents directory with file sharing on: app-owned, visible to the rider in Files,
     * deleted with the app. NOT Library, which is private AND invisible -- that is the analogue of
     * ctx.filesDir, and it is used for filesDir() below.
     *
     * Fred 11-16 09:40: "will all objects end in the same directories with public private ownership
     *   reflected." This line is where that question is answered.
     */
    private val base: Path get() = iosSysDir(NSDocumentDirectory).toPath()

    /** internalBase()/GroupTrack, as GroupTrackStorage.internalRoot(). Not created -- see rootReady. */
    override fun root(): Path = base / "GroupTrack"

    /** internalBase()/my_tracks -- a SIBLING, as GroupTrackStorage.internalTracksRoot(). */
    override fun tracksRoot(): Path = base / "my_tracks"

    /** BARE, as Android's context.cacheDir. */
    override fun cacheDir(): Path = iosSysDir(NSCachesDirectory).toPath()

    /**
     * BARE Library, as Android's context.filesDir -- truly private and invisible.
     * ⛔ NOT base: base is Documents (user-reachable, the getExternalFilesDir analogue). Writing
     * `= base` here is the bug this comment exists to stop; it was written and caught 11-16 09:43,
     * because moving base from Library to Documents silently changed what filesDir() meant.
     */
    override fun filesDir(): Path = iosSysDir(NSLibraryDirectory).toPath()
}

/** The iPad's GtStorage -- the same shared assembly Android gets from GtStorageAndroid.of(ctx). */
fun iosStorage(): GtStorage = GtStorage(IosStorageBase(), FileSystem.SYSTEM)

/**
 * GtImportPaths over that storage, member for member as GtImportAndroid.paths does it:
 * groupTrackRoot = st.root(), dir = st.dir(name), cacheDir/filesDir = st's, and tracksDir CREATED
 * (Android's ConvoyTrackOps.tracksDir() mkdirs before returning).
 */
class IosImportPaths(private val st: GtStorage = iosStorage()) : GtImportPaths {
    override fun groupTrackRoot(): Path = st.root()
    override fun tracksDir(): Path = st.tracksRoot().also { ensureDir(it) }
    override fun dir(name: String): Path = st.dir(name)
    override fun cacheDir(): Path = st.cacheDir()
    override fun filesDir(): Path = st.filesDir()

    private fun ensureDir(p: Path) {
        try { FileSystem.SYSTEM.createDirectories(p) } catch (e: Exception) { }
    }
}
