package com.example.myapplication.UI

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
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
import com.example.myapplication.service.GestoreStatistiche

class CurrentActivityFragment : Fragment(), SmartRingManager.SmartRingListener, MotionSessionManager.Observer {

    companion object {
        var isMeasuringHRGlobal: Boolean = false

        // Variabili per mantenere gli ultimi valori visualizzati tra i cambi di tab
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

    // Componenti grafici dedicati all'output del Machine Learning
    private lateinit var tvActivityLabel: TextView
    private lateinit var tvConfidenceValue: TextView
    private lateinit var progressConfidence: ProgressBar
    private lateinit var ivActivityIcon: ImageView

    private lateinit var pulseRing1: View
    private lateinit var pulseRing2: View
    private lateinit var pulseRing3: View

    private var ringManager: SmartRingManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gestoreStatistiche = GestoreStatistiche.Companion.getInstance(requireContext())
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

        // --- RIPRISTINO STATO UI ---
        btnToggleHR.text = if (isMeasuringHRGlobal) "STOP BPM" else "START BPM"
        tvBpmValue.text = lastBpm
        tvSpO2Value.text = lastSpO2
        tvBpValue.text = lastBP
        // ---------------------------

        // Inizializzazione e aggancio dell'osservatore per il modulo di Machine Learning
        MotionSessionManager.initialize(requireContext())
        MotionSessionManager.addObserver(this)
        renderMotionState(MotionSessionManager.getState())

        // Recuperiamo l'istanza attiva globale se presente nell'applicazione
        ringManager = SmartRingManager.Companion.getActiveInstance()

        setupSmartRingButtons()
    }

