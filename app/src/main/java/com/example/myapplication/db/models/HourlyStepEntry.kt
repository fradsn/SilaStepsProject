package com.example.myapplication.db.models

data class HourlyStepEntry(
    val day: String,
    val hour: Int,
    val steps: Int,
    val updatedAt: Long = System.currentTimeMillis()
)