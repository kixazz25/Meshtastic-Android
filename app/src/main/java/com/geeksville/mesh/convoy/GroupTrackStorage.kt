package com.geeksville.mesh.convoy

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * STORAGEROOT-2026-09-11: the ONE place a GroupTrack path is resolved.
 *
 * \u26d4 BEFORE THIS, TEN PLACES BUILT THE SAME PATH. Two functions in
 * SpatialDbManager built `Documents/GroupTrack` independently of each other, and
 * eight more sites hardcoded it inline. \u26a0 That is the same shape as the palette
 * bug fixed on 09-11 -- more than one copy of a truth, with nothing keeping them
 * in step, and it is why nobody could say with confidence what would have to
 * change to move the data.
 *
 * \u2b50 THE GOVERNING PRINCIPLE (Fred, 09-11): *"internal storage mirrors the
 * directory structure of external storage, so we are just changing external for
 * internal, not remapping everything."* Everything below the root keeps its name
 * and its nesting. Only the root moves.
 *
 * \u26a0 THIS FILE CHANGES NOTHING TODAY. [root] returns exactly what every caller
 * resolved before it existed. The value is that step two of the storage plan
 * becomes a one-line change here.
 */
object GroupTrackStorage {

    /**
     * \u26a0 Remembered from the first caller that has one. These are `object`
     * singletons -- MapStateStore has no Context anywhere -- so the accessor
     * cannot demand one. The PUBLIC path needs none, which is all release 1
     * requires; `getExternalFilesDir()` will.
     */
    @Volatile
    private var appContext: Context? = null

    fun remember(ctx: Context?) {
        if (appContext == null && ctx != null) appContext = ctx.applicationContext
    }

    /** Has a Context been supplied yet? */
    fun hasContext(): Boolean = appContext != null

    /**
     * INTERNALBASE-2026-09-12: the app-private external base.
     *
     * \u2b50 `getExternalFilesDir(null)` needs NO permission, is not visible to
     * other apps, and gives a real filesystem path -- so SQLite, MBTiles and
     * everything else work there unchanged.
     *
     * \u26d4 IT RETURNS NULL RATHER THAN FALLING BACK. A silent fallback to the
     * public path after MANAGE_EXTERNAL_STORAGE has left the manifest means
     * writing somewhere the app cannot read back -- and that looks exactly like
     * it worked. Callers must handle null; the alternative is invisible data
     * loss.
     */
    fun internalBase(): File? {
        val ctx = appContext ?: return null
        return try {
            ctx.getExternalFilesDir(null)
        } catch (e: Exception) {
            android.util.Log.e("GTStorage", "internalBase failed: ${e.message}")
            null
        }
    }

    /** `Documents/GroupTrack` -- the public root, as it has always been. */
    private fun publicRoot(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "GroupTrack"
    )

    /**
     * INTERNALBASE-2026-09-12: `Documents/my_tracks` -- where GPS recording
     * writes today.
     *
     * \u26a0 IT IS A SIBLING OF GroupTrack, NOT A CHILD. Easy to get wrong, because
     * every other GroupTrack path hangs off [root] -- but my_tracks sits beside
     * it in Documents and must keep doing so internally, or the layout changes
     * under the migration.
     */
    private fun publicTracksRoot(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "my_tracks"
    )

    /**
     * INTERNALBASE-2026-09-12: the two internal counterparts. \u2b50 Same names, same
     * relationship -- only the base differs.
     */
    private fun internalRoot(): File? = internalBase()?.let { File(it, "GroupTrack") }
    private fun internalTracksRoot(): File? = internalBase()?.let { File(it, "my_tracks") }

    /**
     * The GroupTrack root. Everything except the tile store lives under here.
     *
     * \u26a0 RELEASE 1 RETURNS THE PUBLIC PATH -- unchanged behaviour. The migration
     * switches it to `getExternalFilesDir(null)`, and because the tree below is
     * identical, nothing else has to change.
     */
    fun root(ctx: Context? = null): File {
        remember(ctx)
        return publicRoot()
    }

    /**
     * The MBTiles store.
     *
     * \u26d4 SEPARATE FROM [root] ON PURPOSE. The tiles are ~17 GB and stay in public
     * storage through release 1 while everything else moves. Release 2 points this
     * one function somewhere else -- or removes it -- without touching the
     * migration. \u26a0 A caller that wants tiles must use THIS, not root().
     */
    fun tileRoot(ctx: Context? = null): File {
        remember(ctx)
        return publicRoot()
    }

    /**
     * The root, created, with `.nomedia` in place.
     *
     * \u26a0 `.nomedia` keeps the media scanner out. It must exist in EVERY root the
     * app writes to -- including the public one after the migration, because
     * maps/ stays behind there.
     */
    fun rootReady(ctx: Context? = null): File {
        val dir = root(ctx)
        if (!dir.exists()) dir.mkdirs()
        val noMedia = File(dir, ".nomedia")
        if (!noMedia.exists()) {
            try { noMedia.createNewFile() } catch (_: Exception) {}
        }
        return dir
    }

    /** A named child of the root, e.g. `dir("state")`. */
    fun dir(name: String, ctx: Context? = null): File = File(root(ctx), name)

    /**
     * INTERNALBASE-2026-09-12: the recorded-GPX root, `my_tracks`.
     *
     * \u26d4 USE THIS, NEVER `dir("my_tracks")`. The latter would nest it INSIDE
     * GroupTrack and silently change a layout that has been flat since the
     * beginning -- the migration copies Documents/my_tracks to its own place, and
     * a caller resolving it under GroupTrack would look in the wrong one.
     *
     * \u26a0 RELEASE 1 RETURNS THE PUBLIC PATH, unchanged.
     * `ConvoyTrackOps.tracksDir()` is the canonical caller and everything else
     * should route through that.
     */
    fun tracksRoot(ctx: Context? = null): File {
        remember(ctx)
        return publicTracksRoot()
    }

    /**
     * INTERNALBASE-2026-09-12: \u26a0 DIAGNOSTIC ONLY -- what the switch WILL return.
     * Nothing in the app should call these to resolve a path; they exist so the
     * migration and the record can name both sides while the switch is still
     * off.
     */
    fun plannedInternalRoot(): File? = internalRoot()
    fun plannedInternalTracksRoot(): File? = internalTracksRoot()
}
