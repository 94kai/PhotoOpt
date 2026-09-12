package com.xk.photoopt

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Disposable metadata cache. Never stores output-existence, pairing, or transient read errors. */
class InspectionCache(context: Context) : Closeable {
    private val helper = object : SQLiteOpenHelper(context, File(context.cacheDir, "inspection-v5.db").path, null, 6) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE inspection (source TEXT PRIMARY KEY, size INTEGER NOT NULL, modified INTEGER NOT NULL, entry TEXT NOT NULL, updated INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS inspection"); onCreate(db)
        }
    }
    private val db = runCatching { helper.writableDatabase }.getOrNull()
    private val pending = ConcurrentLinkedQueue<MediaEntry>()

    fun get(basic: MediaEntry): MediaEntry? = runCatching {
        db?.query("inspection", arrayOf("entry"), "source=? AND size=? AND modified=?",
            arrayOf(basic.source, basic.size.toString(), basic.modified.toString()), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else mediaFromJson(JSONObject(cursor.getString(0))).copy(root = basic.root, relative = basic.relative)
        }
    }.getOrNull()

    fun put(entry: MediaEntry) { pending.add(entry) }

    fun flush() {
        if (pending.isEmpty()) return
        val database = db ?: return
        // Cache failures must never fail a scan. SQLite rolls back interrupted writes.
        runCatching {
            database.beginTransaction()
            try {
                val now = System.currentTimeMillis()
                pending.forEach { entry -> database.insertWithOnConflict("inspection", null, ContentValues().apply {
                    put("source", entry.source); put("size", entry.size); put("modified", entry.modified)
                    put("entry", mediaToJson(entry).toString()); put("updated", now)
                }, SQLiteDatabase.CONFLICT_REPLACE) }
                database.execSQL("DELETE FROM inspection WHERE source IN (SELECT source FROM inspection ORDER BY updated DESC LIMIT -1 OFFSET 20000)")
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
        }
        pending.clear()
    }
    override fun close() { helper.close() }
}
