package com.example.myapplication.UI

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlertPopupActivity : AppCompatActivity() {

    private lateinit var timer: CountDownTimer
    private lateinit var btnRequestHelp: Button
    private lateinit var btnCancel: Button
    private var mediaPlayer: MediaPlayer? = null // Gestore dell'allarme sonoro continuo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Forza lo sblocco dello schermo e l'accensione hardware dei pixel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_alert_popup)

        // AVVIA IL SUONO CONTINUO DI SVEGLIA DI SISTEMA
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alarmUri)
                setAudioStreamType(AudioManager.STREAM_ALARM) // Stream audio dedicato alle sveglie
                isLooping = true // Riproduci in loop continuo
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val title = intent.getStringExtra("EXTRA_TITLE") ?: "Health Alert"
        val message = intent.getStringExtra("EXTRA_MESSAGE") ?: "Anomalous vital signs detected."
        val timestamp = intent.getLongExtra("EXTRA_TIMESTAMP", System.currentTimeMillis())

        val tvTitle = findViewById<TextView>(R.id.tvAlertTitle)
        val tvMessage = findViewById<TextView>(R.id.tvAlertMessage)
        val tvTime = findViewById<TextView>(R.id.tvAlertTime)
        btnRequestHelp = findViewById<Button>(R.id.btnRequestHelp)
        btnCancel = findViewById<Button>(R.id.btnCancel)

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val formattedTime = sdf.format(Date(timestamp))

        tvTitle.text = title
        tvMessage.text = message
        tvTime.text = "Detected at: $formattedTime"

        // Configura il timer di 60 secondi (60000 ms) con step di 1 secondo (1000 ms)
        timer = object : CountDownTimer(60000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = millisUntilFinished / 1000
                btnRequestHelp.text = "Request Help ($secondsLeft s)"
            }

            override fun onFinish() {
                // IL CORE DEL CAMBIAMENTO: L'utente non ha reagito entro il minuto (potenziale svenimento).
                // Spegniamo l'audio ed eseguiamo l'invio AUTOMATICO dei soccorsi.
                stopAlarmSound()

                // Trigger dei soccorsi automatici
                sendEmergencyServices(isAutomaticTrigger = true)

                finish()
            }
        }.start()

        // Pressione manuale del tasto per richiedere aiuto immediato
        btnRequestHelp.setOnClickListener {
            timer.cancel()
            stopAlarmSound()
            sendEmergencyServices(isAutomaticTrigger = false)
            finish()
        }

        // Pressione del tasto annulla (l'utente sta bene, rientra il falso allarme)
        btnCancel.setOnClickListener {
            timer.cancel()
            stopAlarmSound()
            Toast.makeText(this, "Alert dismissed by user. Status safe.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun stopAlarmSound() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
    }

    /**
     * Punto di integrazione futuro per la rete del Digital Twin del parco.
     * Gestisce sia la chiamata manuale che il timeout automatico per svenimento.
     */
    private fun sendEmergencyServices(isAutomaticTrigger: Boolean) {
        if (isAutomaticTrigger) {
            // Log e feedback specifico per i soccorsi inviati a causa del timeout
            android.util.Log.e("HEALTH_EMERGENCY", "🚨 [TIMEOUT] User unresponsive! AUTOMATIC emergency services requested via Digital Twin.")
            Toast.makeText(applicationContext, "CRITICAL: User unresponsive! Automatic help requested.", Toast.LENGTH_LONG).show()
        } else {
            // Log per la richiesta manuale dell'utente cosciente
            android.util.Log.w("HEALTH_EMERGENCY", "🚨 User manually requested emergency services via Digital Twin.")
            Toast.makeText(this, "EMERGENCY DISPATCHED (Manual Request Checked)", Toast.LENGTH_LONG).show()
        }

        // TODO: In futuro, qui recupererai l'ultima posizione nota da LocationService
        // e farai la chiamata di rete HTTP/MQTT verso il Digital Twin inviando i parametri vitali + coordinate GPS.
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::timer.isInitialized) {
            timer.cancel()
        }
        stopAlarmSound()
    }
}