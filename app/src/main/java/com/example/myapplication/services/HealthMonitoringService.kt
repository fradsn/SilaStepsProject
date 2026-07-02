package com.example.myapplication.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.myapplication.BT.ring.Decoder
import com.example.myapplication.BT.ring.SmartRingManager
import com.example.myapplication.R

class HealthMonitoringService : Service(), SmartRingManager.SmartRingListener {

    private lateinit var gestoreStatistiche: GestoreStatistiche
    private var ringManager: SmartRingManager? = null

    // Gestione temporizzatore ciclico
    private val autoMeasureHandler = Handler(Looper.getMainLooper())
    private var isAutoMeasuring = false

    // Configurazione intervalli (espressi in Millisecondi)
    private val TOTAL_CYCLE_INTERVAL = 5 * 60 * 1000L  // Il ciclo totale si ripete ogni 5 minuti
    private val BPM_WINDOW = 2 * 60 * 1000L             // Durata monitoraggio BPM (2 minuti)
    private val SPO2_WINDOW = 30 * 1000L                // Durata singola misurazione SpO2 (30 secondi)
    private val GAP_WINDOW = 5 * 1000L                  // Secondi di tolleranza per pulizia BLE (5 secondi)

    companion object {
        private const val TAG = "HEALTH_SERVICE"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "health_monitoring_channel"

        // Actions custom per l'attivazione/disattivazione ciclica
        const val ACTION_START_AUTO = "START_AUTO_MEASUREMENT"
        const val ACTION_STOP_AUTO = "STOP_AUTO_MEASUREMENT"

        @Volatile
        var isAutoMeasuringActive = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Servizio di Monitoraggio Creato")
        gestoreStatistiche = GestoreStatistiche.getInstance(this)

        ringManager = SmartRingManager.getActiveInstance()
        ringManager?.updateListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Servizio attivo. Action ricevuta: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_AUTO -> startAutomatedCycle()
            ACTION_STOP_AUTO -> stopAutomatedCycle()
            else -> {
                val macAddress = intent?.getStringExtra("MAC_ADDRESS")
                if (!macAddress.isNullOrBlank()) {
                    Log.d(TAG, "Ricevuto MAC_ADDRESS da connettere: $macAddress. Inizializzo istanza.")
                    ringManager = SmartRingManager.getInstance(this, macAddress, this)
                    ringManager?.connect(this)
                } else {
                    ringManager = SmartRingManager.getActiveInstance()
                    ringManager?.updateListener(this)
                }
            }
        }

        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    // =====================================================================================
    // CORE LOGIC: PIPELINE DI MISURAZIONE AUTOMATICA CON TOLLERANZA (BPM -> GAP -> SpO2 -> GAP -> BPM)
    // =====================================================================================
    private val autoMeasurementRunnable = object : Runnable {
        override fun run() {
            if (!isAutoMeasuring) return

            if (ringManager?.isConnected() == true) {
                Log.d(TAG, "[AUTO-CYCLE] Inizio Sequenza: Fase 1 - Avvio BPM (2 minuti)")
                ringManager?.startHeartRateMeasurement()

                // 1. Scaduti i 2 minuti di BPM, fermiamo l'anello per la prima sosta di tolleranza
                autoMeasureHandler.postDelayed({
                    if (!isAutoMeasuring) return@postDelayed
                    Log.d(TAG, "[AUTO-CYCLE] Pausa di Tolleranza: Stop temporaneo prima di SpO2 (5 secondi)")
                    ringManager?.stopAllMeasurements()

                    // 2. Passati i 5 secondi di pausa, avviamo l'Ossigeno in modo isolato e pulito
                    autoMeasureHandler.postDelayed({
                        if (!isAutoMeasuring) return@postDelayed
                        Log.d(TAG, "[AUTO-CYCLE] Fase 2 - Avvio SpO2 isolato (30 secondi)")
                        ringManager?.startSpO2Measurement()

                        // 3. Scaduti i 30 secondi di SpO2, fermiamo nuovamente l'anello per la seconda sosta
                        autoMeasureHandler.postDelayed({
                            if (!isAutoMeasuring) return@postDelayed
                            Log.d(TAG, "[AUTO-CYCLE] Pausa di Tolleranza: Stop temporaneo prima del ritorno a BPM (5 secondi)")
                            ringManager?.stopAllMeasurements()

                            // 4. Passati i 5 secondi di pausa, riavviamo l'ultimo ciclo BPM di 2 minuti
                            autoMeasureHandler.postDelayed({
                                if (!isAutoMeasuring) return@postDelayed
                                Log.d(TAG, "[AUTO-CYCLE] Fase 3 - Ritorno a BPM (Ulteriori 2 minuti)")
                                ringManager?.startHeartRateMeasurement()

                                // 5. Scaduti gli ultimi 2 minuti, spegniamo l'hardware fino al prossimo ciclo da 5 minuti
                                autoMeasureHandler.postDelayed({
                                    if (!isAutoMeasuring) return@postDelayed
                                    Log.d(TAG, "[AUTO-CYCLE] Fase 4 - Fine sequenza attiva. Standby hardware totale.")
                                    ringManager?.stopAllMeasurements()
                                }, BPM_WINDOW)

                            }, GAP_WINDOW)

                        }, SPO2_WINDOW)

                    }, GAP_WINDOW)

                }, BPM_WINDOW)

            } else {
                Log.w(TAG, "[AUTO-CYCLE] Tentativo fallito: Smart Ring non connesso.")
            }

            // Ripianifica l'intero blocco esattamente ogni 5 minuti dall'avvio iniziale
            autoMeasureHandler.postDelayed(this, TOTAL_CYCLE_INTERVAL)
        }
    }

