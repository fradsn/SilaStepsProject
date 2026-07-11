package com.example.myapplication.db.dao

import com.example.myapplication.db.models.PredictionEntry
import com.example.myapplication.db.models.StepEntry
import kotlin.math.roundToInt

class StepDao {

    fun getAll(predictions: List<PredictionEntry>): List<StepEntry> {
        var tot = 0f

        return predictions.mapNotNull { entry ->
            when (entry.activity) {
                "Walking" -> {
                    tot += 1.8f
                    StepEntry(timestamp = entry.timestamp, tot = tot.roundToInt())
                }
                "Jogging" -> {
                    tot += 2f
                    StepEntry(timestamp = entry.timestamp, tot = tot.roundToInt())
                }
                else -> null
            }
        }
    }


}