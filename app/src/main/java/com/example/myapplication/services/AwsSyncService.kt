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
import com.example.myapplication.network.AwsRawRecord
import com.example.myapplication.network.AwsSyncPayload
import com.example.myapplication.network.AwsRawSyncPayload
import com.example.myapplication.network.IntercettaFinestrePredizione
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
        private const val KEY_LAST_RAW_SYNC_TIMESTAMP = "last_raw_sync_timestamp"

        private const val NOTIFICATION_ID = 2005
        private const val CHANNEL_ID = "aws_sync_channel"

        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L

        // Costanti di validità biologica mutate da HealthMonitoringService
        private const val VITAL_VALIDITY_WINDOW_BPM = 60 * 1000L       // Tolleranza 1 minuto
        private const val VITAL_VALIDITY_WINDOW_PRESSURE = 5 * 60 * 1000L // Tolleranza 5 minuti
        private const val SPO2_WINDOW = 30 * 1000L
        private const val GAP_WINDOW = 5 * 1000L

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

        // 1. Inizializzazione Retrofit e API Service comune
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val apiService = retrofit.create(AwsApiService::class.java)

        // =====================================================================================
        // PIPELINE 1: INVIO MISURAZIONI GENERALI AGGREGATE (TABELLA DYNAMODB 1)
        // =====================================================================================
        val (recordsToSend, maxTimestampInBatch) = fetchNewRecordsStrict(
            dbHelper = dbHelper,
            uId = firebaseUserId,
            lastTimestamp = lastSyncTimestamp,
            forcedAlert = forcedAlert,
            customMessage = customMessage
        )

        if (recordsToSend.isNotEmpty()) {
            val uniqueRecordsToSend = recordsToSend.distinctBy { it.timestamp }
            Log.d(TAG, "Filtered batch metrics: Original count=${recordsToSend.size} | Unique items count=${uniqueRecordsToSend.size}")

            try {
                val payload = AwsSyncPayload(records = uniqueRecordsToSend)
                val response = apiService.uploadRecords(apiKey = API_KEY, payload = payload)
                if (response.isSuccessful) {
                    Log.d(TAG, "Cloud sync transmission successful! ${uniqueRecordsToSend.size} records uploaded to target bucket.")
                    sharedPrefs.edit().putLong(KEY_LAST_SYNC_TIMESTAMP, maxTimestampInBatch).apply()
                } else {
                    Log.e(TAG, "AWS Remote Endpoint rejected request: ${response.code()} - ${response.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network transport failure encountered during background payload upload", e)
            }
        } else {
            Log.d(TAG, "No new database entries discovered since the last synchronization cycle.")
        }

        // =====================================================================================
        // PIPELINE 2: INVIO DELLE MEDIE DELLE FINESTRE RAW SHIMMER XYZ (TABELLA DYNAMODB 2)
        // =====================================================================================
        // La specifica prevede che i dati RAW rimangano FISSI a 5 minuti e non vengano inviati subito per allerta
        if (forcedAlert == null) {
            val (rawRecordsToSend, maxRawTimestamp) = fetchNewRawRecordsFromCache(uId = firebaseUserId)

            if (rawRecordsToSend.isNotEmpty()) {
                try {
                    val rawPayload = AwsRawSyncPayload(records = rawRecordsToSend)
                    val response = apiService.uploadRawRecords(apiKey = API_KEY, payload = rawPayload)
                    if (response.isSuccessful) {
                        Log.d(TAG, "Cloud sync dati RAW (Medie finestre RAM) completato! ${rawRecordsToSend.size} record inviati.")
                        sharedPrefs.edit().putLong(KEY_LAST_RAW_SYNC_TIMESTAMP, maxRawTimestamp).apply()
                    } else {
                        Log.e(TAG, "Endpoint remoto RAW ha rifiutato la richiesta delle medie: ${response.code()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Errore durante il trasporto di rete dei dati RAW delle finestre", e)
                }
            } else {
                Log.d(TAG, "Nessun dato RAW intercettato in cache RAM negli ultimi 5 minuti.")
            }
        }

        withContext(Dispatchers.Main) {
            val msg = if (forcedAlert != null) "🚨 IMMEDIATE Cloud Sync Triggered!" else "Cloud Sync Successful!"
            Toast.makeText(applicationContext, "$msg", Toast.LENGTH_SHORT).show()
        }
    }

    // Estrae i punti medi accumulati in RAM ed esegue il flush atomico della cache
    private fun fetchNewRawRecordsFromCache(uId: String): Pair<List<AwsRawRecord>, Long> {
        val rawList = mutableListOf<AwsRawRecord>()

        // Scarica e pulisce atomicamente la CopyOnWriteArrayList interna
        val accelCache = IntercettaFinestrePredizione.getInstance().flushCache()

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        var highestTimestamp = 0L

        for (point in accelCache) {
            val ts = point.timestamp
            if (ts > highestTimestamp) {
                highestTimestamp = ts
            }

            rawList.add(
                AwsRawRecord(
                    userId = uId,
                    timestamp = isoFormat.format(Date(ts)),
                    x = point.avgX,
                    y = point.avgY,
                    z = point.avgZ
                )
            )
        }

        return Pair(rawList, highestTimestamp)
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

        // 1. Calcolo della finestra di validità dell'ossigeno basata sul ciclo hardware reale configurato
        val sharedPrefRing = getSharedPreferences("RingPrefs", Context.MODE_PRIVATE)
        val currentBpmWindowMs = sharedPrefRing.getLong("auto_bpm_window_ms", 200 * 1000L)
        val totalCycleTimeMs = currentBpmWindowMs + SPO2_WINDOW + (GAP_WINDOW * 2)
        val dynamicOxygenValidityWindow = (totalCycleTimeMs * 1.5).toLong()

        // Recovering fresh timestamps from local tables cursors
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

        val cursorSteps = database.rawQuery("SELECT timestamp FROM steps WHERE timestamp > ?", arrayOf(lastTimestamp.toString()))
        while (cursorSteps.moveToNext()) { uniqueTimestamps.add(cursorSteps.getLong(0)) }
        cursorSteps.close()

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        var highestTimestampInBatch = lastTimestamp

        // 2. Standard Database Historical Processing Loop (keeps historical records clean with 'no_alert')
        if (uniqueTimestamps.isNotEmpty()) {
            val sortedTimestamps = uniqueTimestamps.sorted()
            highestTimestampInBatch = sortedTimestamps.last()

            for (ts in sortedTimestamps) {
                var lastKnownBpm = 0
                var lastKnownSpo2 = 0
                var lastKnownPressure = "0/0"
                var lastKnownLatitude = 0.0
                var lastKnownLongitude = 0.0
                var lastKnownActivity = "UNKNOWN"
                var lastKnownSteps = 0

                // Estrazione parametri vitali condizionata dalle finestre di tolleranza stringenti
                val cBpm = database.rawQuery("SELECT bpm, timestamp FROM bpm WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1", arrayOf(ts.toString()))
                if (cBpm.moveToFirst()) {
                    val actualBpmTs = cBpm.getLong(1)
                    if (ts - actualBpmTs <= VITAL_VALIDITY_WINDOW_BPM) {
                        lastKnownBpm = cBpm.getInt(0)
                    }
                }
                cBpm.close()

                val cO2 = database.rawQuery("SELECT value, timestamp FROM o2 WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1", arrayOf(ts.toString()))
                if (cO2.moveToFirst()) {
                    val actualO2Ts = cO2.getLong(1)
                    if (ts - actualO2Ts <= dynamicOxygenValidityWindow) {
                        lastKnownSpo2 = cO2.getInt(0)
                    }
                }
                cO2.close()

                val cPress = database.rawQuery("SELECT systolic, diastolic, timestamp FROM pressure WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1", arrayOf(ts.toString()))
                if (cPress.moveToFirst()) {
                    val actualPressTs = cPress.getLong(2)
                    if (ts - actualPressTs <= VITAL_VALIDITY_WINDOW_PRESSURE) {
                        lastKnownPressure = "${cPress.getInt(0)}/${cPress.getInt(1)}"
                    }
                }
                cPress.close()

                // Parametri di tracciamento continuo (mantengono l'ultimo noto assoluto del record)
                val cAct = database.rawQuery("SELECT activity FROM prediction WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1", arrayOf(ts.toString()))
                if (cAct.moveToFirst()) { lastKnownActivity = cAct.getString(0) }
                cAct.close()

                val cPos = database.rawQuery("SELECT latitude, longitude FROM position WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1", arrayOf(ts.toString()))
                if (cPos.moveToFirst()) {
                    lastKnownLatitude = cPos.getDouble(0)
                    lastKnownLongitude = cPos.getDouble(1)
                }
                cPos.close()

                val cSt = database.rawQuery("SELECT value FROM steps WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1", arrayOf(ts.toString()))
                if (cSt.moveToFirst()) { lastKnownSteps = cSt.getInt(0) }
                cSt.close()

                val record = AwsRecord(
                    userId = uId,
                    activity = lastKnownActivity,
                    heartRate = lastKnownBpm,
                    spo2 = lastKnownSpo2,
                    bloodPressure = lastKnownPressure,
                    latitude = lastKnownLatitude,
                    longitude = lastKnownLongitude,
                    steps = lastKnownSteps,
                    alert = "no_alert",
                    timestamp = isoFormat.format(Date(ts))
                )
                list.add(record)
            }
        }

        // 3. UNIVERSAL DEDICATED EVENT TRIGGER BLOCK (Solo tabelle aggregate normalizzate)
        if (forcedAlert != null) {
            val currentNowMs = System.currentTimeMillis()
            Log.w(TAG, "Forced pipeline event detected ($forcedAlert). Appending a separate dedicated record at exact current timestamp.")

            var lastKnownBpm = 0
            var lastKnownSpo2 = 0
            var lastKnownPressure = "0/0"
            var lastKnownLatitude = 0.0
            var lastKnownLongitude = 0.0
            var lastKnownActivity = "UNKNOWN"
            var lastKnownSteps = 0

            val cBpm = database.rawQuery("SELECT bpm, timestamp FROM bpm ORDER BY timestamp DESC LIMIT 1", null)
            if (cBpm.moveToFirst()) {
                val actualBpmTs = cBpm.getLong(1)
                if (currentNowMs - actualBpmTs <= VITAL_VALIDITY_WINDOW_BPM) {
                    lastKnownBpm = cBpm.getInt(0)
                }
            }
            cBpm.close()

            val cO2 = database.rawQuery("SELECT value, timestamp FROM o2 ORDER BY timestamp DESC LIMIT 1", null)
            if (cO2.moveToFirst()) {
                val actualO2Ts = cO2.getLong(1)
                if (currentNowMs - actualO2Ts <= dynamicOxygenValidityWindow) {
                    lastKnownSpo2 = cO2.getInt(0)
                }
            }
            cO2.close()

            val cPress = database.rawQuery("SELECT systolic, diastolic, timestamp FROM pressure ORDER BY timestamp DESC LIMIT 1", null)
            if (cPress.moveToFirst()) {
                val actualPressTs = cPress.getLong(2)
                if (currentNowMs - actualPressTs <= VITAL_VALIDITY_WINDOW_PRESSURE) {
                    lastKnownPressure = "${cPress.getInt(0)}/${cPress.getInt(1)}"
                }
            }
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

            val cSt = database.rawQuery("SELECT value FROM steps ORDER BY timestamp DESC LIMIT 1", null)
            if (cSt.moveToFirst()) { lastKnownSteps = cSt.getInt(0) }
            cSt.close()

            val finalAlertString = if (!customMessage.isNullOrBlank()) {
                "$forcedAlert | Note: $customMessage"
            } else {
                forcedAlert
            }

            val dedicatedEventRecord = AwsRecord(
                userId = uId,
                activity = lastKnownActivity,
                heartRate = lastKnownBpm,
                spo2 = lastKnownSpo2,
                bloodPressure = lastKnownPressure,
                latitude = lastKnownLatitude,
                longitude = lastKnownLongitude,
                steps = lastKnownSteps,
                alert = finalAlertString,
                timestamp = isoFormat.format(Date(currentNowMs))
            )
            list.add(dedicatedEventRecord)
            Log.w(TAG, "Dedicated event record successfully generated: ${dedicatedEventRecord.timestamp} -> Steps: $lastKnownSteps | Content: $finalAlertString")
        }

        database.close()
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
        serviceScope.cancel()
        Log.d(TAG, "AwsSyncService Destroyed.")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }
}