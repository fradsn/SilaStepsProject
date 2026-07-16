package com.example.myapplication.steps

import android.content.Context
import com.example.myapplication.db.models.DailyStepEntry
import com.example.myapplication.db.models.HourlyStepEntry
import com.google.android.gms.fitness.FitnessLocal
import com.google.android.gms.fitness.data.LocalDataType
import com.google.android.gms.fitness.data.LocalField
import com.google.android.gms.fitness.request.LocalDataReadRequest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine

data class StepHistorySnapshot(
    val dailyEntries: List<DailyStepEntry>,
    val hourlyEntries: List<HourlyStepEntry>,
    val todaySteps: Int
)

class StepRecordingManager(context: Context) {

    private val recordingClient =
        FitnessLocal.getLocalRecordingClient(
            context.applicationContext
        )

    fun subscribe(onResult: (Result<Unit>) -> Unit) {
        recordingClient
            .subscribe(LocalDataType.TYPE_STEP_COUNT_DELTA)
            .addOnSuccessListener {
                onResult(Result.success(Unit))
            }
            .addOnFailureListener { exception ->
                onResult(Result.failure(exception))
            }
    }
    suspend fun ensureSubscribed() {
        return suspendCancellableCoroutine { continuation ->

            subscribe { result ->
                if (continuation.isActive) {
                    continuation.resumeWith(result)
                }
            }
        }
    }

    suspend fun readTodayStepHistory(): StepHistorySnapshot {
        return suspendCancellableCoroutine { continuation ->

            readRecentStepHistory(
                days = 1
            ) { result ->

                if (continuation.isActive) {
                    continuation.resumeWith(result)
                }
            }
        }
    }


    fun readRecentStepHistory(
        days: Long = 10,
        onResult: (Result<StepHistorySnapshot>) -> Unit
    ) {
        val safeDays = days.coerceIn(1, 10)
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)

        // Oggi più i 9 giorni precedenti.
        val firstDay = today.minusDays(safeDays - 1)

        val startTimestamp = firstDay
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()

        val endTimestamp = System.currentTimeMillis()

        val request = LocalDataReadRequest.Builder()
            .aggregate(LocalDataType.TYPE_STEP_COUNT_DELTA)
            .bucketByTime(1, TimeUnit.HOURS)
            .setTimeRange(
                startTimestamp,
                endTimestamp,
                TimeUnit.MILLISECONDS
            )
            .build()

        recordingClient
            .readData(request)
            .addOnSuccessListener { response ->
                val dailyTotals = linkedMapOf<String, Int>()
                val hourlyTotals =
                    linkedMapOf<Pair<String, Int>, Int>()

                // Inizializza tutti i giorni e tutte le ore a zero.
                for (dayOffset in 0 until safeDays) {
                    val date = firstDay.plusDays(dayOffset)
                    val dayString = date.toString()

                    dailyTotals[dayString] = 0

                    for (hour in 0..23) {
                        hourlyTotals[dayString to hour] = 0
                    }
                }

                response.buckets.forEach { bucket ->
                    val bucketStart = bucket.getStartTime(
                        TimeUnit.MILLISECONDS
                    )

                    val bucketDateTime = Instant
                        .ofEpochMilli(bucketStart)
                        .atZone(zoneId)

                    val day = bucketDateTime
                        .toLocalDate()
                        .toString()

                    val hour = bucketDateTime.hour

                    var bucketSteps = 0

                    bucket.dataSets.forEach { dataSet ->
                        dataSet.dataPoints.forEach { dataPoint ->
                            bucketSteps += dataPoint
                                .getValue(LocalField.FIELD_STEPS)
                                .asInt()
                        }
                    }

                    val hourKey = day to hour

                    hourlyTotals[hourKey] =
                        (hourlyTotals[hourKey] ?: 0) + bucketSteps

                    dailyTotals[day] =
                        (dailyTotals[day] ?: 0) + bucketSteps
                }

                val updateTimestamp = System.currentTimeMillis()

                val dailyEntries = dailyTotals
                    .toSortedMap()
                    .map { (day, steps) ->
                        DailyStepEntry(
                            day = day,
                            steps = steps,
                            updatedAt = updateTimestamp
                        )
                    }

                val hourlyEntries = hourlyTotals
                    .entries
                    .sortedBy { entry ->
                        val day = entry.key.first
                        val hour = entry.key.second
                            .toString()
                            .padStart(2, '0')

                        "$day-$hour"
                    }
                    .map { entry ->
                        HourlyStepEntry(
                            day = entry.key.first,
                            hour = entry.key.second,
                            steps = entry.value,
                            updatedAt = updateTimestamp
                        )
                    }

                val todaySteps =
                    dailyTotals[today.toString()] ?: 0

                onResult(
                    Result.success(
                        StepHistorySnapshot(
                            dailyEntries = dailyEntries,
                            hourlyEntries = hourlyEntries,
                            todaySteps = todaySteps
                        )
                    )
                )
            }
            .addOnFailureListener { exception ->
                onResult(Result.failure(exception))
            }
    }
}