package com.example.myapplication.db.dao

import android.content.ContentValues
import android.content.Context
import com.example.myapplication.db.SQLiteHelper
import com.example.myapplication.db.models.BpmEntry

class BpmDao(context: Context) {

    private val db = SQLiteHelper.getInstance(context).writableDatabase

    fun insert(bpm: Int) {
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("bpm", bpm)
        }
        db.insert("bpm", null, values)
    }

    fun getAll(): List<BpmEntry> {
        /*
        val ventiquattroOreFa = System.currentTimeMillis() - 86400000L
        val query = "SELECT * FROM bpm WHERE timestamp >= ? ORDER BY timestamp ASC"
        val cursor = db.rawQuery(query, arrayOf(ventiquattroOreFa.toString()))
         */
        val cursor = db.rawQuery("SELECT * FROM bpm ORDER BY timestamp ASC", null)
        val list = mutableListOf<BpmEntry>()

        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    BpmEntry(
                        id = it.getLong(0),
                        timestamp = it.getLong(1),
                        bpm = it.getInt(2)
                    )
                )
            }
        }
        return list
    }

    fun deleteOlderThan(timestamp: Long) {
        db.delete("bpm", "timestamp < ?", arrayOf(timestamp.toString()))
    }

}
