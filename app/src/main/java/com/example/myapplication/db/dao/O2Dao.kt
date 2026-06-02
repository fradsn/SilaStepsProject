package com.example.myapplication.db.dao

import android.content.ContentValues
import android.content.Context
import com.example.myapplication.db.SQLiteHelper
import com.example.myapplication.db.models.O2Entry

class O2Dao(context: Context) {

    private val db = SQLiteHelper.getInstance(context).writableDatabase

    fun insert(value: Int) {
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("value", value)
        }
        db.insert("o2", null, values)
    }

    fun getAll(): List<O2Entry> {
        val cursor = db.rawQuery("SELECT * FROM o2 ORDER BY timestamp ASC", null)
        val list = mutableListOf<O2Entry>()

        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    O2Entry(
                        id = it.getLong(0),
                        timestamp = it.getLong(1),
                        value = it.getInt(2)
                    )
                )
            }
        }
        return list
    }

    fun deleteOlderThan(timestamp: Long) {
        db.delete("o2", "timestamp < ?", arrayOf(timestamp.toString()))
    }

}
