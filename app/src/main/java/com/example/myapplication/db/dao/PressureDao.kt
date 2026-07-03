package com.example.myapplication.db.dao

import android.content.ContentValues
import android.content.Context
import com.example.myapplication.db.SQLiteHelper
import com.example.myapplication.db.models.PressureEntry
import com.google.firebase.auth.FirebaseAuth

class PressureDao(private val context: Context) {

    private fun getHelper(): SQLiteHelper {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: "default_user"
        return SQLiteHelper(context, currentUid)
    }

    fun insert(systolic: Int, diastolic: Int) {
        val db = getHelper().writableDatabase
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("systolic", systolic)
            put("diastolic", diastolic)
        }
        db.insert("pressure", null, values)
        db.close()
    }

    fun getAll(): List<PressureEntry> {
        val db = getHelper().readableDatabase
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
        db.close()
        return list
    }

    fun deleteOlderThan(timestamp: Long) {
        val db = getHelper().writableDatabase
        db.delete("pressure", "timestamp < ?", arrayOf(timestamp.toString()))
        db.close()
    }
}