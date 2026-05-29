package com.example.myapplication.db.models

data class BpmEntry(
    val id: Long = 0,
    val timestamp: Long,
    val bpm: Int
)