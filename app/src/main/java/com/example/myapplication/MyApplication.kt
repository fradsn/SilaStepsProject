package com.example.myapplication

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.icu.util.Calendar
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.myapplication.services.AwsSyncService
import java.util.concurrent.TimeUnit

class MyApplication : Application(), Application.ActivityLifecycleCallbacks {

    companion object {
        private var runningActivities = 0

        // Metodo statico richiamato dal Service per capire se l'app è aperta o in background
        fun isAppInForeground(): Boolean {
            return runningActivities > 0
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("MY_APP", "Application instance initialized.")

        // Registra i callback globali per il monitoraggio dello stato della UI
        registerActivityLifecycleCallbacks(this)

        // Avvia il servizio persistente di sincronizzazione continua su AWS DynamoDB
        startAwsSyncService()
    }

    // =====================================================================================
    // GESTIONE TRACCIAMENTO FOREGROUND / BACKGROUND PIPELINE
    // =====================================================================================
    override fun onActivityStarted(activity: Activity) {
        runningActivities++
    }

    override fun onActivityStopped(activity: Activity) {
        runningActivities--
    }

    // Metodi obbligatori dell'interfaccia inseriti senza rompere le logiche
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    // =====================================================================================
    // AVVIO FOREGROUND SERVICE PER PIPELINE DI SINCRONIZZAZIONE AWS DYNAMODB
    // =====================================================================================
    private fun startAwsSyncService() {
        Log.d("MY_APP", "Launching persistent AwsSyncService...")
        val serviceIntent = Intent(this, AwsSyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

}