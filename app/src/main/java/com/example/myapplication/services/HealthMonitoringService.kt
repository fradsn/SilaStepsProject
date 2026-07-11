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
import com.example.myapplication.Motion.session.MotionSessionManager
import com.example.myapplication.Motion.session.MotionUiState
import com.example.myapplication.MyApplication
import com.example.myapplication.R
import com.example.myapplication.UI.AlertPopupActivity

class HealthMonitoringService : Service(), SmartRingManager.SmartRingListener, MotionSessionManager.Observer {

    private lateinit var gestoreStatistiche: GestoreStatistiche
    private var ringManager: SmartRingManager? = null

    // Gestione temporizzatore ciclico automatico dell'anello
    private val autoMeasureHandler = Handler(Looper.getMainLooper())
    private var isAutoMeasuring = false

    // Configurazioni intervalli (in Millisecondi)
    private val SPO2_WINDOW = 30 * 1000L
    private val GAP_WINDOW = 5 * 1000L
    private val VITAL_VALIDITY_WINDOW = 5 * 60 * 1000L   // Finestra di tolleranza per ossigeno
    private val VITAL_VALIDITY_WINDOWBPM =  60 * 1000L  // Finestra di tolleranza per BPM
    // Variabili di cache per la fusione dei dati (Data Fusion)
    private var currentActivity: String = "UNKNOWN"
    private var lastBpm: Int? = null
    private var lastBpmTimestamp: Long = 0L
    private var lastSpO2: Int? = null
    private var lastSpO2Timestamp: Long = 0L

    // Meccanismo anti-spam per le notifiche
    private var lastBpmAlertTime = 0L
    private var lastSpO2AlertTime = 0L
    private val ALERT_COOLDOWN = 90 * 1000L             // Aspetta almeno 90 secondi prima di ripetere la stessa allerta

