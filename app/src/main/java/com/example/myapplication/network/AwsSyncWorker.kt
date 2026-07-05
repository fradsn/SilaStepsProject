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

        // 2. Inizializza SQLiteHelper passando il contesto e il firebaseUserId
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

                // 4. MOSTRA IL TOAST SULL'UI THREAD IN CASO DI SUCCESSO (Tradotto in inglese)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        applicationContext,
                        "Data successfully synced with DynamoDB! (${recordsToSend.size} records)",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                Result.success()
            } else {
                Log.e(TAG, "Errore API Gateway AWS: ${response.code()} - ${response.errorBody()?.string()}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore di rete durante la sincronizzazione", e)
            Result.retry()
        }
    }

    private fun fetchAndPrepareRecords(dbHelper: SQLiteHelper, uId: String): List<AwsRecord> {
        val list = mutableListOf<AwsRecord>()

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val database = dbHelper.readableDatabase
        val cursor = database.rawQuery("SELECT * FROM bpm ORDER BY timestamp DESC LIMIT 10", null)

        if (cursor.moveToFirst()) {
            do {
                val timestampMillis = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"))
                val bpm = cursor.getInt(cursor.getColumnIndexOrThrow("bpm"))

                val record = AwsRecord(
                    userId = uId,
                    x = 0.05,
                    y = 0.03,
                    z = 9.65,
                    activity = "sitting",
                    heartRate = bpm,
                    spo2 = 98,
                    bloodPressure = "120/80",
                    timestamp = isoFormat.format(Date(timestampMillis))
                )
                list.add(record)
            } while (cursor.moveToNext())
        }
        cursor.close()

        return list
    }
}