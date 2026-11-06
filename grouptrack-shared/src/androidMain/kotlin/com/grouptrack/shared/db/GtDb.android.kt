package com.grouptrack.shared.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver

// GTBATCH1A-2026-11-06: Android's own SQLite -- never the bundled copy (see GtDb.kt).
actual fun gtOpenDatabase(path: String, readOnly: Boolean): SQLiteConnection = AndroidSQLiteDriver().open(path)
