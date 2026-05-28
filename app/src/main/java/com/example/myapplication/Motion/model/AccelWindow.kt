package com.example.myapplication.Motion.model

data class AccelWindow(
    val startTimestamp: Long,
    val endTimestamp: Long,
    val data: Array<FloatArray>
) {
    val windowSize: Int
        get() = data.size

    val channels: Int
        get() = if (data.isNotEmpty()) data[0].size else 0
}