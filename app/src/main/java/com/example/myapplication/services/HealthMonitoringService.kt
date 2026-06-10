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
import androidx.core.app.NotificationCompat
import com.example.myapplication.BT.ring.Decoder
import com.example.myapplication.BT.ring.SmartRingManager
import com.example.myapplication.R

class HealthMonitoringService : Service(), SmartRingManager.SmartRingListener {

    private lateinit var gestoreStatistiche: GestoreStatistiche
    private var ringManager: SmartRingManager? = null

    companion object {
        private const val TAG = "HEALTH_SERVICE"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "health_monitoring_channel"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Servizio di Monitoraggio Creato")
        gestoreStatistiche = GestoreStatistiche.getInstance(this)

        // Collega inizialmente il servizio all'istanza attiva se già presente
        ringManager = SmartRingManager.getActiveInstance()
        ringManager?.updateListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Servizio attivo come listener fisso dell'hardware")

        // FIX CONNESSIONE CENTRALE: Intercettiamo se viene passato un MAC address dal ProfileFragment
        val macAddress = intent?.getStringExtra("MAC_ADDRESS")
        if (!macAddress.isNullOrBlank()) {
            Log.d(TAG, "Ricevuto MAC_ADDRESS da connettere: $macAddress. Inizializzo istanza.")
            // Crea/Recupera l'istanza legandola indissolubilmente al Service come unico listener primario
            ringManager = SmartRingManager.getInstance(this, macAddress, this)
            ringManager?.connect(this)
        } else {
            // Se invocato senza MAC (es: passaggi di testimone onPause delle Activity), riaggancia l'ascolto dell'istanza attiva
            ringManager = SmartRingManager.getActiveInstance()
            ringManager?.updateListener(this)
        }

        // Avvia il servizio in modalità Foreground (obbligatorio su Android moderno)
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    // =====================================================================================
    // RICEZIONE E SALVATAGGIO DEI PARAMETRI VITALI IN BACKGROUND
    // =====================================================================================
    override fun onDataReceived(result: Decoder.DecodedResult) {
        Log.d(TAG, "Dato hardware intercettato nel Servizio: Tipo=${result.type}")

        when (result.type) {
            "BPM" -> {
                if (result.value > 0) {
                    gestoreStatistiche.salvaBpm(result.value)
                }
            }
            "SPO2" -> {
                if (result.value > 0) {
                    gestoreStatistiche.salvaO2(result.value)
                    Log.d(TAG, "Service ha salvato SpO2: ${result.value}")
                }
            }
            "BP" -> {
                if (result.sys > 0) {
                    gestoreStatistiche.salvaPressione(result.sys, result.dia)
                    Log.d(TAG, "Service ha salvato Pressione: ${result.sys}/${result.dia}")
                }
            }
            "BATTERY" -> {
                if (result.battery >= 0) {
                    val chargingIcon = if (result.chargingStatus == 0x02) "⚡ " else ""
                    val batteryText = "Batteria: $chargingIcon${result.battery}%"

                    // Salviamo il testo formattato nelle SharedPreferences globali leggibili dai Fragment
                    val sharedPref = getSharedPreferences("RingPrefs", Context.MODE_PRIVATE)
                    sharedPref.edit().putString("last_battery_level", batteryText).apply()

                    Log.d(TAG, "Batteria aggiornata dal Service e salvata nelle Prefs: $batteryText")
                }
            }
        }
    }

    override fun onConnected() {
        Log.d(TAG, "Smart Ring connesso al Servizio")
    }

    override fun onDisconnected() {
        Log.d(TAG, "Smart Ring disconnesso dal Servizio")

        // Puliamo lo stato della batteria nelle SharedPreferences in caso di disconnessione
        val sharedPref = getSharedPreferences("RingPrefs", Context.MODE_PRIVATE)
        sharedPref.edit().putString("last_battery_level", "Batteria: --").apply()
    }

    override fun onError(msg: String) {
        Log.e(TAG, "Errore Smart Ring intercettato nel Servizio: $msg")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =====================================================================================
    // GENERAZIONE NOTIFICA PERSISTENTE
    // =====================================================================================
    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Monitoraggio Salute Realtime",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene attiva la registrazione da Smart Ring e Shimmer"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Monitoraggio Sanitario Attivo")
            .setContentText("Registrazione parametri vitali e attività in corso...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true) // Impedisce la rimozione accidentale da parte dell'utente
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}