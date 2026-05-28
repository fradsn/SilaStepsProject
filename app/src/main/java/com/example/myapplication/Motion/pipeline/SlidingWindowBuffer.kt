package com.example.myapplication.Motion.pipeline

import com.example.myapplication.Motion.model.AccelSample
import com.example.myapplication.Motion.model.AccelWindow
import java.util.ArrayDeque

class SlidingWindowBuffer(
    private val windowSize: Int = 100,
    private val stepSize: Int = 50
) {
    private val buffer = ArrayDeque<AccelSample>()

    @Synchronized
    fun addSample(sample: AccelSample): AccelWindow? {
        buffer.addLast(sample)

        if (buffer.size < windowSize) return null

        val snapshot = buffer.take(windowSize)

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

    @Synchronized
    fun clear() {
        buffer.clear()
    }

    @Synchronized
    fun currentSize(): Int = buffer.size
}