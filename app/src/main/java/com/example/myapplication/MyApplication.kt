package com.example.myapplication

import android.app.Application
import android.icu.util.Calendar
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.myapplication.workers.AwsSyncWorker
import com.example.myapplication.workers.DailyCleanupWorker
import java.util.concurrent.TimeUnit

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        setupAwsSyncWorker()
        scheduleDailyCleanup()
    }

    private fun setupAwsSyncWorker() {
        val workManager = WorkManager.getInstance(this)

        // Vincoli: esegui solo se il telefono ha una connessione internet attiva
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // -------------------------------------------------------------
        // INVIO PERIODICO OGNI 15 MINUTI (Periodic Work Request)
        // -------------------------------------------------------------
        val periodicSyncRequest = PeriodicWorkRequestBuilder<AwsSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        // Registra il lavoro in modo univoco (così non si duplica a ogni avvio dell'app)
        workManager.enqueueUniquePeriodicWork(
            "AwsDatabaseSync",
            ExistingPeriodicWorkPolicy.KEEP, // Mantiene il vecchio se esiste già, evitando di resettare il timer all'avvio
            periodicSyncRequest
        )
    }

    private fun getInitialDelay(targetHour: Int, targetMinute: Int): Long {
        val now = Calendar.getInstance()

        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Se l'orario è già passato oggi, programma per domani
        if (target.before(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        return target.timeInMillis - now.timeInMillis
    }

    private fun scheduleDailyCleanup() {
        val initialDelay = getInitialDelay(0, 5) // 00:05

        val workRequest = PeriodicWorkRequestBuilder<DailyCleanupWorker>(
            1, TimeUnit.DAYS
        )
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}