    private fun startAutomatedCycle() {
        if (ringManager?.isConnected() == true) {
            if (!isAutoMeasuring) {
                isAutoMeasuring = true
                isAutoMeasuringActive = true
                Log.d(TAG, "Inizializzazione routine di misurazione ciclica avviata con successo.")
                autoMeasureHandler.post(autoMeasurementRunnable)
            }
        } else {
            Log.w(TAG, "Abortito startAutomatedCycle: l'anello risulta disconnesso.")
            isAutoMeasuring = false
            isAutoMeasuringActive = false
            autoMeasureHandler.removeCallbacksAndMessages(null)

            val sharedPref = getSharedPreferences("RingPrefs", Context.MODE_PRIVATE)
            sharedPref.edit().putBoolean("auto_measurement_enabled", false).apply()
        }
    }

    private fun stopAutomatedCycle() {
        if (isAutoMeasuring) {
            isAutoMeasuring = false
            isAutoMeasuringActive = false
            Log.d(TAG, "Routine di misurazione ciclica interrotta.")
            autoMeasureHandler.removeCallbacksAndMessages(null)
            ringManager?.stopAllMeasurements()
        }
    }

    // =====================================================================================
    // RICEZIONE E SALVATAGGIO DEI PARAMETRI VITALI (Senza filtri aggressivi software)
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

                    val sharedPref = getSharedPreferences("RingPrefs", Context.MODE_PRIVATE)
                    sharedPref.edit().putString("last_battery_level", batteryText).apply()

                    Log.d(TAG, "Batteria aggiornata dal Service e salvata nelle Prefs: $batteryText")
                }
            }
        }
    }

    override fun onConnected() {
        Log.d(TAG, "Smart Ring connesso al Servizio")
        val sharedPref = getSharedPreferences("RingPrefs", Context.MODE_PRIVATE)
        if (sharedPref.getBoolean("auto_measurement_enabled", false)) {
            startAutomatedCycle()
        }
    }

    override fun onDisconnected() {
        Log.d(TAG, "Smart Ring disconnesso dal Servizio")
        val sharedPref = getSharedPreferences("RingPrefs", Context.MODE_PRIVATE)
        sharedPref.edit().putString("last_battery_level", "Batteria: --").apply()

        autoMeasureHandler.removeCallbacksAndMessages(null)
    }

    override fun onError(msg: String) {
        Log.e(TAG, "Errore Smart Ring intercettato nel Servizio: $msg")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopAutomatedCycle()
        Log.d(TAG, "Servizio di Monitoraggio Distrutto")
    }

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
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}