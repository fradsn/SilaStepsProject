package com.example.myapplication.db.models

data class DailyStepEntry(
    val day: String,
    val steps: Int,
    val updatedAt: Long = System.currentTimeMillis()
)