package com.example.myapplication.db.models

data class PressureEntry(
    val id: Long = 0,
    val timestamp: Long,
    val systolic: Int,
    val diastolic: Int
)
