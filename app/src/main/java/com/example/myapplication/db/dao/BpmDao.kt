package com.example.myapplication.db.dao

import android.content.ContentValues
import android.content.Context
import com.example.myapplication.db.SQLiteHelper
import com.example.myapplication.db.models.BpmEntry
import com.google.firebase.auth.FirebaseAuth

class BpmDao(private val context: Context) {

    // Ottiene il database helper dinamico basato sull'utente loggato
    private fun getHelper(): SQLiteHelper {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: "default_user"
        return SQLiteHelper(context, currentUid)
    }

    fun insert(bpm: Int) {
        val db = getHelper().writableDatabase
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("bpm", bpm)
        }
        db.insert("bpm", null, values)
        db.close()
    }

    fun getAll(): List<BpmEntry> {
        val db = getHelper().readableDatabase
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
        db.close()
        return list
    }

    fun deleteOlderThan(timestamp: Long) {
        val db = getHelper().writableDatabase
        db.delete("bpm", "timestamp < ?", arrayOf(timestamp.toString()))
        db.close()
    }
}