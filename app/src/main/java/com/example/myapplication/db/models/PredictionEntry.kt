package com.example.myapplication.db.models

data class PredictionEntry(
    val id: Long = 0,
    val timestamp: Long,
    val activity: String?,
    val confidence: Int
)