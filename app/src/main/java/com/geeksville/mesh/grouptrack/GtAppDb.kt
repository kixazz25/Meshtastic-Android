package com.geeksville.mesh.grouptrack

import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

/**
 * STEP11-2026-11-07: the shared code's connection shape (androidx.sqlite SQLiteConnection) ON THE APP'S OWN SQLiteDatabase handle.
 * WHY not a second connection (as the read-only lookups use): the importers (OsmImportWorker, TrailCleanup, ...) hold a write
 * transaction on the app's handle while they call the trail writes -- a second connection would wait for a lock its own caller holds.
 * Here every statement runs on the same handle, so a write inside an import's transaction stays inside it.
 *  - queries bind TYPED values (rawQuery would bind every value as text and change numeric comparisons) -- the same trick the
 *    framework's own SupportSQLite helper uses: a CursorFactory binds the SQLiteQuery before the cursor exists.
 *  - BEGIN / COMMIT / END / ROLLBACK map to the handle's own beginTransaction / setTransactionSuccessful / endTransaction.
 *  - close() never closes the app's handle (it belongs to SpatialDbManager).
 */
object GtAppDb {
    fun wrap(db: SQLiteDatabase): SQLiteConnection = Conn(db)

    private class Conn(private val db: SQLiteDatabase) : SQLiteConnection {
        override fun prepare(sql: String): SQLiteStatement = Stmt(db, sql)
        override fun close() {}
    }

    private class Stmt(private val db: SQLiteDatabase, private val sql: String) : SQLiteStatement {
        private val args = HashMap<Int, Any?>()
        private var cursor: Cursor? = null
        private var ran = false
        private val head = sql.trimStart().take(9).uppercase()
        private val isQuery = head.startsWith("SELECT") || head.startsWith("PRAGMA") || head.startsWith("WITH") || head.startsWith("VALUES")

        override fun bindBlob(index: Int, value: ByteArray) { args[index] = value }
        override fun bindDouble(index: Int, value: Double) { args[index] = value }
        override fun bindLong(index: Int, value: Long) { args[index] = value }
        override fun bindText(index: Int, value: String) { args[index] = value }
        override fun bindNull(index: Int) { args[index] = null }

        private fun bindTo(p: SQLiteProgram) {
            for ((i, v) in args) when (v) {
                null -> p.bindNull(i)
                is Long -> p.bindLong(i, v)
                is Double -> p.bindDouble(i, v)
                is ByteArray -> p.bindBlob(i, v)
                else -> p.bindString(i, v.toString())
            }
        }

        private fun open(): Cursor = cursor ?: db.rawQueryWithFactory(
            SQLiteDatabase.CursorFactory { _, driver, editTable, query -> bindTo(query); SQLiteCursor(driver, editTable, query) },
            sql, null, "").also { cursor = it }   // STEP11B-2026-11-07: editTable is non-null; "" = none

        override fun step(): Boolean {
            if (isQuery) return open().moveToNext()
            if (ran) return false
            ran = true
            when {
                head.startsWith("BEGIN") -> db.beginTransaction()
                head.startsWith("COMMIT") || head.startsWith("END") -> { db.setTransactionSuccessful(); db.endTransaction() }
                head.startsWith("ROLLBACK") -> db.endTransaction()
                else -> { val st = db.compileStatement(sql); try { bindTo(st); st.execute() } finally { st.close() } }
            }
            return false
        }

        private fun c(): Cursor = cursor ?: throw IllegalStateException("GtAppDb: read a column before step()")
        override fun getBlob(index: Int): ByteArray = c().getBlob(index)
        override fun getDouble(index: Int): Double = c().getDouble(index)
        override fun getLong(index: Int): Long = c().getLong(index)
        override fun getText(index: Int): String = c().getString(index) ?: ""
        override fun isNull(index: Int): Boolean = c().isNull(index)
        override fun getColumnCount(): Int = if (isQuery) open().columnCount else 0
        override fun getColumnName(index: Int): String = open().getColumnName(index)
        override fun getColumnType(index: Int): Int = when (c().getType(index)) {
            Cursor.FIELD_TYPE_INTEGER -> SQLITE_DATA_INTEGER
            Cursor.FIELD_TYPE_FLOAT -> SQLITE_DATA_FLOAT
            Cursor.FIELD_TYPE_STRING -> SQLITE_DATA_TEXT
            Cursor.FIELD_TYPE_BLOB -> SQLITE_DATA_BLOB
            else -> SQLITE_DATA_NULL
        }
        override fun reset() { cursor?.close(); cursor = null; ran = false }
        override fun clearBindings() { args.clear() }
        override fun close() { cursor?.close(); cursor = null }
    }
}
