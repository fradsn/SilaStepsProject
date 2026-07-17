package com.example.myapplication.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SQLiteHelper(
    context: Context,
    userId: String
) : SQLiteOpenHelper(
    context,
    "statistiche_$userId.db",
    null,
    DATABASE_VERSION
) {

    companion object {
        private const val DATABASE_VERSION = 2
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE bpm (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                bpm INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE pressure (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                systolic INTEGER NOT NULL,
                diastolic INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE o2 (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                value INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE prediction (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                activity INTEGER NOT NULL,
                confidence INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE position (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                latitude DOUBLE NOT NULL,
                longitude DOUBLE NOT NULL
            )
            """.trimIndent()
        )

        // Tabella già utilizzata da AWS.
        db.execSQL(
            """
            CREATE TABLE steps (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                value INTEGER NOT NULL
            )
            """.trimIndent()
        )

        createStepHistoryTables(db)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 2) {
            createStepHistoryTables(db)
        }
    }

    private fun createStepHistoryTables(db: SQLiteDatabase) {
        // Un solo totale per ciascun giorno.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_steps (
                day TEXT PRIMARY KEY NOT NULL,
                steps INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // Un valore per ciascuna ora della giornata.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS hourly_steps (
                day TEXT NOT NULL,
                hour INTEGER NOT NULL CHECK(hour BETWEEN 0 AND 23),
                steps INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(day, hour)
            )
            """.trimIndent()
        )
    }
}