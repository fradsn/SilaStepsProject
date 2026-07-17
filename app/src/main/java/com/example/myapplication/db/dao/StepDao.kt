package com.example.myapplication.db.dao

import android.content.ContentValues
import android.content.Context
import com.example.myapplication.db.SQLiteHelper
import com.example.myapplication.db.models.BpmEntry
import com.example.myapplication.db.models.PredictionEntry
import com.example.myapplication.db.models.StepEntry
import com.google.firebase.auth.FirebaseAuth
import kotlin.math.roundToInt

class StepDao(private val context: Context) {

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
        db.insert("steps", null, values)
        db.close()
    }

    fun getAll(): List<StepEntry> {
        val db = getHelper().readableDatabase
        val cursor = db.rawQuery("SELECT * FROM steps ORDER BY timestamp ASC", null)
        val list = mutableListOf<StepEntry>()

        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    StepEntry(
                        id = it.getLong(0),
                        timestamp = it.getLong(1),
                        tot = it.getInt(2)
                    )
                )
            }
        }
        db.close()
        return list
    }

    fun getLastRecord(): StepEntry? {
        val db = getHelper().readableDatabase
        val startOfToday = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        val cursor = db.rawQuery(
            "SELECT * FROM steps WHERE timestamp >= ? ORDER BY timestamp ASC",
            arrayOf(startOfToday.toString())
        )
        var ret: StepEntry? = null
        if (cursor.moveToFirst())
            ret = StepEntry(
                id = cursor.getLong(0),
                timestamp = cursor.getLong(1),
                tot = cursor.getInt(2)
            )
        cursor.close()
        db.close()
        return ret
    }

    fun deleteOlderThan(timestamp: Long) {
        val db = getHelper().writableDatabase
        db.delete("steps", "timestamp < ?", arrayOf(timestamp.toString()))
        db.close()
    }

}