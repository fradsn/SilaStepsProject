package com.example.myapplication.db.dao

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.myapplication.db.SQLiteHelper
import com.example.myapplication.db.models.DailyStepEntry
import com.example.myapplication.db.models.HourlyStepEntry
import com.google.firebase.auth.FirebaseAuth

class StepHistoryDao(
    private val context: Context
) {

    private fun getHelper(): SQLiteHelper {
        val userId =
            FirebaseAuth.getInstance().currentUser?.uid
                ?: "default_user"

        return SQLiteHelper(context, userId)
    }

    fun upsertHistory(
        dailyEntries: List<DailyStepEntry>,
        hourlyEntries: List<HourlyStepEntry>
    ) {
        val db = getHelper().writableDatabase

        db.beginTransaction()

        try {
            dailyEntries.forEach { entry ->
                val values = ContentValues().apply {
                    put("day", entry.day)
                    put("steps", entry.steps)
                    put("updated_at", entry.updatedAt)
                }

                db.insertWithOnConflict(
                    "daily_steps",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }

            hourlyEntries.forEach { entry ->
                val values = ContentValues().apply {
                    put("day", entry.day)
                    put("hour", entry.hour)
                    put("steps", entry.steps)
                    put("updated_at", entry.updatedAt)
                }

                db.insertWithOnConflict(
                    "hourly_steps",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    fun getDailyBetween(
        firstDay: String,
        lastDay: String
    ): List<DailyStepEntry> {
        val db = getHelper().readableDatabase

        val cursor = db.rawQuery(
            """
            SELECT day, steps, updated_at
            FROM daily_steps
            WHERE day >= ? AND day <= ?
            ORDER BY day ASC
            """.trimIndent(),
            arrayOf(firstDay, lastDay)
        )

        val result = mutableListOf<DailyStepEntry>()

        cursor.use {
            while (it.moveToNext()) {
                result.add(
                    DailyStepEntry(
                        day = it.getString(
                            it.getColumnIndexOrThrow("day")
                        ),
                        steps = it.getInt(
                            it.getColumnIndexOrThrow("steps")
                        ),
                        updatedAt = it.getLong(
                            it.getColumnIndexOrThrow("updated_at")
                        )
                    )
                )
            }
        }

        db.close()
        return result
    }

    fun getHourlyForDay(
        day: String
    ): List<HourlyStepEntry> {
        val db = getHelper().readableDatabase

        val cursor = db.rawQuery(
            """
            SELECT day, hour, steps, updated_at
            FROM hourly_steps
            WHERE day = ?
            ORDER BY hour ASC
            """.trimIndent(),
            arrayOf(day)
        )

        val result = mutableListOf<HourlyStepEntry>()

        cursor.use {
            while (it.moveToNext()) {
                result.add(
                    HourlyStepEntry(
                        day = it.getString(
                            it.getColumnIndexOrThrow("day")
                        ),
                        hour = it.getInt(
                            it.getColumnIndexOrThrow("hour")
                        ),
                        steps = it.getInt(
                            it.getColumnIndexOrThrow("steps")
                        ),
                        updatedAt = it.getLong(
                            it.getColumnIndexOrThrow("updated_at")
                        )
                    )
                )
            }
        }

        db.close()
        return result
    }
}