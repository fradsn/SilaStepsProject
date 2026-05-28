package com.example.myapplication.UI

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import com.example.myapplication.BT.ring.BLE
import com.example.myapplication.BT.ring.Decoder
import com.example.myapplication.Motion.session.MotionSessionManager
import com.example.myapplication.Motion.session.MotionUiState
import com.example.myapplication.R

class CurrentActivityFragment : Fragment(), MotionSessionManager.Observer {

    companion object {
        var isMeasuringHRGlobal: Boolean = false
        val bpmHistory: MutableList<Float> = mutableListOf()

        var lastBpm: String = "--"
        var lastSpO2: String = "-- %"
        var lastBP: String = "-- / --"
    }

    private lateinit var tvBpmValue: TextView
    private lateinit var tvSpO2Value: TextView
    private lateinit var tvBpValue: TextView
    private lateinit var tvLastReading: TextView
    private lateinit var btnToggleHR: Button
    private lateinit var btnStartSpO2: Button
    private lateinit var btnStartBP: Button

    private lateinit var tvActivityLabel: TextView
    private lateinit var tvConfidenceValue: TextView
    private lateinit var progressConfidence: ProgressBar
    private lateinit var tvDuration: TextView
    private lateinit var tvSteps: TextView
    private lateinit var tvCalories: TextView
    private lateinit var ivActivityIcon: ImageView

    private lateinit var pulseRing1: View
    private lateinit var pulseRing2: View
    private lateinit var pulseRing3: View

    private var bleService: BLE? = null
    private var isBleBound = false

    private var currentSessionStartMillis: Long = 0L
    private var motionStreaming: Boolean = false

    private val durationHandler = Handler(Looper.getMainLooper())
    private val durationRunnable = object : Runnable {
        override fun run() {
            if (!isAdded || !motionStreaming || currentSessionStartMillis <= 0L) return

            val elapsedMs = System.currentTimeMillis() - currentSessionStartMillis
            val totalSeconds = (elapsedMs / 1000L).toInt()
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60

            tvDuration.text = String.format("%dm %02ds", minutes, seconds)
            durationHandler.postDelayed(this, 1000L)
        }
    }

    private val bleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "BLE_DATA_RX" -> {
                    val raw = intent.getStringExtra("data") ?: return
                    if (raw.startsWith("RX: ")) {
                        handleDecodedData(raw.removePrefix("RX: "))
                    }
                }

