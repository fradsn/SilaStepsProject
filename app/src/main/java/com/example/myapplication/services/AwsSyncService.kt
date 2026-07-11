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

        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L

        // Actions and Extras matching exactly with the Profile and Health services pipeline
        const val ACTION_TRIGGER_IMMEDIATE_SYNC = "com.example.myapplication.TRIGGER_IMMEDIATE_SYNC"
        const val EXTRA_ALERT_TYPE = "EXTRA_ALERT_TYPE"
        const val EXTRA_USER_MESSAGE = "EXTRA_USER_MESSAGE"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AwsSyncService Created. Starting Foreground Configuration...")
        startForeground(NOTIFICATION_ID, createNotification())
        startSyncLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TRIGGER_IMMEDIATE_SYNC) {
            val alertType = intent.getStringExtra(EXTRA_ALERT_TYPE) ?: "unknown_anomaly"
            val userMessage = intent.getStringExtra(EXTRA_USER_MESSAGE) ?: ""

            Log.w(TAG, "🚨 IMMEDIATE SYNC REQUEST RECEIVED: '$alertType' | Note: '$userMessage'. Bypassing queue loop!")

            // Dispatch an immediate network transmission job outside the normal loop interval
            serviceScope.launch {
                try {
                    performSynchronization(forcedAlert = alertType, customMessage = userMessage)
                } catch (e: Exception) {
                    Log.e(TAG, "Immediate forced synchronization failed", e)
                }
            }
        }
        return START_STICKY
    }

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = serviceScope.launch {
            while (isActive) {
                try {
                    performSynchronization(forcedAlert = null, customMessage = null)
                } catch (e: Exception) {
                    Log.e(TAG, "Exception during active sync loop iteration", e)
                }
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    private suspend fun performSynchronization(forcedAlert: String?, customMessage: String? = null) {
        Log.d(TAG, "Sync operation executing. ForcedAlert state: $forcedAlert | Custom Message: $customMessage")

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.w(TAG, "User not logged into Firebase session. Synchronization postponed.")
            return
        }
        val firebaseUserId = currentUser.uid

        val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSyncTimestamp = sharedPrefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0L)

        val dbHelper = SQLiteHelper(applicationContext, firebaseUserId)
        val (recordsToSend, maxTimestampInBatch) = fetchNewRecordsStrict(
            dbHelper = dbHelper,
            uId = firebaseUserId,
            lastTimestamp = lastSyncTimestamp,
            forcedAlert = forcedAlert,
            customMessage = customMessage
        )

        if (recordsToSend.isEmpty()) {
            Log.d(TAG, "No new database entries discovered since the last synchronization cycle.")
            return
        }

        val uniqueRecordsToSend = recordsToSend.distinctBy { it.timestamp }
        Log.d(TAG, "Filtered batch metrics: Original count=${recordsToSend.size} | Unique items count=${uniqueRecordsToSend.size}")

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val apiService = retrofit.create(AwsApiService::class.java)
        val payload = AwsSyncPayload(records = uniqueRecordsToSend)

        try {
            val response = apiService.uploadRecords(apiKey = API_KEY, payload = payload)
            if (response.isSuccessful) {
                Log.d(TAG, "Cloud sync transmission successful! ${uniqueRecordsToSend.size} records uploaded to target bucket.")

                sharedPrefs.edit().putLong(KEY_LAST_SYNC_TIMESTAMP, maxTimestampInBatch).apply()

                withContext(Dispatchers.Main) {
                    val msg = if (forcedAlert != null) "🚨 IMMEDIATE Cloud Sync Triggered!" else "Cloud Sync Successful!"
                    Toast.makeText(applicationContext, "$msg (${uniqueRecordsToSend.size} records)", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e(TAG, "AWS Remote Endpoint rejected request: ${response.code()} - ${response.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network transport failure encountered during background payload upload", e)
        }
    }

    private fun fetchNewRecordsStrict(
        dbHelper: SQLiteHelper,
        uId: String,
        lastTimestamp: Long,
        forcedAlert: String?,
        customMessage: String?
    ): Pair<List<AwsRecord>, Long> {
        val list = mutableListOf<AwsRecord>()
        val database = dbHelper.readableDatabase
        val uniqueTimestamps = mutableSetOf<Long>()

        // 1. Recupero dei timestamp dai cursori locali
        val cursorBpm = database.rawQuery("SELECT timestamp FROM bpm WHERE timestamp > ?", arrayOf(lastTimestamp.toString()))
        while (cursorBpm.moveToNext()) { uniqueTimestamps.add(cursorBpm.getLong(0)) }
        cursorBpm.close()

        val cursorO2 = database.rawQuery("SELECT timestamp FROM o2 WHERE timestamp > ?", arrayOf(lastTimestamp.toString()))
        while (cursorO2.moveToNext()) { uniqueTimestamps.add(cursorO2.getLong(0)) }
        cursorO2.close()

        val cursorPressure = database.rawQuery("SELECT timestamp FROM pressure WHERE timestamp > ?", arrayOf(lastTimestamp.toString()))
        while (cursorPressure.moveToNext()) { uniqueTimestamps.add(cursorPressure.getLong(0)) }
        cursorPressure.close()

        val cursorPrediction = database.rawQuery("SELECT timestamp FROM prediction WHERE timestamp > ?", arrayOf(lastTimestamp.toString()))
        while (cursorPrediction.moveToNext()) { uniqueTimestamps.add(cursorPrediction.getLong(0)) }
        cursorPrediction.close()

        val cursorPosition = database.rawQuery("SELECT timestamp FROM position WHERE timestamp > ?", arrayOf(lastTimestamp.toString()))
        while (cursorPosition.moveToNext()) { uniqueTimestamps.add(cursorPosition.getLong(0)) }
        cursorPosition.close()

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        // Variabili per la tecnica "last known value" (ultimo valore noto)
        var lastKnownBpm = 0
        var lastKnownSpo2 = 0
        var lastKnownPressure = "0/0"
        var lastKnownLatitude = 0.0
        var lastKnownLongitude = 0.0
        var lastKnownActivity = "UNKNOWN"

        // --- CASO DI FALLBACK (IL TUO SUGGERIMENTO) ---
        // Se non ci sono nuove misurazioni nel DB, ma è stato forzato un allert/report, creiamo un record fittizio ora
        if (uniqueTimestamps.isEmpty()) {
            if (forcedAlert != null) {
                val currentNowMs = System.currentTimeMillis()
                Log.w(TAG, "No new measurements found in DB for this alert. Creating a fallback record at current timestamp.")

                // Recuperiamo comunque gli ultimissimi dati memorizzati (anche se antecedenti a lastTimestamp)
                val cBpm = database.rawQuery("SELECT bpm FROM bpm ORDER BY timestamp DESC LIMIT 1", null)
                if (cBpm.moveToFirst()) { lastKnownBpm = cBpm.getInt(0) }
                cBpm.close()

                val cO2 = database.rawQuery("SELECT value FROM o2 ORDER BY timestamp DESC LIMIT 1", null)
                if (cO2.moveToFirst()) { lastKnownSpo2 = cO2.getInt(0) }
                cO2.close()

                val cPress = database.rawQuery("SELECT systolic, diastolic FROM pressure ORDER BY timestamp DESC LIMIT 1", null)
                if (cPress.moveToFirst()) { lastKnownPressure = "${cPress.getInt(0)}/${cPress.getInt(1)}" }
                cPress.close()

                val cAct = database.rawQuery("SELECT activity FROM prediction ORDER BY timestamp DESC LIMIT 1", null)
                if (cAct.moveToFirst()) { lastKnownActivity = cAct.getString(0) }
                cAct.close()

                val cPos = database.rawQuery("SELECT latitude, longitude FROM position ORDER BY timestamp DESC LIMIT 1", null)
                if (cPos.moveToFirst()) {
                    lastKnownLatitude = cPos.getDouble(0)
                    lastKnownLongitude = cPos.getDouble(1)
                }
                cPos.close()

                val finalAlertString = if (!customMessage.isNullOrBlank()) {
                    "$forcedAlert | Note: $customMessage"
                } else {
                    forcedAlert
                }

                val fallbackRecord = AwsRecord(
                    userId = uId,
                    x = 0.12,
                    y = 0.45,
                    z = 9.81,
                    activity = lastKnownActivity,
                    heartRate = lastKnownBpm,
                    spo2 = lastKnownSpo2,
                    bloodPressure = lastKnownPressure,
                    latitude = lastKnownLatitude,
                    longitude = lastKnownLongitude,
                    steps = 1540,
                    alert = finalAlertString,
                    timestamp = isoFormat.format(Date(currentNowMs))
                )
                list.add(fallbackRecord)

                // Ritorniamo il record generato. NON aggiorniamo lastSyncTimestamp con currentNowMs
                // per evitare di saltare future letture reali se l'orologio di sistema diverge dal DB.
                return Pair(list, lastTimestamp)
            } else {
                // Se non c'è nessun allarme e non ci sono record, non fare nulla
                return Pair(emptyList(), lastTimestamp)
            }
        }

        // 2. Comportamento standard se ci sono record reali nel DB (rimane invariato)
        val sortedTimestamps = uniqueTimestamps.sorted()
        val highestTimestampInBatch = sortedTimestamps.last()

        for (ts in sortedTimestamps) {
            val cBpm = database.rawQuery("SELECT bpm FROM bpm WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1", arrayOf(ts.toString()))
            if (cBpm.moveToFirst()) { lastKnownBpm = cBpm.getInt(0) }
            cBpm.close()

            val cO2 = database.rawQuery("SELECT value FROM o2 WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1", arrayOf(ts.toString()))
            if (cO2.moveToFirst()) { lastKnownSpo2 = cO2.getInt(0) }
            cO2.close()

            val cPress = database.rawQuery("SELECT systolic, diastolic FROM pressure WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1", arrayOf(ts.toString()))
            if (cPress.moveToFirst()) { lastKnownPressure = "${cPress.getInt(0)}/${cPress.getInt(1)}" }
            cPress.close()

            val cAct = database.rawQuery("SELECT activity FROM prediction WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1", arrayOf(ts.toString()))
            if (cAct.moveToFirst()) { lastKnownActivity = cAct.getString(0) }
            cAct.close()

            val cPos = database.rawQuery("SELECT latitude, longitude FROM position WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1", arrayOf(ts.toString()))
            if (cPos.moveToFirst()) {
                lastKnownLatitude = cPos.getDouble(0)
                lastKnownLongitude = cPos.getDouble(1)
            }
            cPos.close()

            val record = AwsRecord(
                userId = uId,
                x = 0.12,
                y = 0.45,
                z = 9.81,
                activity = lastKnownActivity,
                heartRate = lastKnownBpm,
                spo2 = lastKnownSpo2,
                bloodPressure = lastKnownPressure,
                latitude = lastKnownLatitude,
                longitude = lastKnownLongitude,
                steps = 1540,
                alert = "no_alert",
                timestamp = isoFormat.format(Date(ts))
            )
            list.add(record)
        }

        // Applica il tipo di allarme all'ultimo record inserito del batch reale
        if (forcedAlert != null && list.isNotEmpty()) {
            val lastIndex = list.size - 1
            val mostRecentRecord = list[lastIndex]

            val finalAlertString = if (!customMessage.isNullOrBlank()) {
                "$forcedAlert | Note: $customMessage"
            } else {
                forcedAlert
            }

            list[lastIndex] = mostRecentRecord.copy(alert = finalAlertString)
            Log.w(TAG, "Manual alert injection successful into newest data item: ${mostRecentRecord.timestamp} -> Content: $finalAlertString")
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