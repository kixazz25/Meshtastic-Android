package com.grouptrack.shared.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE

// GTBATCH1A-2026-11-06: the bundled SQLite on the iPad (proved in the spike).
actual fun gtOpenDatabase(path: String, readOnly: Boolean): SQLiteConnection =
    BundledSQLiteDriver().open(path, if (readOnly) SQLITE_OPEN_READONLY else (SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE))
