package com.example.myapplication.workers

import android.content.Context
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
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Inizio sincronizzazione dati verso AWS...")

        // 1. Recupera l'utente corrente da Firebase Auth
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.w(TAG, "Utente non loggato su Firebase. Sincronizzazione rimandata.")
            return Result.retry()
        }
        val firebaseUserId = currentUser.uid

        // 2. Inizializza SQLiteHelper passando il contesto e il firebaseUserId per aprire il DB specifico
        val dbHelper = SQLiteHelper(applicationContext, firebaseUserId)
        val recordsToSend = fetchAndPrepareRecords(dbHelper, firebaseUserId)

        if (recordsToSend.isEmpty()) {
            Log.d(TAG, "Nessun nuovo dato locale da sincronizzare.")
            return Result.success()
        }

        // 3. Inizializza Retrofit ed esegui la chiamata
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val apiService = retrofit.create(AwsApiService::class.java)
        val payload = AwsSyncPayload(records = recordsToSend)

        return try {
            val response = apiService.uploadRecords(apiKey = API_KEY, payload = payload)
            if (response.isSuccessful) {
                Log.d(TAG, "Sincronizzazione completata con successo! ${recordsToSend.size} record inviati.")

                // 4. Mostra il Toast di successo in lingua inglese sull'UI Thread
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        applicationContext,
                        "Data successfully synced with DynamoDB! (${recordsToSend.size} records)",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                Result.success()
            } else {
                Log.e(TAG, "Error API Gateway AWS: ${response.code()} - ${response.errorBody()?.string()}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error during synchronization", e)
            Result.retry()
        }
    }

    private fun fetchAndPrepareRecords(dbHelper: SQLiteHelper, uId: String): List<AwsRecord> {
        val list = mutableListOf<AwsRecord>()

        // Formattatore ISO 8601 UTC richiesto da AWS DynamoDB
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val database = dbHelper.readableDatabase

        // Calcoliamo il timestamp di 15 minuti fa in millisecondi
        val currentTimeMillis = System.currentTimeMillis()
        val fifteenMinutesAgoMillis = currentTimeMillis - (15 * 60 * 1000)

        // 1. Estraiamo TUTTI i BPM registrati negli ultimi 15 minuti (rimosso il LIMIT 10)
        val bpmCursor = database.rawQuery(
            "SELECT * FROM bpm WHERE timestamp >= ? AND timestamp <= ? ORDER BY timestamp ASC",
            arrayOf(fifteenMinutesAgoMillis.toString(), currentTimeMillis.toString())
        )

        if (bpmCursor.moveToFirst()) {
            do {
                val timestampMillis = bpmCursor.getLong(bpmCursor.getColumnIndexOrThrow("timestamp"))
                val bpm = bpmCursor.getInt(bpmCursor.getColumnIndexOrThrow("bpm"))

                // 2. Cerchiamo la misurazione di SpO2 (Tabella o2) più vicina a questo timestamp
                var realSpo2 = 98 // Fallback
                val o2Cursor = database.rawQuery(
                    "SELECT value FROM o2 ORDER BY ABS(timestamp - ?) ASC LIMIT 1",
                    arrayOf(timestampMillis.toString())
                )
                if (o2Cursor.moveToFirst()) {
                    realSpo2 = o2Cursor.getInt(o2Cursor.getColumnIndexOrThrow("value"))
                }
                o2Cursor.close()

                // 3. Cerchiamo la misurazione di Pressione (Tabella pressure) più vicina a questo timestamp
                var realPressure = "120/80" // Fallback
                val pressureCursor = database.rawQuery(
                    "SELECT systolic, diastolic FROM pressure ORDER BY ABS(timestamp - ?) ASC LIMIT 1",
                    arrayOf(timestampMillis.toString())
                )
                if (pressureCursor.moveToFirst()) {
                    val sys = pressureCursor.getInt(pressureCursor.getColumnIndexOrThrow("systolic"))
                    val dia = pressureCursor.getInt(pressureCursor.getColumnIndexOrThrow("diastolic"))
                    realPressure = "$sys/$dia"
                }
                pressureCursor.close()

                // 4. Creazione del record combinando i dati reali dell'anello con quelli mockup dello Shimmer
                val record = AwsRecord(
                    userId = uId,
                    x = 0.05,
                    y = 0.03,
                    z = 9.65,
                    activity = "sitting",
                    heartRate = bpm,
                    spo2 = realSpo2,
                    bloodPressure = realPressure,
                    timestamp = isoFormat.format(Date(timestampMillis))
                )
                list.add(record)
            } while (bpmCursor.moveToNext())
        }
        bpmCursor.close()

        return list
    }
}