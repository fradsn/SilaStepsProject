package com.example.myapplication.db.models

data class PositionEntry(
    val id: Long = 0,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double
)
