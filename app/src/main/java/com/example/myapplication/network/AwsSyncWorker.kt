package com.example.myapplication.workers

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myapplication.db.SQLiteHelper
import com.example.myapplication.network.AwsApiService
import com.example.myapplication.network.AwsRecord
import com.example.myapplication.network.AwsSyncPayload
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AwsSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "AwsSyncWorker"
        private const val BASE_URL = "https://4m4fvs5pn0.execute-api.eu-north-1.amazonaws.com/"
        private const val API_KEY = "rwT6tGGmplSObjaoVdhb4XN0N0rE1x68k6rLFid9"

        private const val PREFS_NAME = "AwsSyncPrefs"
        private const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting data synchronization to AWS...")

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.w(TAG, "User not logged in Firebase. Sync postponed.")
            return Result.retry()
        }
        val firebaseUserId = currentUser.uid

        val sharedPrefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSyncTimestamp = sharedPrefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0L)

        val dbHelper = SQLiteHelper(applicationContext, firebaseUserId)

        val (recordsToSend, maxTimestampInBatch) = fetchNewRecordsStrict(dbHelper, firebaseUserId, lastSyncTimestamp)

        if (recordsToSend.isEmpty()) {
            Log.d(TAG, "No new measurements found in any table since last sync. Skipping network call.")
            return Result.success()
        }

        // 🚨 RISOLUZIONE BUG DYNAMODB: Filtra e mantiene un unico record per ogni timestamp per evitare duplicati in BatchWriteItem
        val uniqueRecordsToSend = recordsToSend.distinctBy { it.timestamp }
        Log.d(TAG, "Cleaned batch for AWS: Original size = ${recordsToSend.size} | Unique size = ${uniqueRecordsToSend.size}")

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val apiService = retrofit.create(AwsApiService::class.java)

        // Assegna la lista pulita priva di chiavi duplicate al payload di rete
        val payload = AwsSyncPayload(records = uniqueRecordsToSend)

        return try {
            val response = apiService.uploadRecords(apiKey = API_KEY, payload = payload)
            if (response.isSuccessful) {
                Log.d(TAG, "Sync successful! ${uniqueRecordsToSend.size} records uploaded.")

                sharedPrefs.edit().putLong(KEY_LAST_SYNC_TIMESTAMP, maxTimestampInBatch).apply()

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        applicationContext,
                        "Data successfully synced with DynamoDB! (${uniqueRecordsToSend.size} records)",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                Result.success()
            } else {
                Log.e(TAG, "AWS Gateway Error: ${response.code()} - ${response.errorBody()?.string()}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error during synchronization", e)
            Result.retry()
        }
    }

    private fun fetchNewRecordsStrict(dbHelper: SQLiteHelper, uId: String, lastTimestamp: Long): Pair<List<AwsRecord>, Long> {
        val list = mutableListOf<AwsRecord>()
        val database = dbHelper.readableDatabase

        val uniqueTimestamps = mutableSetOf<Long>()

        val cursorBpm = database.rawQuery("SELECT timestamp FROM bpm WHERE timestamp > ?", arrayOf(lastTimestamp.toString()))
        while (cursorBpm.moveToNext()) { uniqueTimestamps.add(cursorBpm.getLong(0)) }
        cursorBpm.close()

        val cursorO2 = database.rawQuery("SELECT value FROM o2 WHERE timestamp > ?", arrayOf(lastTimestamp.toString()))
        while (cursorO2.moveToNext()) { uniqueTimestamps.add(cursorO2.getLong(0)) }
        cursorO2.close()

        val cursorPressure = database.rawQuery("SELECT timestamp FROM pressure WHERE timestamp > ?", arrayOf(lastTimestamp.toString()))
        while (cursorPressure.moveToNext()) { uniqueTimestamps.add(cursorPressure.getLong(0)) }
        cursorPressure.close()

        if (uniqueTimestamps.isEmpty()) {
            return Pair(emptyList(), lastTimestamp)
        }

        val sortedTimestamps = uniqueTimestamps.sorted()
        val highestTimestampInBatch = sortedTimestamps.last()

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        for (ts in sortedTimestamps) {
            var realBpm = 0
            val cBpm = database.rawQuery("SELECT bpm FROM bpm WHERE timestamp = ?", arrayOf(ts.toString()))
            if (cBpm.moveToFirst()) { realBpm = cBpm.getInt(0) }
            cBpm.close()

            var realSpo2 = 0
            val cO2 = database.rawQuery("SELECT value FROM o2 WHERE timestamp = ?", arrayOf(ts.toString()))
            if (cO2.moveToFirst()) { realSpo2 = cO2.getInt(0) }
            cO2.close()

            var realPressure = "0/0"
            val cPress = database.rawQuery("SELECT systolic, diastolic FROM pressure WHERE timestamp = ?", arrayOf(ts.toString()))
            if (cPress.moveToFirst()) {
                realPressure = "${cPress.getInt(0)}/${cPress.getInt(1)}"
            }
            cPress.close()

            val mockAlert = when {
                realBpm > 0 && (realBpm < 50 || realBpm > 100) -> "heart_rate_anomaly"
                realSpo2 > 0 && realSpo2 < 92 -> "hypoxemia_anomaly"
                else -> "normal"
            }

            val record = AwsRecord(
                userId = uId,
                x = 0.12,
                y = 0.45,
                z = 9.81,
                activity = "Walking",
                heartRate = realBpm,
                spo2 = realSpo2,
                bloodPressure = realPressure,
                latitude = 39.2983,
                longitude = 16.2530,
                steps = 1540,
                alert = mockAlert,
                timestamp = isoFormat.format(Date(ts))
            )
            list.add(record)
        }

        return Pair(list, highestTimestampInBatch)
    }
}