package com.example.myapplication

import java.util.ArrayDeque

class SlidingWindowBuffer(
    private val windowSize: Int = 100,
    private val stepSize: Int = 25
) {
    private val buffer = ArrayDeque<AccelSample>()

    fun addSample(sample: AccelSample): AccelWindow? {
        buffer.addLast(sample)

        if (buffer.size < windowSize) return null

        val snapshot = buffer.take(windowSize)

        // 3 canali: acc_x, acc_y, acc_z
        val matrix = Array(windowSize) { FloatArray(3) }
        snapshot.forEachIndexed { index, item ->
            matrix[index][0] = item.x
            matrix[index][1] = item.y
            matrix[index][2] = item.z
        }

        val window = AccelWindow(
            startTimestamp = snapshot.first().timestamp,
            endTimestamp = snapshot.last().timestamp,
            data = matrix
        )

        repeat(stepSize.coerceAtMost(buffer.size)) {
            buffer.removeFirst()
        }

        return window
    }

    fun clear() {
        buffer.clear()
    }

    fun currentSize(): Int = buffer.size
}