    private fun bindViews(view: View) {
        // Componenti grafici dell'attività del Machine Learning
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
                if (isMeasuringHRGlobal) {
                    ringManager?.stopAllMeasurements()
                    isMeasuringHRGlobal = false
                    btnToggleHR.text = "START BPM"
                } else {
                    ringManager?.startHeartRateMeasurement()
                    isMeasuringHRGlobal = true
                    btnToggleHR.text = "STOP BPM"
                }
            } else {
                Toast.makeText(
                    context,
                    "Connetti l'anello dal profilo prima di misurare",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        btnStartSpO2.setOnClickListener {
            if (ringManager?.isConnected() == true) {
                ringManager?.startSpO2Measurement()
            } else {
                Toast.makeText(context, "Anello non connesso", Toast.LENGTH_SHORT).show()
            }
        }

        btnStartBP.setOnClickListener {
            if (ringManager?.isConnected() == true) {
                ringManager?.startBloodPressureMeasurement()
            } else {
                Toast.makeText(context, "Anello non connesso", Toast.LENGTH_SHORT).show()
            }
        }

        // MODIFICA: Pressione prolungata per aprire la calibrazione medica
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

    /**
     * Costruisce ed espone una finestra di dialogo interattiva per l'immissione numerica
     * dei parametri sistolici e diastolici di calibrazione.
     */
    private fun showCalibrationDialog() {
        val context = context ?: return
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Calibrazione Pressione")
        builder.setMessage("Rimani fermo. Avvia la misurazione sull'anello e inserisci qui sotto i dati appena letti dallo sfigmomanometro a braccio:")

        // Layout contenitore verticale per ospitare le EditText via codice
        val linearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 24, 60, 24)
        }

        val etSystolic = EditText(context).apply {
            hint = "Pressione Sistolica (es. 120) [60-250]"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val etDiastolic = EditText(context).apply {
            hint = "Pressione Diastolica (es. 80) [40-150]"
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

                // Invia i dati strutturati direttamente al Bluetooth Manager
                ringManager?.sendBloodPressureCalibration(systolic, diastolic)
            } else {
                Toast.makeText(context, "Inserisci entrambi i parametri per continuare", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        builder.setNegativeButton("Annulla") { dialog, _ -> dialog.dismiss() }
        builder.show()
    }

    // =====================================================================================
    // GESTIONE MACHINE LEARNING / MOTION OBSERVER CALLBACKS
    // =====================================================================================
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

        if (drawableRes != 0) {
            ivActivityIcon.setImageResource(drawableRes)
        }
    }

    private fun getDrawableIdByName(name: String): Int {
        return resources.getIdentifier(name, "drawable", requireContext().packageName)
    }

    // =====================================================================================
    // GESTIONE SMART RING LISTENER CALLBACKS
    // =====================================================================================
    override fun onConnected() {
        // Callback di allineamento stato grafico opzionale
    }

    override fun onDisconnected() {
        // Aggiorna IMMEDIATAMENTE lo stato logico globale, a prescindere dalla UI
        isMeasuringHRGlobal = false
        lastBpm = "--"

        activity?.runOnUiThread {
            if (isAdded) { // Verifica che il fragment sia ancora attaccato in sicurezza
                btnToggleHR.text = "START BPM"
                tvBpmValue.text = lastBpm
            }
        }
    }

    override fun onDataReceived(result: Decoder.DecodedResult) {
        activity?.runOnUiThread {
            if (!isAdded) return@runOnUiThread
            when (result.type) {
                "BPM" -> {
                    if (result.value > 0) {
                        lastBpm = result.value.toString()
                        tvBpmValue.text = lastBpm
                        gestoreStatistiche.salvaBpm(result.value)
                    }
                }
                "SPO2" -> {
                    if (result.value > 0) {
                        lastSpO2 = "${result.value} %"
                        tvSpO2Value.text = lastSpO2
                        gestoreStatistiche.salvaO2(result.value)
                    }
                }
                "BP" -> {
                    if (result.sys > 0) {
                        lastBP = "${result.sys} / ${result.dia}"
                        tvBpValue.text = lastBP
                        gestoreStatistiche.salvaPressione(result.sys, result.dia)
                    }
                }
                // MODIFICA: Ricezione ed interpretazione dei codici esito del firmware hardware
                "CALIBRATION_RESULT" -> {
                    when (result.calibrationStatus) {
                        0 -> Toast.makeText(context, "Calibrazione completata con successo!", Toast.LENGTH_LONG).show()
                        1 -> Toast.makeText(context, "Errore: Valori fuori scala o non validi", Toast.LENGTH_SHORT).show()
                        2 -> Toast.makeText(context, "Calibrazione rifiutata: Avvia prima la misurazione Pressione", Toast.LENGTH_LONG).show()
                        else -> Toast.makeText(context, "Errore di calibrazione sconosciuto", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onError(msg: String) {
        activity?.runOnUiThread {
            if (isAdded) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // =====================================================================================
    // CICLO DI VITA E AGGANCIO COMPONENTI ASINCRONI
    // =====================================================================================
    override fun onResume() {
        super.onResume()

        // Riagganciamo l'Observer del Machine Learning all'attivazione del frammento
        MotionSessionManager.addObserver(this)
        renderMotionState(MotionSessionManager.getState())

        val activeInstance = SmartRingManager.Companion.getActiveInstance()
        if (activeInstance != null) {
            ringManager = activeInstance
            ringManager?.updateListener(this)

            if (ringManager?.isConnected() == false) {
                isMeasuringHRGlobal = false
                btnToggleHR.text = "START BPM"
                tvBpmValue.text = lastBpm
            }
        }
    }

    override fun onPause() {
        super.onPause()

        // Rimuoviamo il listener ML e azzeriamo le callback grafiche BLE per prevenire Memory Leak
        MotionSessionManager.removeObserver(this)

        ringManager?.updateListener(object : SmartRingManager.SmartRingListener {
            override fun onConnected() {}
            override fun onDisconnected() {}
            override fun onDataReceived(result: Decoder.DecodedResult) {}
            override fun onError(msg: String) {}
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        MotionSessionManager.removeObserver(this)
    }

    // =====================================================================================
    // ANIMATION LOGIC
    // =====================================================================================
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