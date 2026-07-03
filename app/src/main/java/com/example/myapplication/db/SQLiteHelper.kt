package com.example.myapplication.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// Il costruttore ora accetta il contesto e il flag userId dinamico per isolare i file fisici
class SQLiteHelper(context: Context, userId: String) :
    SQLiteOpenHelper(context, "statistiche_$userId.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE bpm (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                bpm INTEGER NOT NULL
            );
            """
        )

        db.execSQL(
            """
            CREATE TABLE pressure (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                systolic INTEGER NOT NULL,
                diastolic INTEGER NOT NULL
            );
            """
        )

        db.execSQL(
            """
            CREATE TABLE o2 (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                value INTEGER NOT NULL
            );
            """
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS bpm")
        db.execSQL("DROP TABLE IF EXISTS pressure")
        db.execSQL("DROP TABLE IF EXISTS o2")
        onCreate(db)
    }
}