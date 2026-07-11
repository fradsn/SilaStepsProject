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

        // Intervallo di sveglia del ciclo impostato a 5 minuti
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
                // Si mette in pausa per 5 minuti prima del prossimo ciclo
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

        // 2. Prendi i timestamp da 'o2'
        val cursorO2 = database.rawQuery("SELECT timestamp FROM o2 WHERE timestamp > ?", arrayOf(lastTimestamp.toString()))
        while (cursorO2.moveToNext()) { uniqueTimestamps.add(cursorO2.getLong(0)) }
        cursorO2.close()

        // 3. Prendi i timestamp da 'pressure'
        val cursorPressure = database.rawQuery("SELECT timestamp FROM pressure WHERE timestamp > ?", arrayOf(lastTimestamp.toString()))
        while (cursorPressure.moveToNext()) { uniqueTimestamps.add(cursorPressure.getLong(0)) }
        cursorPressure.close()

        // 4. Prendi i timestamp reali da 'prediction' (Storico Attività Shimmer)
        val cursorPrediction = database.rawQuery("SELECT timestamp FROM prediction WHERE timestamp > ?", arrayOf(lastTimestamp.toString()))
        while (cursorPrediction.moveToNext()) { uniqueTimestamps.add(cursorPrediction.getLong(0)) }
        cursorPrediction.close()

        // 5. Prendi i timestamp reali da 'position' (Storico GPS)
        val cursorPosition = database.rawQuery("SELECT timestamp FROM position WHERE timestamp > ?", arrayOf(lastTimestamp.toString()))
        while (cursorPosition.moveToNext()) { uniqueTimestamps.add(cursorPosition.getLong(0)) }
        cursorPosition.close()

        if (uniqueTimestamps.isEmpty()) {
            return Pair(emptyList(), lastTimestamp)
        }

        val sortedTimestamps = uniqueTimestamps.sorted()
        val highestTimestampInBatch = sortedTimestamps.last()

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        // Variabili di cache per la logica di Forward Filling
        var lastKnownBpm = 0
        var lastKnownSpo2 = 0
        var lastKnownPressure = "0/0"
        var lastKnownLatitude = 0.0
        var lastKnownLongitude = 0.0
        var lastKnownActivity = "UNKNOWN"

        for (ts in sortedTimestamps) {

            // 1. Recupera l'ultimo dato utile per i BPM
            val cBpm = database.rawQuery("SELECT bpm FROM bpm WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1", arrayOf(ts.toString()))
            if (cBpm.moveToFirst()) { lastKnownBpm = cBpm.getInt(0) }
            cBpm.close()

            // 2. Recupera l'ultimo dato utile per lo SpO2
            val cO2 = database.rawQuery("SELECT value FROM o2 WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1", arrayOf(ts.toString()))
            if (cO2.moveToFirst()) { lastKnownSpo2 = cO2.getInt(0) }
            cO2.close()

            // 3. Recupera l'ultimo dato utile per la Pressione
            val cPress = database.rawQuery("SELECT systolic, diastolic FROM pressure WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1", arrayOf(ts.toString()))
            if (cPress.moveToFirst()) {
                lastKnownPressure = "${cPress.getInt(0)}/${cPress.getInt(1)}"
            }
            cPress.close()

            // 4. Recupera lo storico reale del contesto dell'attività Shimmer
            val cAct = database.rawQuery("SELECT activity FROM prediction WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1", arrayOf(ts.toString()))
            if (cAct.moveToFirst()) { lastKnownActivity = cAct.getString(0) }
            cAct.close()

            // 5. Recupera lo storico reale della Posizione GPS
            val cPos = database.rawQuery("SELECT latitude, longitude FROM position WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1", arrayOf(ts.toString()))
            if (cPos.moveToFirst()) {
                lastKnownLatitude = cPos.getDouble(0)
                lastKnownLongitude = cPos.getDouble(1)
            }
            cPos.close()

            // Classificazione realistica delle anomalie basata sui valori fusi correnti
            val currentAlert = when {
                lastKnownBpm > 0 && (lastKnownBpm < 50 || lastKnownBpm > 100) -> "heart_rate_anomaly"
                lastKnownSpo2 > 0 && lastKnownSpo2 < 92 -> "hypoxemia_anomaly"
                else -> "normal"
            }

            // Impacchettamento definitivo dell'oggetto AwsRecord
            val record = AwsRecord(
                userId = uId,
                x = 0.12,                               // PROVVISORIAMNETE FITTIZIO
                y = 0.45,                               // PROVVISORIAMNETE FITTIZIO
                z = 9.81,                               // PROVVISORIAMNETE FITTIZIO
                activity = lastKnownActivity,           // REALE DA DB
                heartRate = lastKnownBpm,               // REALE DA DB
                spo2 = lastKnownSpo2,                   // REALE DA DB
                bloodPressure = lastKnownPressure,       // REALE DA DB
                latitude = lastKnownLatitude,           // REALE DA DB
                longitude = lastKnownLongitude,         // REALE DA DB
                steps = 1540,                           // PROVVISORIAMENTE FITTIZIO (Tabella non ancora creata)
                alert = currentAlert,                   // DINAMICO SU PARAMETRI REALI
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
        serviceScope.cancel()
        Log.d(TAG, "AwsSyncService Destroyed.")
    }
}