                "BLE_STATUS_UPDATE" -> {
                    val status = intent.getStringExtra("status") ?: "DISCONNESSO"
                    if (status == "CONNESSO") onBleConnected() else onBleDisconnected()
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bleService = (binder as BLE.LocalBinder).getService()
            bleService?.initialize()
            isBleBound = true

            if (bleService?.isDeviceConnected() == true) {
                onBleConnected()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            isBleBound = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.prova, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        restoreSmartRingUi()
        startPulseAnimation()

        MotionSessionManager.initialize(requireContext())
        MotionSessionManager.addObserver(this)
        renderMotionState(MotionSessionManager.getState())

        requireActivity().bindService(
            Intent(requireActivity(), BLE::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        setupSmartRingButtons()
    }

    private fun bindViews(view: View) {
        tvActivityLabel = view.findViewById(R.id.tvActivityLabel)
        tvConfidenceValue = view.findViewById(R.id.tvConfidenceValue)
        progressConfidence = view.findViewById(R.id.progressConfidence)
        tvDuration = view.findViewById(R.id.tvDuration)
        tvSteps = view.findViewById(R.id.tvSteps)
        tvCalories = view.findViewById(R.id.tvCalories)
        ivActivityIcon = view.findViewById(R.id.ivActivityIcon)

        pulseRing1 = view.findViewById(R.id.pulseRing1)
        pulseRing2 = view.findViewById(R.id.pulseRing2)
        pulseRing3 = view.findViewById(R.id.pulseRing3)

        tvBpmValue = view.findViewById(R.id.tvBpmValue)
        tvSpO2Value = view.findViewById(R.id.tvSpO2Value)
        tvBpValue = view.findViewById(R.id.tvBpValue)
        tvLastReading = view.findViewById(R.id.tvLastReading)

        btnToggleHR = view.findViewById(R.id.btnToggleHR)
        btnStartSpO2 = view.findViewById(R.id.btnStartSpO2)
        btnStartBP = view.findViewById(R.id.btnStartBP)
    }

    private fun restoreSmartRingUi() {
        btnToggleHR.text = if (isMeasuringHRGlobal) "STOP BPM" else "START BPM"
        tvBpmValue.text = lastBpm
        tvSpO2Value.text = lastSpO2
        tvBpValue.text = lastBP

        tvLastReading.text =
            if (lastBpm == "--") "Nessuna lettura disponibile"
            else "Ultima lettura: $lastBpm BPM"
    }

    private fun setupSmartRingButtons() {
        btnToggleHR.setOnClickListener {
            val isConnected = bleService?.isDeviceConnected() ?: false

            if (isConnected) {
                if (isMeasuringHRGlobal) {
                    stopAllSensors()
                    isMeasuringHRGlobal = false
                    btnToggleHR.text = "START BPM"
                } else {
                    startHR()
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
            if (bleService?.isDeviceConnected() == true) startSpO2()
            else Toast.makeText(context, "Anello non connesso", Toast.LENGTH_SHORT).show()
        }

        btnStartBP.setOnClickListener {
            if (bleService?.isDeviceConnected() == true) startBP()
            else Toast.makeText(context, "Anello non connesso", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onMotionStateChanged(state: MotionUiState) {
        if (!isAdded) return
        renderMotionState(state)
    }

    private fun renderMotionState(state: MotionUiState) {
        tvActivityLabel.text = state.currentActivity
        tvConfidenceValue.text = "${state.confidencePercent}%"
        progressConfidence.progress = state.confidencePercent

        motionStreaming = state.streaming
        currentSessionStartMillis = state.sessionStartMillis

        if (motionStreaming && currentSessionStartMillis > 0L) {
            startDurationTimer()
        } else {
            stopDurationTimer()
            if (state.currentActivity == "Disconnected" || state.currentActivity == "Waiting...") {
                tvDuration.text = "0m 00s"
            }
        }

        tvSteps.text = "0"
        tvCalories.text = "0"
        setActivityIcon(state.currentActivity)
    }

    private fun handleDecodedData(hex: String) {
        Decoder.decode(hex)?.let { result ->
            when (result.type) {
                "BPM" -> {
                    if (result.value > 0) {
                        lastBpm = result.value.toString()
                        tvBpmValue.text = lastBpm
                        tvLastReading.text = "Ultima lettura: ${result.value} BPM"

                        bpmHistory.add(result.value.toFloat())
                        if (bpmHistory.size > 100) bpmHistory.removeAt(0)

                        (parentFragmentManager.findFragmentByTag("charts") as? ChartsFragment)
                            ?.updateHeartRateChart(bpmHistory, result.value)
                    }
                }

                "SPO2" -> {
                    lastSpO2 = "${result.value} %"
                    tvSpO2Value.text = lastSpO2
                }

                "BP" -> {
                    lastBP = "${result.sys} / ${result.dia}"
                    tvBpValue.text = lastBP
                }
            }
        }
    }

    private fun startHR() {
        bleService?.sendCommand(
            0x03.toByte(),
            0x0C.toByte(),
            byteArrayOf(0x01, 0x01),
            "Stream ON"
        )

        Handler(Looper.getMainLooper()).postDelayed({
            bleService?.sendCommand(
                0x03.toByte(),
                0x09.toByte(),
                byteArrayOf(0x01, 0x01, 0x01),
                "Start BPM"
            )
        }, 600)
    }

    private fun stopAllSensors() {
        bleService?.sendCommand(
            0x03.toByte(),
            0x09.toByte(),
            byteArrayOf(0x00, 0x01, 0x01),
            "Stop BPM"
        )

        Handler(Looper.getMainLooper()).postDelayed({
            bleService?.sendCommand(
                0x03.toByte(),
                0x0C.toByte(),
                byteArrayOf(0x00, 0x01),
                "Stream OFF"
            )
        }, 600)
    }

    private fun startSpO2() {
        bleService?.sendCommand(
            0x03.toByte(),
            0x0C.toByte(),
            byteArrayOf(0x01, 0x01),
            "Stream ON SpO2"
        )

        Handler(Looper.getMainLooper()).postDelayed({
            bleService?.sendCommand(
                0x03.toByte(),
                0x2F.toByte(),
                byteArrayOf(0x01, 0x02),
                "Start SpO2"
            )
        }, 600)
    }

    private fun startBP() {
        bleService?.sendCommand(
            0x03.toByte(),
            0x0C.toByte(),
            byteArrayOf(0x01, 0x01),
            "Stream ON BP"
        )

        Handler(Looper.getMainLooper()).postDelayed({
            bleService?.sendCommand(
                0x03.toByte(),
                0x2F.toByte(),
                byteArrayOf(0x01, 0x01),
                "Start BP"
            )
        }, 600)
    }

    private fun onBleConnected() {
    }

    private fun onBleDisconnected() {
        isMeasuringHRGlobal = false
        lastBpm = "--"
        btnToggleHR.text = "START BPM"
        tvBpmValue.text = lastBpm
        tvLastReading.text = "Nessuna lettura disponibile"
    }

    private fun startDurationTimer() {
        durationHandler.removeCallbacks(durationRunnable)
        durationHandler.post(durationRunnable)
    }

    private fun stopDurationTimer() {
        durationHandler.removeCallbacks(durationRunnable)
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

        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            start()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction("BLE_DATA_RX")
            addAction("BLE_STATUS_UPDATE")
        }

        requireContext().registerReceiver(
            bleReceiver,
            filter,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0
        )
    }

    override fun onStop() {
        super.onStop()
        try {
            requireContext().unregisterReceiver(bleReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        stopDurationTimer()
        MotionSessionManager.removeObserver(this)

        try {
            if (isBleBound) {
                requireActivity().unbindService(serviceConnection)
                isBleBound = false
            }
        } catch (_: Exception) {
        }

        bleService = null
    }
}