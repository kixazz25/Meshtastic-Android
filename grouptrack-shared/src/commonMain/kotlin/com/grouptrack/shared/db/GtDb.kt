package com.grouptrack.shared.db

import androidx.sqlite.SQLiteConnection

/**
 * GTBATCH1A-2026-11-06: open a GroupTrack database the platform's way -- ONE SQLite per platform.
 *  - Android: the system SQLite (androidx.sqlite AndroidSQLiteDriver) -- the same engine as the app's own SQLiteDatabase handles, so the
 *    shared code is one more connection on that engine. [readOnly] is not enforced there (the driver opens read-write); the shared
 *    queries only read.
 *  - iPad: the bundled SQLite, read-only when asked.
 */
expect fun gtOpenDatabase(path: String, readOnly: Boolean): SQLiteConnection