    companion object {
        private const val TAG = "HEALTH_SERVICE"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "health_monitoring_channel"
        private const val EMERGENCY_CHANNEL_ID = "health_emergency_channel" // Canale dedicato alle emergenze acustiche

        const val ACTION_START_AUTO = "START_AUTO_MEASUREMENT"
        const val ACTION_STOP_AUTO = "STOP_AUTO_MEASUREMENT"

        @Volatile
        var isAutoMeasuringActive = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Health Monitoring Service Created")
        gestoreStatistiche = GestoreStatistiche.getInstance(this)

        ringManager = SmartRingManager.getActiveInstance()
        ringManager?.updateListener(this)

        // Sostituito il mock con il vero Observer di MotionSessionManager
        Log.d(TAG, "Connecting as Observer to MotionSessionManager...")
        MotionSessionManager.addObserver(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service active. Received Action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_AUTO -> startAutomatedCycle()
            ACTION_STOP_AUTO -> stopAutomatedCycle()
            else -> {
                val macAddress = intent?.getStringExtra("MAC_ADDRESS")
                if (!macAddress.isNullOrBlank()) {
                    Log.d(TAG, "Received MAC_ADDRESS to connect: $macAddress. Initializing instance.")
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
    // REAL SHIMMER HARDWARE OBSERVER CALLBACK LISTENER
    // =====================================================================================
    override fun onMotionStateChanged(state: MotionUiState) {
        val incomingActivity = state.currentActivity.lowercase().trim()

        if (incomingActivity.isNotEmpty() && incomingActivity != "unknown") {
            this.currentActivity = incomingActivity
            Log.d(TAG, "[LOG-SHIMMER] Activity context updated: '$currentActivity' (${state.confidencePercent}%). Evaluating alerts...")
            checkAlertingLogic()
        }
    }

    // =====================================================================================
    // REAL HARDWARE SMART RING VITAL PARAMETERS RECEIVER
    // =====================================================================================
    override fun onDataReceived(result: Decoder.DecodedResult) {
        val now = System.currentTimeMillis()

        when (result.type) {
            "BPM" -> {
                if (result.value > 0) {
                    Log.i(TAG, "[LOG-HARDWARE] Real Smart Ring captured BPM: ${result.value}")
                    gestoreStatistiche.salvaBpm(result.value)

                    lastBpm = result.value
                    lastBpmTimestamp = now
                    checkAlertingLogic()
                }
            }
            "SPO2" -> {
                if (result.value > 0) {
                    Log.i(TAG, "[LOG-HARDWARE] Real Smart Ring captured SpO2: ${result.value}%")
                    gestoreStatistiche.salvaO2(result.value)

                    lastSpO2 = result.value
                    lastSpO2Timestamp = now
                    checkAlertingLogic()
                }
            }
            "BP" -> {
                if (result.sys > 0) {
                    Log.d(TAG, "[LOG-HARDWARE] Real Smart Ring captured Blood Pressure: ${result.sys}/${result.dia}")
                    gestoreStatistiche.salvaPressione(result.sys, result.dia)
                }
            }
            "BATTERY" -> {
                if (result.battery >= 0) {
                    val chargingIcon = if (result.chargingStatus == 0x02) "⚡ " else ""
                    val batteryText = "Battery: $chargingIcon${result.battery}%"
                    val sharedPref = getSharedPreferences("RingPrefs", Context.MODE_PRIVATE)
                    sharedPref.edit().putString("last_battery_level", batteryText).apply()
                }
            }
        }
    }

    // =====================================================================================
    // MULTI-MODAL DATA FUSION ALERTING MODULE
    // =====================================================================================

    private fun checkAlertingLogic() {
        val now = System.currentTimeMillis()
        val activity = currentActivity

        Log.d(TAG, "[LOG-ALERT] Pipeline evaluation started. Current Context Activity: '$activity'")

        // LEGGE L'INTERVALLO SCELTO DALL'UTENTE (Default 200s se non trova nulla)
        val sharedPref = getSharedPreferences("RingPrefs", Context.MODE_PRIVATE)
        val currentBpmWindowMs = sharedPref.getLong("auto_bpm_window_ms", 200 * 1000L)

        // CALCOLO DINAMICO FINESTRA OSSIGENO:
        // Un ciclo intero dura: bpmWindow + spo2Window (30s) + 2*gap (10s).
        // Diamo una tolleranza pari a 1.5 volte la durata del ciclo completo (flessibile ma sicuro).
        val totalCycleTimeMs = currentBpmWindowMs + SPO2_WINDOW + (GAP_WINDOW * 2)
        val dynamicOxygenValidityWindow = (totalCycleTimeMs * 1.5).toLong()

        // 1. VALUTAZIONE CONTRASTO BATTITO CARDIACO (BPM) + ATTIVITÀ FISICA
        val bpm = lastBpm
        if (bpm != null) {
            val bpmAgeMs = now - lastBpmTimestamp
            val bpmAgeSec = bpmAgeMs / 1000

            Log.d(TAG, "[LOG-ALERT] Cached BPM: $bpm | Age: ${bpmAgeSec}s (Max Allowed: ${VITAL_VALIDITY_WINDOWBPM / 1000}s)")

            if (bpmAgeMs < VITAL_VALIDITY_WINDOWBPM) {
                val cooldownLeft = ALERT_COOLDOWN - (now - lastBpmAlertTime)

                if (cooldownLeft <= 0) {
                    Log.d(TAG, "[LOG-ALERT] BPM Anti-spam filter PASSED. Testing condition thresholds for '$activity'...")
                    when (activity) {
                        "sitting", "standing" -> {
                            if (bpm < 50) {
                                Log.w(TAG, "[LOG-TRIGGER] Threshold MATCHED: Bradycardia at rest ($bpm < 50)!")
                                triggerAlertNotification("Bradycardia Detected", "Critical low heart rate ($bpm BPM) found during rest ($activity).")
                                lastBpmAlertTime = now
                            } else if (bpm > 100) {
                                Log.w(TAG, "[LOG-TRIGGER] Threshold MATCHED: Tachycardia at rest ($bpm > 100)!")
                                triggerAlertNotification("Tachycardia at Rest", "High heart rate ($bpm BPM) detected while stationary ($activity).")
                                lastBpmAlertTime = now
                            } else {
                                Log.d(TAG, "[LOG-ALERT] BPM $bpm is safe within range [50-100] for '$activity'.")
                            }
                        }
                        "walking" -> {
                            if (bpm > 135) {
                                Log.w(TAG, "[LOG-TRIGGER] Threshold MATCHED: High BPM during walk ($bpm > 135)!")
                                triggerAlertNotification("Abnormal Heart Rate", "Elevated heart rate ($bpm BPM) captured during a casual walk.")
                                lastBpmAlertTime = now
                            } else {
                                Log.d(TAG, "[LOG-ALERT] BPM $bpm is safe (<135) for walking.")
                            }
                        }
                        "jogging" -> {
                            if (bpm > 175) {
                                Log.w(TAG, "[LOG-TRIGGER] Threshold MATCHED: Ceiling breached during jog ($bpm > 175)!")
                                triggerAlertNotification("Cardio Safety Alert", "Maximum safety ceiling breached: $bpm BPM while jogging!")
                                lastBpmAlertTime = now
                            } else {
                                Log.d(TAG, "[LOG-ALERT] BPM $bpm is safe (<175) for jogging.")
                            }
                        }
                        else -> Log.d(TAG, "[LOG-ALERT] Activity '$activity' does not have active BPM constraints.")
                    }
                } else {
                    Log.v(TAG, "[LOG-ALERT] BPM Alert throttled by anti-spam. Cooldown ends in ${cooldownLeft / 1000}s.")
                }
            } else {
                Log.w(TAG, "[LOG-ALERT] BPM evaluation SKIPPED: Data is too old (${bpmAgeSec}s > ${VITAL_VALIDITY_WINDOWBPM / 1000}s).")
            }
        } else {
            Log.d(TAG, "[LOG-ALERT] BPM evaluation SKIPPED: No ring data has been received yet during this session.")
        }

        // 2. VALUTAZIONE CONTRASTO SATURAZIONE OSSIGENO (SpO2) + ATTIVITÀ FISICA (ORA DINAMICA)
        val o2 = lastSpO2
        if (o2 != null) {
            val o2AgeMs = now - lastSpO2Timestamp
            val o2AgeSec = o2AgeMs / 1000
            val maxAllowedO2AgeSec = dynamicOxygenValidityWindow / 1000

            Log.d(TAG, "[LOG-ALERT] Cached SpO2: $o2% | Age: ${o2AgeSec}s (Dynamic Max Allowed: ${maxAllowedO2AgeSec}s)")

            if (o2AgeMs < dynamicOxygenValidityWindow) {
                val cooldownLeft = ALERT_COOLDOWN - (now - lastSpO2AlertTime)

                if (o2 < 92) {
                    if (cooldownLeft <= 0) {
                        Log.w(TAG, "[LOG-TRIGGER] Threshold MATCHED: Low oxygen ($o2 < 92%)!")
                        val hazardLevel = if (activity == "jogging") "Monitor closely (Exercise Induced Hypoxia)" else "CRITICAL RISK (Hypoxemia at rest!)"

                        triggerAlertNotification("Low Blood Oxygen Level", "SpO2 dropped down to $o2% during $activity. Status: $hazardLevel")
                        lastSpO2AlertTime = now
                    } else {
                        Log.v(TAG, "[LOG-ALERT] SpO2 Alert throttled by anti-spam. Cooldown ends in ${cooldownLeft / 1000}s.")
                    }
                } else {
                    Log.d(TAG, "[LOG-ALERT] SpO2 value ($o2%) is optimal and safe.")
                }
            } else {
                Log.w(TAG, "[LOG-ALERT] SpO2 evaluation SKIPPED: Data is too old (${o2AgeSec}s > ${maxAllowedO2AgeSec}s).")
            }
        }
    }

    // =====================================================================================
    // DISPATCHER ALLERTE: EMISSIONE ACUSTICA E LANCIO ATTIVITÀ POPUP SU SCHERMO (3 CASI)
    // =====================================================================================
    private fun triggerAlertNotification(title: String, message: String) {
        Log.w(TAG, "⚠️ [ALERT DISPATCHED TO SYSTEM] Title: '$title' | Message: '$message'")

        val now = System.currentTimeMillis()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 1. CONFIGURAZIONE DEL CANALE AD ALTA IMPORTANZA CON AUDIO DA SVEGLIA (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val emergencyChannel = NotificationChannel(
                EMERGENCY_CHANNEL_ID,
                "Health Emergency Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical vital signs and health danger alerts"
                enableLights(true)
                enableVibration(true)
                setBypassDnd(true) // Passa sopra la modalità "Non Disturbare" se attiva
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(emergencyChannel)
        }

        // 2. PREPARAZIONE DELL'INTENT PER IL POPUP GRAFICO
        val popupIntent = Intent(this, AlertPopupActivity::class.java).apply {
            putExtra("EXTRA_TITLE", title)
            putExtra("EXTRA_MESSAGE", message)
            putExtra("EXTRA_TIMESTAMP", now)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val fullScreenPendingIntent = android.app.PendingIntent.getActivity(
            this,
            now.toInt(),
            popupIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        // 3. COSTRUZIONE NOTIFICA (Configurata con priorità massima e categoria CALL/ALARM)
        val alertNotification = NotificationCompat.Builder(this, EMERGENCY_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPendingIntent, true) // CASO 1: Schermo Spento
            .setContentIntent(fullScreenPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        manager.notify(now.toInt(), alertNotification)

        // 4. VERIFICA DELLO STATO HARDWARE DELLO SCHERMO
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val isInteractive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }

        if (isInteractive) {
            if (MyApplication.isAppInForeground()) {
                // CASO 2: App Attiva (In Foreground) -> Apertura diretta istantanea
                Log.d(TAG, "[LOG-ALERT] Screen is ON and App is in FOREGROUND. Launching popup Activity directly.")
                startActivity(popupIntent)
            } else {
                // CASO 3: App in Background (Schermo Acceso) -> Richiede autorizzazione overlay speciale
                Log.d(TAG, "[LOG-ALERT] Screen is ON but App is in BACKGROUND. Checking SYSTEM_ALERT_WINDOW permission...")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && android.provider.Settings.canDrawOverlays(this)) {
                    Log.w(TAG, "[LOG-ALERT] Overlay permission granted! Forcing direct background startActivity overlay.")
                    popupIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    startActivity(popupIntent)
                } else {
                    Log.e(TAG, "[LOG-ALERT] Overlay permission MISSING! Cannot force window. Falling back to Settings routing automation.")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val settingsIntent = Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(settingsIntent)
                    }
                }
            }
        } else {
            Log.d(TAG, "[LOG-ALERT] Screen is OFF. The system fullScreenIntent will natively wake up the display.")
        }
    }

    // =====================================================================================
    // GESTIONE METODI ESISTENTI DELLO SMART RING (INVARIATI)
    // =====================================================================================
    private val autoMeasurementRunnable = object : Runnable {
        override fun run() {
            if (!isAutoMeasuring) return
            if (ringManager?.isConnected() == true) {
                val sharedPref = getSharedPreferences("RingPrefs", Context.MODE_PRIVATE)
                val dynamicBpmWindow = sharedPref.getLong("auto_bpm_window_ms", 200 * 1000L)
                ringManager?.startHeartRateMeasurement()
                autoMeasureHandler.postDelayed({
                    if (!isAutoMeasuring) return@postDelayed
                    ringManager?.stopAllMeasurements()
                    autoMeasureHandler.postDelayed({
                        if (!isAutoMeasuring) return@postDelayed
                        ringManager?.startSpO2Measurement()
                        autoMeasureHandler.postDelayed({
                            if (!isAutoMeasuring) return@postDelayed
                            ringManager?.stopAllMeasurements()
                            autoMeasureHandler.postDelayed({
                                if (!isAutoMeasuring) return@postDelayed
                                this.run()
                            }, GAP_WINDOW)
                        }, SPO2_WINDOW)
                    }, GAP_WINDOW)
                }, dynamicBpmWindow)
            } else {
                autoMeasureHandler.postDelayed(this, 10000L)
            }
        }
    }

    private fun startAutomatedCycle() {
        if (ringManager?.isConnected() == true) {
            if (!isAutoMeasuring) {
                isAutoMeasuring = true
                isAutoMeasuringActive = true
                autoMeasureHandler.post(autoMeasurementRunnable)
            }
        } else {
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
            autoMeasureHandler.removeCallbacksAndMessages(null)
            ringManager?.stopAllMeasurements()
        }
    }

    override fun onConnected() {
        val sharedPref = getSharedPreferences("RingPrefs", Context.MODE_PRIVATE)
        if (sharedPref.getBoolean("auto_measurement_enabled", false)) {
            startAutomatedCycle()
        }
    }

    override fun onDisconnected() {
        val sharedPref = getSharedPreferences("RingPrefs", Context.MODE_PRIVATE)
        sharedPref.edit().putString("last_battery_level", "Battery: --").apply()
        autoMeasureHandler.removeCallbacksAndMessages(null)
    }

    override fun onError(msg: String) { Log.e(TAG, "Smart Ring Error: $msg") }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopAutomatedCycle()
        // Rimozione pulita dell'Observer per prevenire leak di memoria
        MotionSessionManager.removeObserver(this)
        Log.d(TAG, "Health Monitoring Service Destroyed")
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Realtime Health Monitoring", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Health Monitoring System Active")
            .setContentText("Recording vital signs and fusion alerts active...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}