package com.example.myapplication.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.myapplication.R
import com.example.myapplication.db.SQLiteHelper
import com.example.myapplication.network.AwsApiService
import com.example.myapplication.network.AwsRecord
import com.example.myapplication.network.AwsSyncPayload
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AwsSyncService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    companion object {
        private const val TAG = "AwsSyncService"
        private const val BASE_URL = "https://4m4fvs5pn0.execute-api.eu-north-1.amazonaws.com/"
        private const val API_KEY = "rwT6tGGmplSObjaoVdhb4XN0N0rE1x68k6rLFid9"

        private const val PREFS_NAME = "AwsSyncPrefs"
        private const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp"

        private const val NOTIFICATION_ID = 2005
        private const val CHANNEL_ID = "aws_sync_channel"

        // Intervallo di sveglia del ciclo (5 Minuti espressi in Millisecondi)
        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AwsSyncService Created. Starting Foreground Configuration...")
        startForeground(NOTIFICATION_ID, createNotification())

        // Avvia il ciclo infinito temporizzato
        startSyncLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "AwsSyncService onStartCommand called.")
        // Rende il servizio Sticky: se il sistema lo killa per RAM, lo riavvia appena possibile
        return START_STICKY
    }

    private fun startSyncLoop() {
        syncJob?.cancel() // Evita loop sovrapposti
        syncJob = serviceScope.launch {
            while (isActive) {
                try {
                    performSynchronization()
                } catch (e: Exception) {
                    Log.e(TAG, "Exception during active sync loop iteration", e)
                }
                // Si mette in pausa per 15 minuti prima del prossimo ciclo
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    private suspend fun performSynchronization() {
        Log.d(TAG, "Sync loop awakened. Executing database export...")

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.w(TAG, "User not logged in Firebase. Synchronization postponed.")
            return
        }
        val firebaseUserId = currentUser.uid

        val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSyncTimestamp = sharedPrefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0L)

        val dbHelper = SQLiteHelper(applicationContext, firebaseUserId)
        val (recordsToSend, maxTimestampInBatch) = fetchNewRecordsStrict(dbHelper, firebaseUserId, lastSyncTimestamp)

        if (recordsToSend.isEmpty()) {
            Log.d(TAG, "No new measurements found since last iteration.")
            return
        }

        // Rimozione duplicati anti-ValidationException per DynamoDB
        val uniqueRecordsToSend = recordsToSend.distinctBy { it.timestamp }
        Log.d(TAG, "Filtered batch: Original=${recordsToSend.size} | Unique=${uniqueRecordsToSend.size}")

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val apiService = retrofit.create(AwsApiService::class.java)
        val payload = AwsSyncPayload(records = uniqueRecordsToSend)

        try {
            val response = apiService.uploadRecords(apiKey = API_KEY, payload = payload)
            if (response.isSuccessful) {
                Log.d(TAG, "Upload successful! ${uniqueRecordsToSend.size} items sent.")

                sharedPrefs.edit().putLong(KEY_LAST_SYNC_TIMESTAMP, maxTimestampInBatch).apply()

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        applicationContext,
                        "Cloud Sync Successful! (${uniqueRecordsToSend.size} records)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                Log.e(TAG, "AWS Endpoint error: ${response.code()} - ${response.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network failure during background service upload", e)
        }
    }

    private fun fetchNewRecordsStrict(dbHelper: SQLiteHelper, uId: String, lastTimestamp: Long): Pair<List<AwsRecord>, Long> {
        val list = mutableListOf<AwsRecord>()
        val database = dbHelper.readableDatabase
        val uniqueTimestamps = mutableSetOf<Long>()

        // 1. Prendi i timestamp da 'bpm'
        val cursorBpm = database.rawQuery("SELECT timestamp FROM bpm WHERE timestamp > ?", arrayOf(lastTimestamp.toString()))
        while (cursorBpm.moveToNext()) { uniqueTimestamps.add(cursorBpm.getLong(0)) }
        cursorBpm.close()

        // 2. Prendi i timestamp da 'o2' (Usa la query corretta leggendo il valore modificato)
        val cursorO2 = database.rawQuery("SELECT value FROM o2 WHERE timestamp > ?", arrayOf(lastTimestamp.toString()))
        while (cursorO2.moveToNext()) { uniqueTimestamps.add(cursorO2.getLong(0)) }
        cursorO2.close()

        // 3. Prendi i timestamp da 'pressure'
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

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "AWS Database Cloud Sync", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cloud Synchronizer Active")
            .setContentText("Pipelining IoT health records to DynamoDB...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // Cancella lo scope per evitare coroutine appese o memory leak
        serviceScope.cancel()
        Log.d(TAG, "AwsSyncService Destroyed.")
    }
}