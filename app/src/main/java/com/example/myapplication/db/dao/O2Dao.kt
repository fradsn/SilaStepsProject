package com.example.myapplication.db.dao

import android.content.ContentValues
import android.content.Context
import com.example.myapplication.db.SQLiteHelper
import com.example.myapplication.db.models.O2Entry
import com.google.firebase.auth.FirebaseAuth

class O2Dao(private val context: Context) {

    private fun getHelper(): SQLiteHelper {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: "default_user"
        return SQLiteHelper(context, currentUid)
    }

    fun insert(value: Int) {
        val db = getHelper().writableDatabase
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("value", value)
        }
        db.insert("o2", null, values)
        db.close()
    }

    fun getAll(): List<O2Entry> {
        val db = getHelper().readableDatabase
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
        db.close()
        return list
    }

    fun deleteOlderThan(timestamp: Long) {
        val db = getHelper().writableDatabase
        db.delete("o2", "timestamp < ?", arrayOf(timestamp.toString()))
        db.close()
    }
}