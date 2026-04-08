package com.example.myapplication

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CurrentActivityFragment : Fragment(), SmartRingBleManager.RingCallback {

    private val TAG = "CurrentActivityFragment"

    // UI
    private lateinit var pulseRing1: View
    private lateinit var pulseRing2: View
    private lateinit var pulseRing3: View
    private lateinit var statusDot: View
    private lateinit var tvStatusLabel: TextView
    private lateinit var tvActivityLabel: TextView
    private lateinit var tvConfidenceValue: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvSteps: TextView
    private lateinit var tvCalories: TextView
    private lateinit var ivActivityIcon: ImageView
    private lateinit var progressConfidence: ProgressBar
    private lateinit var cardCurrentActivity: View

    private lateinit var dotBleStatus: View
    private lateinit var tvBleStatusLabel: TextView
    private lateinit var tvBpmValue: TextView
    private lateinit var tvLastReading: TextView
    private lateinit var ivHeartIcon: TextView
    private lateinit var btnConnectRing: Button
    private lateinit var btnToggleHR: Button
    private lateinit var tvSpO2Value: TextView
    private lateinit var tvBpValue: TextView
    private lateinit var btnStartSpO2: Button
    private lateinit var btnStartBP: Button

    private val handler = Handler(Looper.getMainLooper())

    private var isConnected = false
    private var connecting = false

    private enum class MeasureMode { NONE, HR, SPO2, BP }
    private var currentMode: MeasureMode = MeasureMode.NONE

    private var heartbeatAnimator: AnimatorSet? = null

    private val placeholderActivity = ActivityData(
        label = "Walking",
        iconRes = R.drawable.ic_activity_walking,
        confidence = 87
    )

    private var ringManager: SmartRingBleManager? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val allGranted = perms.values.all { it }
        if (allGranted) startScanAndConnect()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.prova, container, false)


    @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        applyActivityData(placeholderActivity)
        startPulseAnimation()
        startStatusDotBlink()
        setHeartDisconnectedUI()

        if (ringManager == null) {
            ringManager = SmartRingBleManager(requireContext().applicationContext)
        }
        ringManager?.setCallback(this)

        btnConnectRing.setOnClickListener  {
            when {
                connecting -> {
                    connecting = false
                    tvStatusLabel.text = "In attesa dispositivo"
                    tvBleStatusLabel.text = "Non connesso"
                    btnConnectRing.text = "Connetti Ring"
                    ringManager?.stopAllMeasurements()
                }
                isConnected -> {
                    ringManager?.stopAllMeasurements()
                    ringManager?.disconnect()
                    setHeartDisconnectedUI()
                }
                else -> {
                    checkPermissionsAndConnect()
                }
            }
        }

        btnToggleHR.setOnClickListener {
            if (!isConnected) return@setOnClickListener
            if (currentMode == MeasureMode.HR) {
                ringManager?.stopAllMeasurements()
                currentMode = MeasureMode.NONE
                btnToggleHR.text = "Start BPM"
            } else {
                ringManager?.stopAllMeasurements()
                ringManager?.startHeartRate()
                currentMode = MeasureMode.HR
                btnToggleHR.text = "Stop BPM"
            }
        }

        btnStartSpO2.setOnClickListener {
            if (!isConnected) return@setOnClickListener
            currentMode = MeasureMode.SPO2
            tvSpO2Value.text = "-- %"
            tvBpValue.text = "-- / --"
            btnToggleHR.text = "Start BPM"
            ringManager?.stopAllMeasurements()
            // SE hai un comando specifico SpO2 nel protocollo,
            // qui lo richiami; altrimenti lo gestisci con una buildPacket dedicata.
        }

        btnStartBP.setOnClickListener {
            if (!isConnected) return@setOnClickListener
            currentMode = MeasureMode.BP
            tvBpValue.text = "-- / --"
            tvSpO2Value.text = "-- %"
            btnToggleHR.text = "Start BPM"
            ringManager?.stopAllMeasurements()
            ringManager?.startBloodPressure()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        heartbeatAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
        // IMPORTANTE: NON chiamiamo disconnect qui
    }

    // ---- SmartRingBleManager.RingCallback ----------------------------------

    override fun onConnected() {
        isConnected = true
        connecting = false
        setDotColor(R.color.accent_teal)
        tvBleStatusLabel.text = "Connesso"
        tvStatusLabel.text = "Ring connesso"
        btnConnectRing.text = "Disconnetti"
        tvBpmValue.text = "--"
        tvSpO2Value.text = "-- %"
        tvBpValue.text = "-- / --"
    }

    override fun onDisconnected() {
        isConnected = false
        connecting = false
        setHeartDisconnectedUI()
    }

    override fun onHeartRateReceived(bpm: Int) {
        tvBpmValue.text = bpm.toString()
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvLastReading.text = "Ultima lettura: $time"
        startHeartbeatAnimation()
    }

    override fun onBloodPressureReceived(systolic: Int, diastolic: Int) {
        tvBpValue.text = "$systolic / $diastolic"
    }

    override fun onDeviceFound(device: BluetoothDevice, rssi: Int) {
        // Qui puoi filtrare per nome/MAC e connetterti subito
        ringManager?.connect(device)
        connecting = true
        tvStatusLabel.text = "Connessione al ring..."
        tvBleStatusLabel.text = "Connessione..."
        btnConnectRing.text = "Annulla"
    }

    // ---- Permessi & connessione --------------------------------------------

    private fun checkPermissionsAndConnect() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startScanAndConnect()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanAndConnect() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            tvStatusLabel.text = "Bluetooth disabilitato"
            return
        }
        setDotColor(R.color.accent_orange)
        tvStatusLabel.text = "Ricerca ring..."
        tvBleStatusLabel.text = "Scanning..."
        btnConnectRing.text = "Annulla"
        connecting = true
        ringManager?.startScan()
    }

    // ---- Helpers UI --------------------------------------------------------

    private fun setHeartDisconnectedUI() {
        setDotColor(R.color.text_muted)
        tvBleStatusLabel.text = "Non connesso"
        tvStatusLabel.text = "In attesa dispositivo"
        btnConnectRing.text = "Connetti Ring"
        btnToggleHR.text = "Start BPM"
        tvBpmValue.text = "--"
        tvLastReading.text = "Nessuna lettura disponibile"
        tvSpO2Value.text = "-- %"
        tvBpValue.text = "-- / --"
        stopHeartbeatAnimation()
    }

    private fun setDotColor(colorRes: Int) {
        dotBleStatus.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), colorRes))
    }

    private fun bindViews(view: View) {
        pulseRing1 = view.findViewById(R.id.pulseRing1)
        pulseRing2 = view.findViewById(R.id.pulseRing2)
        pulseRing3 = view.findViewById(R.id.pulseRing3)
        statusDot = view.findViewById(R.id.statusDot)
        tvStatusLabel = view.findViewById(R.id.tvStatusLabel)
        tvActivityLabel = view.findViewById(R.id.tvActivityLabel)
        tvConfidenceValue = view.findViewById(R.id.tvConfidenceValue)
        tvDuration = view.findViewById(R.id.tvDuration)
        tvSteps = view.findViewById(R.id.tvSteps)
        tvCalories = view.findViewById(R.id.tvCalories)
        ivActivityIcon = view.findViewById(R.id.ivActivityIcon)
        progressConfidence = view.findViewById(R.id.progressConfidence)
        cardCurrentActivity = view.findViewById(R.id.cardCurrentActivity)

        dotBleStatus = view.findViewById(R.id.dotBleStatus)
        tvBleStatusLabel = view.findViewById(R.id.tvBleStatusLabel)
        tvBpmValue = view.findViewById(R.id.tvBpmValue)
        tvLastReading = view.findViewById(R.id.tvLastReading)
        ivHeartIcon = view.findViewById(R.id.ivHeartIcon)
        btnConnectRing = view.findViewById(R.id.btnConnectRing)
        btnToggleHR = view.findViewById(R.id.btnToggleHR)
        tvSpO2Value = view.findViewById(R.id.tvSpO2Value)
        tvBpValue = view.findViewById(R.id.tvBpValue)
        btnStartSpO2 = view.findViewById(R.id.btnStartSpO2)
        btnStartBP = view.findViewById(R.id.btnStartBP)
    }

    // ---- Activity recognition card ----------------------------------------

    fun updateActivityUI(data: ActivityData) {
        val fadeOut = ObjectAnimator.ofFloat(cardCurrentActivity, "alpha", 1f, 0f)
            .apply { duration = 200 }
        fadeOut.addUpdateListener {
            if (it.animatedFraction == 1f) {
                applyActivityData(data)
                ObjectAnimator.ofFloat(cardCurrentActivity, "alpha", 0f, 1f)
                    .apply { duration = 300 }
                    .start()
            }
        }
        fadeOut.start()
    }

    private fun applyActivityData(data: ActivityData) {
        tvActivityLabel.text = data.label
        tvConfidenceValue.text = "${data.confidence}%"
        ivActivityIcon.setImageResource(data.iconRes)
        tvDuration.text = "0m 00s"
        tvSteps.text = "0"
        tvCalories.text = "0"
        ObjectAnimator.ofInt(progressConfidence, "progress", 0, data.confidence).apply {
            duration = 800
            interpolator = AccelerateDecelerateInterpolator()
        }.start()
    }

    // ---- Animazioni --------------------------------------------------------

    private fun startHeartbeatAnimation() {
        heartbeatAnimator?.cancel()
        val scaleX = ObjectAnimator.ofFloat(ivHeartIcon, "scaleX", 1f, 1.18f, 1f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(ivHeartIcon, "scaleY", 1f, 1.18f, 1f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        heartbeatAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            start()
        }
    }

    private fun stopHeartbeatAnimation() {
        heartbeatAnimator?.cancel()
        heartbeatAnimator = null
        if (::ivHeartIcon.isInitialized) {
            ivHeartIcon.scaleX = 1f
            ivHeartIcon.scaleY = 1f
        }
    }

    private fun startPulseAnimation() {
        animatePulseRing(pulseRing3, 2200L, 0L)
        animatePulseRing(pulseRing2, 2200L, 400L)
        animateInnerRingBreath(pulseRing1)
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
        val alpha = ObjectAnimator.ofFloat(view, "alpha", view.alpha, view.alpha * 0.4f, view.alpha).apply {
            duration = durationMs
            startDelay = delay
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            start()
        }
    }

    private fun animateInnerRingBreath(view: View) {
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.06f, 1f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.06f, 1f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            start()
        }
    }

    private fun startStatusDotBlink() {
        ObjectAnimator.ofFloat(statusDot, "alpha", 1f, 0.2f, 1f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }
}

data class ActivityData(
    val label: String,
    val iconRes: Int,
    val confidence: Int
)