package com.example.myapplication.db.dao

import android.content.ContentValues
import android.content.Context
import com.example.myapplication.db.SQLiteHelper
import com.example.myapplication.db.models.PressureEntry

class PressureDao(context: Context) {

    private val db = SQLiteHelper.getInstance(context).writableDatabase

    fun insert(systolic: Int, diastolic: Int) {
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("systolic", systolic)
            put("diastolic", diastolic)
        }
        db.insert("pressure", null, values)
    }

    fun getAll(): List<PressureEntry> {
        val cursor = db.rawQuery("SELECT * FROM pressure ORDER BY timestamp ASC", null)
        val list = mutableListOf<PressureEntry>()

        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    PressureEntry(
                        id = it.getLong(0),
                        timestamp = it.getLong(1),
                        systolic = it.getInt(2),
                        diastolic = it.getInt(3)
                    )
                )
            }
        }
        return list
    }

    fun deleteOlderThan(timestamp: Long) {
        db.delete("pressure", "timestamp < ?", arrayOf(timestamp.toString()))
    }

}
