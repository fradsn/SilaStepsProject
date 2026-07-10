package com.example.myapplication.db.dao

import android.content.ContentValues
import android.content.Context
import com.example.myapplication.db.SQLiteHelper
import com.example.myapplication.db.models.PredictionEntry
import com.google.firebase.auth.FirebaseAuth

class PredictionDao(private val context: Context) {

    private val codesToActivity: Map<Int, String> = mapOf(
        1 to "Sitting",
        2 to "Standing",
        3 to "Walking",
        4 to "Jogging"
    )

    private val activityToCodes = codesToActivity.entries.associate { it.value to it.key }

    private fun getHelper(): SQLiteHelper {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: "default_user"
        return SQLiteHelper(context, currentUid)
    }

    fun insert(activity: String, confidence: Int) {
        val db = getHelper().writableDatabase

        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("activity", activityToCodes[activity])
            put("confidence", confidence)
        }
        db.insert("prediction", null, values)
        db.close()
    }

    fun getAll(): List<PredictionEntry> {
        val db = getHelper().readableDatabase
        val cursor = db.rawQuery("SELECT * FROM prediction ORDER BY timestamp ASC", null)
        val list = mutableListOf<PredictionEntry>()

        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    PredictionEntry(
                        id = it.getLong(0),
                        timestamp = it.getLong(1),
                        activity = codesToActivity[it.getInt(2)],
                        confidence = it.getInt(3)
                    )
                )
            }
        }
        db.close()
        return list
    }

    fun getActivityCount(): Map<String, Int> {
        val db = getHelper().readableDatabase
        val cursor = db.rawQuery("SELECT activity, COUNT(*) FROM prediction GROUP BY activity", null)
        val ret = mutableMapOf<String, Int>()

        cursor.use {
            while (it.moveToNext()) {
                val activity = codesToActivity[it.getInt(0)]
                val count = cursor.getInt(1)

                ret[activity!!] = count
            }
        }
        db.close()

        return ret
    }

    fun deleteOlderThan(timestamp: Long) {
        val db = getHelper().writableDatabase
        db.delete("prediction", "timestamp < ?", arrayOf(timestamp.toString()))
        db.close()
    }
}