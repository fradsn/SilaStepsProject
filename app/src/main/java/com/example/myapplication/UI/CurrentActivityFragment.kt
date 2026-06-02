package com.example.myapplication.UI

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.myapplication.BT.ring.Decoder
import com.example.myapplication.BT.ring.SmartRingManager
import com.example.myapplication.Motion.session.MotionSessionManager
import com.example.myapplication.Motion.session.MotionUiState
import com.example.myapplication.R
import com.example.myapplication.services.GestoreStatistiche
import com.example.myapplication.services.HealthMonitoringService

class CurrentActivityFragment : Fragment(), SmartRingManager.SmartRingListener, MotionSessionManager.Observer {

    companion object {
        var lastBpm: String = "--"
        var lastSpO2: String = "-- %"
        var lastBP: String = "-- / --"
    }

    private lateinit var gestoreStatistiche: GestoreStatistiche

    private lateinit var tvBpmValue: TextView
    private lateinit var tvSpO2Value: TextView
    private lateinit var tvBpValue: TextView

    private lateinit var btnToggleHR: Button
    private lateinit var btnStartSpO2: Button
    private lateinit var btnStartBP: Button

    private lateinit var tvActivityLabel: TextView
    private lateinit var tvConfidenceValue: TextView
    private lateinit var progressConfidence: ProgressBar
    private lateinit var ivActivityIcon: ImageView

    private lateinit var pulseRing1: View
    private lateinit var pulseRing2: View
    private lateinit var pulseRing3: View

    private var ringManager: SmartRingManager? = null

    // LOGICA DI RICHIESTA CICLICA (UI Polling Timer)
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            aggiornaUiDaDatabase()
            // Ripete l'interrogazione ogni 2000 millisecondi (2 secondi)
            pollHandler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gestoreStatistiche = GestoreStatistiche.getInstance(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.activity_monitor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        startPulseAnimation()

        ringManager = SmartRingManager.Companion.getActiveInstance()

        btnToggleHR.text = if (ringManager?.getActiveMeasurementType() == "BPM") "STOP BPM" else "START BPM"
        tvBpmValue.text = lastBpm
        tvSpO2Value.text = lastSpO2
        tvBpValue.text = lastBP

        MotionSessionManager.initialize(requireContext())
        MotionSessionManager.addObserver(this)
        renderMotionState(MotionSessionManager.getState())

        setupSmartRingButtons()
    }

    private fun bindViews(view: View) {
        tvActivityLabel = view.findViewById(R.id.tvActivityLabel)
        tvConfidenceValue = view.findViewById(R.id.tvConfidenceValue)
        progressConfidence = view.findViewById(R.id.progressConfidence)
        ivActivityIcon = view.findViewById(R.id.ivActivityIcon)

        pulseRing1 = view.findViewById(R.id.pulseRing1)
        pulseRing2 = view.findViewById(R.id.pulseRing2)
        pulseRing3 = view.findViewById(R.id.pulseRing3)

        tvBpmValue = view.findViewById(R.id.tvBpmValue)
        tvSpO2Value = view.findViewById(R.id.tvSpO2Value)
        tvBpValue = view.findViewById(R.id.tvBpValue)

        btnToggleHR = view.findViewById(R.id.btnToggleHR)
        btnStartSpO2 = view.findViewById(R.id.btnStartSpO2)
        btnStartBP = view.findViewById(R.id.btnStartBP)
    }

