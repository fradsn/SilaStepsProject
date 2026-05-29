package com.example.myapplication.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SQLiteHelper private constructor(context: Context) :
    SQLiteOpenHelper(context, "statistiche.db", null, 1) {

    companion object {
        @Volatile
        private var INSTANCE: SQLiteHelper? = null

        fun getInstance(context: Context): SQLiteHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SQLiteHelper(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

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
        // Se un giorno vuoi aggiornare le tabelle
    }
}
