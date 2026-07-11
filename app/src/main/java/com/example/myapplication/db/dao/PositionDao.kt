package com.example.myapplication.db.dao

import android.content.ContentValues
import android.content.Context
import com.example.myapplication.db.SQLiteHelper
import com.example.myapplication.db.models.PositionEntry
import com.example.myapplication.db.models.PressureEntry
import com.google.firebase.auth.FirebaseAuth

class PositionDao(private val context: Context) {

    private fun getHelper(): SQLiteHelper {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: "default_user"
        return SQLiteHelper(context, currentUid)
    }

    fun insert(latitude: Double, longitude: Double) {
        val db = getHelper().writableDatabase
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("latitude", latitude)
            put("longitude", longitude)
        }
        db.insert("position", null, values)
        db.close()
    }

    fun getAll(): List<PositionEntry> {
        val db = getHelper().readableDatabase
        val cursor = db.rawQuery("SELECT * FROM position ORDER BY timestamp ASC", null)
        val list = mutableListOf<PositionEntry>()

        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    PositionEntry(
                        id = it.getLong(0),
                        timestamp = it.getLong(1),
                        latitude = it.getDouble(2),
                        longitude = it.getDouble(3)
                    )
                )
            }
        }
        db.close()
        return list
    }

    fun deleteOlderThan(timestamp: Long) {
        val db = getHelper().writableDatabase
        db.delete("position", "timestamp < ?", arrayOf(timestamp.toString()))
        db.close()
    }
}