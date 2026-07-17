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
import com.example.myapplication.network.AwsRawRecord
import com.example.myapplication.network.AwsRawSyncPayload
import com.example.myapplication.network.AwsRecord
import com.example.myapplication.network.AwsSyncPayload
import com.example.myapplication.network.IntercettaFinestrePredizione
import com.example.myapplication.steps.StepDatabaseUpdater
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AwsSyncService : Service() {

    private val serviceScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var syncJob: Job? = null

    private lateinit var stepDatabaseUpdater: StepDatabaseUpdater

    private var stepRecordingInitialized = false

    companion object {
        private const val TAG = "AwsSyncService"

        private const val BASE_URL =
            "https://4m4fvs5pn0.execute-api.eu-north-1.amazonaws.com/"

        private const val API_KEY =
            "rwT6tGGmplSObjaoVdhb4XN0N0rE1x68k6rLFid9"

        private const val PREFS_NAME =
            "AwsSyncPrefs"

        private const val KEY_LAST_SYNC_TIMESTAMP =
            "last_sync_timestamp"

        private const val KEY_LAST_RAW_SYNC_TIMESTAMP =
            "last_raw_sync_timestamp"

        private const val NOTIFICATION_ID = 2005

        private const val CHANNEL_ID =
            "aws_sync_channel"

        private const val SYNC_INTERVAL_MS =
            5 * 60 * 1000L

        private const val VITAL_VALIDITY_WINDOW_BPM =
            60 * 1000L

        private const val VITAL_VALIDITY_WINDOW_PRESSURE =
            5 * 60 * 1000L

        private const val SPO2_WINDOW =
            30 * 1000L

        private const val GAP_WINDOW =
            5 * 1000L

        const val ACTION_TRIGGER_IMMEDIATE_SYNC =
            "com.example.myapplication.TRIGGER_IMMEDIATE_SYNC"

        const val EXTRA_ALERT_TYPE =
            "EXTRA_ALERT_TYPE"

        const val EXTRA_USER_MESSAGE =
            "EXTRA_USER_MESSAGE"
    }

    override fun onCreate() {
        super.onCreate()

        stepDatabaseUpdater =
            StepDatabaseUpdater(applicationContext)

        Log.d(
            TAG,
            "AwsSyncService Created. Starting Foreground Configuration..."
        )

        startForeground(
            NOTIFICATION_ID,
            createNotification()
        )

        startSyncLoop()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent?.action == ACTION_TRIGGER_IMMEDIATE_SYNC) {
            val alertType =
                intent.getStringExtra(EXTRA_ALERT_TYPE)
                    ?: "unknown_anomaly"

            val userMessage =
                intent.getStringExtra(EXTRA_USER_MESSAGE)
                    ?: ""

            Log.w(
                TAG,
                "IMMEDIATE SYNC REQUEST RECEIVED: '$alertType' | Note: '$userMessage'"
            )

            serviceScope.launch {
                try {
                    refreshStepDatabase()

                    performSynchronization(
                        forcedAlert = alertType,
                        customMessage = userMessage
                    )
                } catch (exception: Exception) {
                    Log.e(
                        TAG,
                        "Immediate forced synchronization failed",
                        exception
                    )
                }
            }
        }

        return START_STICKY
    }

    private suspend fun refreshStepDatabase() {
        val currentUser =
            FirebaseAuth.getInstance().currentUser

        if (currentUser == null) {
            Log.w(
                TAG,
                "Aggiornamento passi rimandato: utente non autenticato"
            )
            return
        }

        try {
            if (!stepRecordingInitialized) {
                stepDatabaseUpdater.initialize()
                stepRecordingInitialized = true

                Log.d(
                    TAG,
                    "Registrazione contapassi inizializzata"
                )
            }

            stepDatabaseUpdater.updateToday()

            Log.d(
                TAG,
                "Database passi aggiornato"
            )
        } catch (exception: SecurityException) {
            Log.e(
                TAG,
                "Permesso ACTIVITY_RECOGNITION mancante",
                exception
            )
        } catch (exception: Exception) {
            Log.e(
                TAG,
                "Errore durante l'aggiornamento dei passi",
                exception
            )
        }
    }

    private fun startSyncLoop() {
        syncJob?.cancel()

        syncJob = serviceScope.launch {
            while (isActive) {
                try {
                    refreshStepDatabase()

                    performSynchronization(
                        forcedAlert = null,
                        customMessage = null
                    )
                } catch (exception: Exception) {
                    Log.e(
                        TAG,
                        "Exception during active sync loop iteration",
                        exception
                    )
                }

                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    private suspend fun performSynchronization(
        forcedAlert: String?,
        customMessage: String? = null
    ) {
        Log.d(
            TAG,
            "Sync operation executing. ForcedAlert state: $forcedAlert | Custom Message: $customMessage"
        )

        val currentUser =
            FirebaseAuth.getInstance().currentUser

        if (currentUser == null) {
            Log.w(
                TAG,
                "User not logged into Firebase session. Synchronization postponed."
            )
            return
        }

        val firebaseUserId =
            currentUser.uid

        val sharedPrefs =
            getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

        val lastSyncTimestamp =
            sharedPrefs.getLong(
                KEY_LAST_SYNC_TIMESTAMP,
                0L
            )

        val dbHelper =
            SQLiteHelper(
                applicationContext,
                firebaseUserId
            )

        val retrofit =
            Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(
                    GsonConverterFactory.create()
                )
                .build()

        val apiService =
            retrofit.create(
                AwsApiService::class.java
            )

        val (
            recordsToSend,
            maxTimestampInBatch
        ) = fetchNewRecordsStrict(
            dbHelper = dbHelper,
            uId = firebaseUserId,
            lastTimestamp = lastSyncTimestamp,
            forcedAlert = forcedAlert,
            customMessage = customMessage
        )

        if (recordsToSend.isNotEmpty()) {
            val uniqueRecordsToSend =
                recordsToSend.distinctBy {
                    it.timestamp
                }

            Log.d(
                TAG,
                "Filtered batch metrics: Original count=${recordsToSend.size} | Unique items count=${uniqueRecordsToSend.size}"
            )

            try {
                val payload =
                    AwsSyncPayload(
                        records = uniqueRecordsToSend
                    )

                val response =
                    apiService.uploadRecords(
                        apiKey = API_KEY,
                        payload = payload
                    )

                if (response.isSuccessful) {
                    Log.d(
                        TAG,
                        "Cloud sync transmission successful! ${uniqueRecordsToSend.size} records uploaded."
                    )

                    sharedPrefs
                        .edit()
                        .putLong(
                            KEY_LAST_SYNC_TIMESTAMP,
                            maxTimestampInBatch
                        )
                        .apply()
                } else {
                    Log.e(
                        TAG,
                        "AWS Remote Endpoint rejected request: ${response.code()} - ${response.errorBody()?.string()}"
                    )
                }
            } catch (exception: Exception) {
                Log.e(
                    TAG,
                    "Network transport failure during payload upload",
                    exception
                )
            }
        } else {
            Log.d(
                TAG,
                "No new database entries discovered since the last synchronization cycle."
            )
        }

        if (forcedAlert == null) {
            val (
                rawRecordsToSend,
                maxRawTimestamp
            ) = fetchNewRawRecordsFromCache(
                uId = firebaseUserId
            )

            if (rawRecordsToSend.isNotEmpty()) {
                try {
                    val rawPayload =
                        AwsRawSyncPayload(
                            records = rawRecordsToSend
                        )

                    val response =
                        apiService.uploadRawRecords(
                            apiKey = API_KEY,
                            payload = rawPayload
                        )

                    if (response.isSuccessful) {
                        Log.d(
                            TAG,
                            "Cloud sync dati RAW completato! ${rawRecordsToSend.size} record inviati."
                        )

                        sharedPrefs
                            .edit()
                            .putLong(
                                KEY_LAST_RAW_SYNC_TIMESTAMP,
                                maxRawTimestamp
                            )
                            .apply()
                    } else {
                        Log.e(
                            TAG,
                            "Endpoint RAW ha rifiutato la richiesta: ${response.code()}"
                        )
                    }
                } catch (exception: Exception) {
                    Log.e(
                        TAG,
                        "Errore durante il trasporto dei dati RAW",
                        exception
                    )
                }
            } else {
                Log.d(
                    TAG,
                    "Nessun dato RAW intercettato negli ultimi 5 minuti."
                )
            }
        }

        withContext(Dispatchers.Main) {
            val message =
                if (forcedAlert != null) {
                    "Immediate Cloud Sync Triggered!"
                } else {
                    "Cloud Sync Successful!"
                }

            Toast.makeText(
                applicationContext,
                message,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun fetchNewRawRecordsFromCache(
        uId: String
    ): Pair<List<AwsRawRecord>, Long> {
        val rawList =
            mutableListOf<AwsRawRecord>()

        val accelCache =
            IntercettaFinestrePredizione
                .getInstance()
                .flushCache()

        val isoFormat =
            SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                Locale.getDefault()
            ).apply {
                timeZone =
                    TimeZone.getTimeZone("UTC")
            }

        var highestTimestamp = 0L

        for (point in accelCache) {
            val timestamp =
                point.timestamp

            if (timestamp > highestTimestamp) {
                highestTimestamp = timestamp
            }

            rawList.add(
                AwsRawRecord(
                    userId = uId,
                    timestamp =
                        isoFormat.format(
                            Date(timestamp)
                        ),
                    x = point.avgX,
                    y = point.avgY,
                    z = point.avgZ
                )
            )
        }

        return Pair(
            rawList,
            highestTimestamp
        )
    }

    private fun fetchNewRecordsStrict(
        dbHelper: SQLiteHelper,
        uId: String,
        lastTimestamp: Long,
        forcedAlert: String?,
        customMessage: String?
    ): Pair<List<AwsRecord>, Long> {
        val list =
            mutableListOf<AwsRecord>()

        val database =
            dbHelper.readableDatabase

        val uniqueTimestamps =
            mutableSetOf<Long>()

        val sharedPrefRing =
            getSharedPreferences(
                "RingPrefs",
                Context.MODE_PRIVATE
            )

        val currentBpmWindowMs =
            sharedPrefRing.getLong(
                "auto_bpm_window_ms",
                200 * 1000L
            )

        val totalCycleTimeMs =
            currentBpmWindowMs +
                    SPO2_WINDOW +
                    (GAP_WINDOW * 2)

        val dynamicOxygenValidityWindow =
            (totalCycleTimeMs * 1.5)
                .toLong()

        val cursorBpm =
            database.rawQuery(
                "SELECT timestamp FROM bpm WHERE timestamp > ?",
                arrayOf(lastTimestamp.toString())
            )

        while (cursorBpm.moveToNext()) {
            uniqueTimestamps.add(
                cursorBpm.getLong(0)
            )
        }

        cursorBpm.close()

        val cursorO2 =
            database.rawQuery(
                "SELECT timestamp FROM o2 WHERE timestamp > ?",
                arrayOf(lastTimestamp.toString())
            )

        while (cursorO2.moveToNext()) {
            uniqueTimestamps.add(
                cursorO2.getLong(0)
            )
        }

        cursorO2.close()

        val cursorPressure =
            database.rawQuery(
                "SELECT timestamp FROM pressure WHERE timestamp > ?",
                arrayOf(lastTimestamp.toString())
            )

        while (cursorPressure.moveToNext()) {
            uniqueTimestamps.add(
                cursorPressure.getLong(0)
            )
        }

        cursorPressure.close()

        val cursorPrediction =
            database.rawQuery(
                "SELECT timestamp FROM prediction WHERE timestamp > ?",
                arrayOf(lastTimestamp.toString())
            )

        while (cursorPrediction.moveToNext()) {
            uniqueTimestamps.add(
                cursorPrediction.getLong(0)
            )
        }

        cursorPrediction.close()

        val cursorPosition =
            database.rawQuery(
                "SELECT timestamp FROM position WHERE timestamp > ?",
                arrayOf(lastTimestamp.toString())
            )

        while (cursorPosition.moveToNext()) {
            uniqueTimestamps.add(
                cursorPosition.getLong(0)
            )
        }

        cursorPosition.close()

        val cursorSteps =
            database.rawQuery(
                "SELECT timestamp FROM steps WHERE timestamp > ?",
                arrayOf(lastTimestamp.toString())
            )

        while (cursorSteps.moveToNext()) {
            uniqueTimestamps.add(
                cursorSteps.getLong(0)
            )
        }

        cursorSteps.close()

        val isoFormat =
            SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                Locale.getDefault()
            ).apply {
                timeZone =
                    TimeZone.getTimeZone("UTC")
            }

        var highestTimestampInBatch =
            lastTimestamp

        if (uniqueTimestamps.isNotEmpty()) {
            val sortedTimestamps =
                uniqueTimestamps.sorted()

            highestTimestampInBatch =
                sortedTimestamps.last()

            for (timestamp in sortedTimestamps) {
                var lastKnownBpm = 0
                var lastKnownSpo2 = 0
                var lastKnownPressure = "0/0"
                var lastKnownLatitude = 0.0
                var lastKnownLongitude = 0.0
                var lastKnownActivity = "UNKNOWN"
                var lastKnownSteps = 0

                val cursorLastBpm =
                    database.rawQuery(
                        "SELECT bpm, timestamp FROM bpm WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1",
                        arrayOf(timestamp.toString())
                    )

                if (cursorLastBpm.moveToFirst()) {
                    val actualBpmTimestamp =
                        cursorLastBpm.getLong(1)

                    if (
                        timestamp -
                        actualBpmTimestamp <=
                        VITAL_VALIDITY_WINDOW_BPM
                    ) {
                        lastKnownBpm =
                            cursorLastBpm.getInt(0)
                    }
                }

                cursorLastBpm.close()

                val cursorLastO2 =
                    database.rawQuery(
                        "SELECT value, timestamp FROM o2 WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1",
                        arrayOf(timestamp.toString())
                    )

                if (cursorLastO2.moveToFirst()) {
                    val actualO2Timestamp =
                        cursorLastO2.getLong(1)

                    if (
                        timestamp -
                        actualO2Timestamp <=
                        dynamicOxygenValidityWindow
                    ) {
                        lastKnownSpo2 =
                            cursorLastO2.getInt(0)
                    }
                }

                cursorLastO2.close()

                val cursorLastPressure =
                    database.rawQuery(
                        "SELECT systolic, diastolic, timestamp FROM pressure WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1",
                        arrayOf(timestamp.toString())
                    )

                if (cursorLastPressure.moveToFirst()) {
                    val actualPressureTimestamp =
                        cursorLastPressure.getLong(2)

                    if (
                        timestamp -
                        actualPressureTimestamp <=
                        VITAL_VALIDITY_WINDOW_PRESSURE
                    ) {
                        lastKnownPressure =
                            "${cursorLastPressure.getInt(0)}/${cursorLastPressure.getInt(1)}"
                    }
                }

                cursorLastPressure.close()

                val cursorLastActivity =
                    database.rawQuery(
                        "SELECT activity FROM prediction WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1",
                        arrayOf(timestamp.toString())
                    )

                if (cursorLastActivity.moveToFirst()) {
                    lastKnownActivity =
                        cursorLastActivity.getString(0)
                }

                cursorLastActivity.close()

                val cursorLastPosition =
                    database.rawQuery(
                        "SELECT latitude, longitude FROM position WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1",
                        arrayOf(timestamp.toString())
                    )

                if (cursorLastPosition.moveToFirst()) {
                    lastKnownLatitude =
                        cursorLastPosition.getDouble(0)

                    lastKnownLongitude =
                        cursorLastPosition.getDouble(1)
                }

                cursorLastPosition.close()

                val cursorLastSteps =
                    database.rawQuery(
                        "SELECT value FROM steps WHERE timestamp <= ? ORDER BY timestamp DESC LIMIT 1",
                        arrayOf(timestamp.toString())
                    )

                if (cursorLastSteps.moveToFirst()) {
                    lastKnownSteps =
                        cursorLastSteps.getInt(0)
                }

                cursorLastSteps.close()

                list.add(
                    AwsRecord(
                        userId = uId,
                        activity = lastKnownActivity,
                        heartRate = lastKnownBpm,
                        spo2 = lastKnownSpo2,
                        bloodPressure =
                            lastKnownPressure,
                        latitude =
                            lastKnownLatitude,
                        longitude =
                            lastKnownLongitude,
                        steps = lastKnownSteps,
                        alert = "no_alert",
                        timestamp =
                            isoFormat.format(
                                Date(timestamp)
                            )
                    )
                )
            }
        }

        if (forcedAlert != null) {
            val currentTimestamp =
                System.currentTimeMillis()

            var lastKnownBpm = 0
            var lastKnownSpo2 = 0
            var lastKnownPressure = "0/0"
            var lastKnownLatitude = 0.0
            var lastKnownLongitude = 0.0
            var lastKnownActivity = "UNKNOWN"
            var lastKnownSteps = 0

            val cursorBpm =
                database.rawQuery(
                    "SELECT bpm, timestamp FROM bpm ORDER BY timestamp DESC LIMIT 1",
                    null
                )

            if (cursorBpm.moveToFirst()) {
                val actualTimestamp =
                    cursorBpm.getLong(1)

                if (
                    currentTimestamp -
                    actualTimestamp <=
                    VITAL_VALIDITY_WINDOW_BPM
                ) {
                    lastKnownBpm =
                        cursorBpm.getInt(0)
                }
            }

            cursorBpm.close()

            val cursorO2 =
                database.rawQuery(
                    "SELECT value, timestamp FROM o2 ORDER BY timestamp DESC LIMIT 1",
                    null
                )

            if (cursorO2.moveToFirst()) {
                val actualTimestamp =
                    cursorO2.getLong(1)

                if (
                    currentTimestamp -
                    actualTimestamp <=
                    dynamicOxygenValidityWindow
                ) {
                    lastKnownSpo2 =
                        cursorO2.getInt(0)
                }
            }

            cursorO2.close()

            val cursorPressure =
                database.rawQuery(
                    "SELECT systolic, diastolic, timestamp FROM pressure ORDER BY timestamp DESC LIMIT 1",
                    null
                )

            if (cursorPressure.moveToFirst()) {
                val actualTimestamp =
                    cursorPressure.getLong(2)

                if (
                    currentTimestamp -
                    actualTimestamp <=
                    VITAL_VALIDITY_WINDOW_PRESSURE
                ) {
                    lastKnownPressure =
                        "${cursorPressure.getInt(0)}/${cursorPressure.getInt(1)}"
                }
            }

            cursorPressure.close()

            val cursorActivity =
                database.rawQuery(
                    "SELECT activity FROM prediction ORDER BY timestamp DESC LIMIT 1",
                    null
                )

            if (cursorActivity.moveToFirst()) {
                lastKnownActivity =
                    cursorActivity.getString(0)
            }

            cursorActivity.close()

            val cursorPosition =
                database.rawQuery(
                    "SELECT latitude, longitude FROM position ORDER BY timestamp DESC LIMIT 1",
                    null
                )

            if (cursorPosition.moveToFirst()) {
                lastKnownLatitude =
                    cursorPosition.getDouble(0)

                lastKnownLongitude =
                    cursorPosition.getDouble(1)
            }

            cursorPosition.close()

            val cursorSteps =
                database.rawQuery(
                    "SELECT value FROM steps ORDER BY timestamp DESC LIMIT 1",
                    null
                )

            if (cursorSteps.moveToFirst()) {
                lastKnownSteps =
                    cursorSteps.getInt(0)
            }

            cursorSteps.close()

            val finalAlertString =
                if (!customMessage.isNullOrBlank()) {
                    "$forcedAlert | Note: $customMessage"
                } else {
                    forcedAlert
                }

            list.add(
                AwsRecord(
                    userId = uId,
                    activity = lastKnownActivity,
                    heartRate = lastKnownBpm,
                    spo2 = lastKnownSpo2,
                    bloodPressure =
                        lastKnownPressure,
                    latitude =
                        lastKnownLatitude,
                    longitude =
                        lastKnownLongitude,
                    steps = lastKnownSteps,
                    alert =
                        finalAlertString,
                    timestamp =
                        isoFormat.format(
                            Date(currentTimestamp)
                        )
                )
            )
        }

        database.close()

        return Pair(
            list,
            highestTimestampInBatch
        )
    }

    private fun createNotification(): Notification {
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "AWS Database Cloud Sync",
                    NotificationManager.IMPORTANCE_LOW
                )

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager?.createNotificationChannel(
                channel
            )
        }

        return NotificationCompat
            .Builder(
                this,
                CHANNEL_ID
            )
            .setContentTitle(
                "Cloud Synchronizer Active"
            )
            .setContentText(
                "Pipelining IoT health records to DynamoDB..."
            )
            .setSmallIcon(
                R.mipmap.ic_launcher
            )
            .setOngoing(true)
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .build()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()

        Log.d(
            TAG,
            "AwsSyncService Destroyed."
        )

        super.onDestroy()
    }

    override fun onTaskRemoved(
        rootIntent: Intent?
    ) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }
}