    private fun setupSmartRingButtons() {
        btnToggleHR.setOnClickListener {
            val isConnected = ringManager?.isConnected() == true
            if (isConnected) {
                if (ringManager?.getActiveMeasurementType() == "BPM") {
                    ringManager?.stopAllMeasurements()
                    btnToggleHR.text = "START BPM"
                    context?.stopService(Intent(context, HealthMonitoringService::class.java))
                } else {
                    val serviceIntent = Intent(context, HealthMonitoringService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context?.startForegroundService(serviceIntent)
                    } else {
                        context?.startService(serviceIntent)
                    }

                    ringManager?.startHeartRateMeasurement()
                    btnToggleHR.text = "STOP BPM"
                }
            } else {
                Toast.makeText(context, "Connetti l'anello dal profilo prima di misurare", Toast.LENGTH_SHORT).show()
            }
        }

        btnStartSpO2.setOnClickListener {
            if (ringManager?.isConnected() == true) {
                if (ringManager?.isMeasuring() == false) {
                    val serviceIntent = Intent(context, HealthMonitoringService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context?.startForegroundService(serviceIntent)
                    } else {
                        context?.startService(serviceIntent)
                    }

                    ringManager?.startSpO2Measurement()
                } else {
                    Toast.makeText(context, "Misurazione già in corso. Ferma l'attività attuale.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Anello non connesso", Toast.LENGTH_SHORT).show()
            }
        }

        btnStartBP.setOnClickListener {
            if (ringManager?.isConnected() == true) {
                if (ringManager?.isMeasuring() == false) {
                    val serviceIntent = Intent(context, HealthMonitoringService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context?.startForegroundService(serviceIntent)
                    } else {
                        context?.startService(serviceIntent)
                    }

                    ringManager?.startBloodPressureMeasurement()
                } else {
                    Toast.makeText(context, "Misurazione già in corso. Ferma l'attività attuale.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Anello non connesso", Toast.LENGTH_SHORT).show()
            }
        }

        btnStartBP.setOnLongClickListener {
            if (ringManager?.isConnected() == true) {
                showCalibrationDialog()
                true
            } else {
                Toast.makeText(context, "Anello non connesso", Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    private fun showCalibrationDialog() {
        val context = context ?: return
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Calibrazione Pressione")
        builder.setMessage("Rimani fermo. Avvia la misurazione sull'anello e inserisci qui sotto i dati appena letti dallo sfigmomanometro a braccio:")

        val linearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 24, 60, 24)
        }

        val etSystolic = EditText(context).apply {
            hint = "Pressione Sistolica [60-250]"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val etDiastolic = EditText(context).apply {
            hint = "Pressione Diastolica [40-150]"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        linearLayout.addView(etSystolic)
        linearLayout.addView(etDiastolic)
        builder.setView(linearLayout)

        builder.setPositiveButton("Calibra") { dialog, _ ->
            val sysStr = etSystolic.text.toString()
            val diaStr = etDiastolic.text.toString()

            if (sysStr.isNotEmpty() && diaStr.isNotEmpty()) {
                val systolic = sysStr.toIntOrNull() ?: 0
                val diastolic = diaStr.toIntOrNull() ?: 0
                ringManager?.sendBloodPressureCalibration(systolic, diastolic)
            } else {
                Toast.makeText(context, "Inserisci entrambi i parametri per continuare", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        builder.setNegativeButton("Annulla") { dialog, _ -> dialog.dismiss() }
        builder.show()
    }

    override fun onMotionStateChanged(state: MotionUiState) {
        if (!isAdded) return
        renderMotionState(state)
    }

    private fun renderMotionState(state: MotionUiState) {
        tvActivityLabel.text = state.currentActivity
        tvConfidenceValue.text = "${state.confidencePercent}%"
        progressConfidence.progress = state.confidencePercent
        setActivityIcon(state.currentActivity)
    }

    private fun setActivityIcon(activity: String) {
        val drawableRes = when (activity.lowercase()) {
            "walking" -> getDrawableIdByName("ic_activity_walking")
            "jogging", "running" -> getDrawableIdByName("ic_activity_jogging")
            "sitting" -> getDrawableIdByName("ic_activity_sitting")
            "standing" -> getDrawableIdByName("ic_activity_standing")
            else -> getDrawableIdByName("ic_activity_walking")
        }
        if (drawableRes != 0) ivActivityIcon.setImageResource(drawableRes)
    }

    private fun getDrawableIdByName(name: String): Int {
        return resources.getIdentifier(name, "drawable", requireContext().packageName)
    }

    override fun onConnected() {}

    override fun onDisconnected() {
        lastBpm = "--"
        activity?.runOnUiThread {
            if (isAdded) {
                btnToggleHR.text = "START BPM"
                tvBpmValue.text = lastBpm
            }
        }
    }

    // QUESTO METODO NON RICEVE PIÙ I PACCHETTI DIRETTI (Se ne occupa stabilmente il Service)
    override fun onDataReceived(result: Decoder.DecodedResult) {
        // Mostriamo i Toast per le calibrazioni, ma i dati vitali li leggiamo via timer dal DB
        if (result.type == "CALIBRATION_RESULT") {
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                when (result.calibrationStatus) {
                    0 -> Toast.makeText(context, "Calibrazione completata!", Toast.LENGTH_LONG).show()
                    1 -> Toast.makeText(context, "Errore parametri fuori scala", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(context, "Calibrazione rifiutata", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onError(msg: String) {
        activity?.runOnUiThread {
            if (isAdded) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Metodo di Polling ciclico attivato dall'Handler
     */
    private fun aggiornaUiDaDatabase() {
        if (!isAdded) return
        try {
            // Estrae l'ultimo record per ciascuna tabella
            val listaBpm = gestoreStatistiche.getBpm()
            if (listaBpm.isNotEmpty()) {
                lastBpm = listaBpm.last().bpm.toString()
                tvBpmValue.text = lastBpm
            }

            val listaO2 = gestoreStatistiche.getO2()
            if (listaO2.isNotEmpty()) {
                lastSpO2 = "${listaO2.last().value} %"
                tvSpO2Value.text = lastSpO2
            }

            val listaPressioni = gestoreStatistiche.getPressioni()
            if (listaPressioni.isNotEmpty()) {
                val uP = listaPressioni.last()
                lastBP = "${uP.systolic} / ${uP.diastolic}"
                tvBpValue.text = lastBP
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        MotionSessionManager.addObserver(this)
        renderMotionState(MotionSessionManager.getState())

        // 1. Legge subito lo stato attuale del DB all'apertura
        aggiornaUiDaDatabase()

        // 2. AVVIA LA RICHIESTA CICLICA DI POLLING
        pollHandler.post(pollRunnable)

        val activeInstance = SmartRingManager.Companion.getActiveInstance()
        if (activeInstance != null) {
            ringManager = activeInstance

            // IMPORTANTE: NON cambiamo il listener del manager hardware!
            // Lasciamo che lo SmartRingManager continui a inviare i dati al Service.
            // Aggiorniamo solo il testo del bottone per coerenza grafica
            btnToggleHR.text = if (ringManager?.getActiveMeasurementType() == "BPM") "STOP BPM" else "START BPM"

            if (ringManager?.isConnected() == false) {
                btnToggleHR.text = "START BPM"
                tvBpmValue.text = lastBpm
            }
        }
    }

    override fun onPause() {
        super.onPause()
        MotionSessionManager.removeObserver(this)

        // IMPORTANTE: INTERROMPIAMO IL TIMER CICLICO quando l'utente esce dalla tab
        pollHandler.removeCallbacks(pollRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        MotionSessionManager.removeObserver(this)
        pollHandler.removeCallbacks(pollRunnable)
    }

    private fun startPulseAnimation() {
        animatePulseRing(pulseRing3, 2200L, 0L)
        animatePulseRing(pulseRing2, 2200L, 400L)
    }

    private fun animatePulseRing(view: View, durationMs: Long, delay: Long) {
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.18f, 1f).apply {
            duration = durationMs
            startDelay = delay
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.18f, 1f).apply {
            duration = durationMs
            startDelay = delay
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        AnimatorSet().apply { playTogether(scaleX, scaleY); start() }
    }
}