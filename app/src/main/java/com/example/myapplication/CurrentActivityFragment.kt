package com.example.myapplication

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
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
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CurrentActivityFragment : Fragment() {

    private val TAG = "CurrentActivityFragment"

    companion object {
        // stato globale condiviso tra istanze del fragment
        var isRingConnectedGlobal: Boolean = false
        var isMeasuringHRGlobal: Boolean = false
        val bpmHistory: MutableList<Float> = mutableListOf()
    }

    // ── Views: activity card ──────────────────────────────────────────────────
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

    // ── Views: heart + health ────────────────────────────────────────────────
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

    // ── BLE ───────────────────────────────────────────────────────────────────
    private var bleService: BLE? = null
    private var isConnected = false
    private var connecting = false
    private val handler = Handler(Looper.getMainLooper())

    private val RING_MAC = "FE:1C:6D:14:03:0B"

    // ── Stato misura corrente ────────────────────────────────────────────────
    private enum class MeasureMode { NONE, HR, SPO2, BP }
    private var currentMode: MeasureMode = MeasureMode.NONE

    // Animazione battito
    private var heartbeatAnimator: AnimatorSet? = null

    private val placeholderActivity = ActivityData(
        label = "Walking",
        iconRes = R.drawable.ic_activity_walking,
        confidence = 87
    )


    // ── BroadcastReceiver BLE ────────────────────────────────────────────────
    private val bleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "BLE_DATA_RX" -> {
                    val raw = intent.getStringExtra("data") ?: return
                    Log.d(TAG, "BLE_DATA_RX: $raw")
                    if (raw.startsWith("RX: ")) {
                        val hex = raw.removePrefix("RX: ")
                        handleDecodedData(hex)
                    }
                }
                "BLE_STATUS_UPDATE" -> {
                    val status = intent.getStringExtra("status") ?: "DISCONNESSO"
                    Log.d(TAG, "BLE_STATUS_UPDATE: $status")
                    when (status) {
                        "CONNESSO" -> onBleConnected()
                        "DISCONNESSO" -> onBleDisconnected()
                    }
                }
            }
        }
    }

    // ── ServiceConnection ─────────────────────────────────────────────────────
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "Service connected")
            bleService = (binder as BLE.LocalBinder).getService()
            val ok = bleService?.initialize() ?: false
            Log.d(TAG, "BLE initialize() = $ok")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            bleService = null
        }
    }

    // ── Permission launcher ───────────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val allGranted = perms.values.all { it }
        Log.d(TAG, "Permissions result: $perms, allGranted=$allGranted")
        if (allGranted) connectRing()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.prova, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated")

        bindViews(view)
        applyActivityData(placeholderActivity)
        startPulseAnimation()
        startStatusDotBlink()

        val ctx = requireActivity()
        ctx.bindService(
            Intent(ctx, BLE::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        // ripristina UI in base allo stato globale
        if (isRingConnectedGlobal) {
            isConnected = true
            setHeartConnectedUI()
            if (isMeasuringHRGlobal) {
                currentMode = MeasureMode.HR
                btnToggleHR.text = "Stop BPM"
            } else {
                currentMode = MeasureMode.NONE
                btnToggleHR.text = "Start BPM"
            }
        } else {
            isConnected = false
            setHeartDisconnectedUI()
        }

        btnConnectRing.setOnClickListener {
            when {
                connecting -> {
                    Log.d(TAG, "Cancel connection pressed")
                    connecting = false
                    tvStatusLabel.text = "In attesa dispositivo"
                    tvBleStatusLabel.text = "Non connesso"
                    btnConnectRing.text = "Connetti Ring"
                    setDotColor(R.color.text_muted)
                }
                isConnected -> {
                    Log.d(TAG, "Disconnect button pressed")
                    disconnectRing()
                }
                else -> {
                    Log.d(TAG, "Connect button pressed")
                    checkPermissionsAndConnect()
                }
            }
        }

        btnToggleHR.setOnClickListener {
            if (!isConnected) return@setOnClickListener
            if (currentMode == MeasureMode.HR) {
                Log.d(TAG, "Stop HR from button")
                stopAllSensors()
                currentMode = MeasureMode.NONE
                isMeasuringHRGlobal = false
                btnToggleHR.text = "Start BPM"
            } else {
                Log.d(TAG, "Start HR from button")
                stopAllSensors()
                handler.postDelayed({
                    startHR()
                    currentMode = MeasureMode.HR
                    isMeasuringHRGlobal = true
                    btnToggleHR.text = "Stop BPM"
                }, 1200)
            }
        }

        btnStartSpO2.setOnClickListener {
            if (!isConnected) return@setOnClickListener
            Log.d(TAG, "Start SpO2 button")
            startSpO2()
        }

        btnStartBP.setOnClickListener {
            if (!isConnected) return@setOnClickListener
            Log.d(TAG, "Start BP button")
            startBP()
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart -> registerReceiver")
        val filter = IntentFilter().apply {
            addAction("BLE_DATA_RX")
            addAction("BLE_STATUS_UPDATE")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(bleReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            ContextCompat.registerReceiver(
                requireContext(),
                bleReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop -> unregisterReceiver")
        try {
            requireContext().unregisterReceiver(bleReceiver)
        } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        Log.d(TAG, "onDestroyView")
        heartbeatAnimator?.cancel()
        // niente stopAllSensors e niente unbindService, così il Service continua
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    // ── BLE Logic ─────────────────────────────────────────────────────────────

    private fun checkPermissionsAndConnect() {
        Log.d(TAG, "checkPermissionsAndConnect()")
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
            connectRing()
        } else {
            Log.d(TAG, "Missing permissions: $missing")
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectRing() {
        Log.d(TAG, "Tentativo connessione a $RING_MAC")
        connecting = true
        setDotColor(R.color.accent_orange)
        tvBleStatusLabel.text = "Connessione..."
        tvStatusLabel.text = "Connessione al ring..."
        btnConnectRing.text = "Annulla"
        bleService?.connect(RING_MAC)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectRing() {
        Log.d(TAG, "disconnectRing()")
        isConnected = false
        isRingConnectedGlobal = false
        connecting = false
        isMeasuringHRGlobal = false
        stopAllSensors()
        handler.postDelayed({
            bleService?.disconnectDevice()
        }, 1200)
        setHeartDisconnectedUI()
    }

    @SuppressLint("MissingPermission")
    private fun startHR() {
        Log.d(TAG, "startHR() -> invio sequenza BPM")
        bleService?.sendCommand(
            0x03.toByte(),
            0x0C.toByte(),
            byteArrayOf(0x01, 0x01),
            "Stream ON"
        )
        handler.postDelayed({
            bleService?.sendCommand(
                0x03.toByte(),
                0x0B.toByte(),
                byteArrayOf(0x02, 0x00),
                "Workout Mode"
            )
        }, 500)
        handler.postDelayed({
            bleService?.sendCommand(
                0x03.toByte(),
                0x09.toByte(),
                byteArrayOf(0x01, 0x01, 0x01),
                "Start BPM"
            )
        }, 1000)
    }

    @SuppressLint("MissingPermission")
    private fun startSpO2() {
        Log.d(TAG, "startSpO2()")
        stopAllSensors()
        currentMode = MeasureMode.SPO2
        isMeasuringHRGlobal = false
        tvSpO2Value.text = "-- %"
        tvBpValue.text = "-- / --"
        btnToggleHR.text = "Start BPM"

        bleService?.sendCommand(
            0x03.toByte(), 0x0C.toByte(),
            byteArrayOf(0x01, 0x01), "Stream ON SpO2"
        )
        handler.postDelayed({
            bleService?.sendCommand(
                0x03.toByte(), 0x2F.toByte(),
                byteArrayOf(0x01, 0x02), "Start SpO2"
            )
        }, 600)
    }

    @SuppressLint("MissingPermission")
    private fun startBP() {
        Log.d(TAG, "startBP()")
        stopAllSensors()
        currentMode = MeasureMode.BP
        isMeasuringHRGlobal = false
        tvBpValue.text = "-- / --"
        tvSpO2Value.text = "-- %"
        btnToggleHR.text = "Start BPM"

        bleService?.sendCommand(
            0x03.toByte(), 0x0C.toByte(),
            byteArrayOf(0x01, 0x01), "Stream ON BP"
        )
        handler.postDelayed({
            bleService?.sendCommand(
                0x03.toByte(), 0x2F.toByte(),
                byteArrayOf(0x01, 0x01), "Start BP"
            )
        }, 600)
    }

    @SuppressLint("MissingPermission")
    private fun stopAllSensors() {
        Log.d(TAG, "stopAllSensors()")
        currentMode = MeasureMode.NONE
        bleService?.sendCommand(
            0x03.toByte(),
            0x09.toByte(),
            byteArrayOf(0x00, 0x01, 0x01),
            "Stop BPM"
        )
        handler.postDelayed({
            bleService?.sendCommand(
                0x03.toByte(),
                0x2F.toByte(),
                byteArrayOf(0x00, 0x02),
                "Stop SpO2"
            )
        }, 300)
        handler.postDelayed({
            bleService?.sendCommand(
                0x03.toByte(),
                0x2F.toByte(),
                byteArrayOf(0x00, 0x01),
                "Stop BP"
            )
        }, 500)
        handler.postDelayed({
            bleService?.sendCommand(
                0x03.toByte(),
                0x0B.toByte(),
                byteArrayOf(0x00, 0x00),
                "Idle Mode"
            )
        }, 800)
        handler.postDelayed({
            bleService?.sendCommand(
                0x03.toByte(),
                0x0C.toByte(),
                byteArrayOf(0x00, 0x01),
                "Stream OFF"
            )
        }, 1100)
    }

    @SuppressLint("MissingPermission")
    private fun syncTimeAndUser() {
        Log.d(TAG, "syncTimeAndUser()")
        val c = Calendar.getInstance()
        val timePayload = byteArrayOf(
            (c.get(Calendar.YEAR) - 2000).toByte(),
            (c.get(Calendar.MONTH) + 1).toByte(),
            c.get(Calendar.DAY_OF_MONTH).toByte(),
            c.get(Calendar.HOUR_OF_DAY).toByte(),
            c.get(Calendar.MINUTE).toByte(),
            c.get(Calendar.SECOND).toByte()
        )
        bleService?.sendCommand(
            0x01.toByte(),
            0x00.toByte(),
            timePayload,
            "Sync Time"
        )
        handler.postDelayed({
            bleService?.sendCommand(
                0x01.toByte(),
                0x03.toByte(),
                byteArrayOf(0xAF.toByte(), 0x4B, 0x00, 0x1E),
                "Init User"
            )
        }, 600)
    }

    // ── Gestione pacchetti dati ───────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun handleDecodedData(hex: String) {
        Log.d(TAG, "handleDecodedData(hex=$hex)")
        Decoder.decode(hex)?.let { result ->
            Log.d(
                TAG,
                "Decoded: type=${result.type}, value=${result.value}, sys=${result.sys}, dia=${result.dia}"
            )
            when (result.type) {
                "BPM" -> {
                    if (result.value > 0) updateBpm(result.value)
                }

                "SPO2" -> {
                    tvSpO2Value.text = "${result.value} %"
                    bleService?.sendCommand(
                        0x03.toByte(), 0x2F.toByte(),
                        byteArrayOf(0x00, 0x02), "Auto-Stop SpO2"
                    )
                    currentMode = MeasureMode.NONE
                    isMeasuringHRGlobal = false
                }

                "BP" -> {
                    val sys = result.sys
                    val dia = result.dia
                    tvBpValue.text = "$sys / $dia"
                    bleService?.sendCommand(
                        0x03.toByte(), 0x2F.toByte(),
                        byteArrayOf(0x00, 0x01), "Auto-Stop BP"
                    )
                    currentMode = MeasureMode.NONE
                    isMeasuringHRGlobal = false
                }

                "END_ACK" -> {
                    Log.d(TAG, "Ricevuto END_ACK, invio ACK 0x04/0x0E")
                    bleService?.sendCommand(
                        0x04.toByte(),
                        0x0E.toByte(),
                        byteArrayOf(0x00),
                        "ACK Fine Misura"
                    )
                }

                "WAIT" -> {
                    if (tvSpO2Value.text == "-- %") tvSpO2Value.text = "..."
                    if (tvBpValue.text == "-- / --") tvBpValue.text = "..."
                }
            }
        }
    }

    // ── Callback stato BLE ───────────────────────────────────────────────────

    private fun onBleConnected() {
        Log.d(TAG, "onBleConnected()")
        isConnected = true
        isRingConnectedGlobal = true
        connecting = false
        setHeartConnectedUI()
        tvLastReading.text = "Dispositivo collegato"
        tvBpmValue.text = "--"
        tvSpO2Value.text = "-- %"
        tvBpValue.text = "-- / --"

        syncTimeAndUser()
    }

    private fun onBleDisconnected() {
        Log.d(TAG, "onBleDisconnected()")
        isConnected = false
        isRingConnectedGlobal = false
        isMeasuringHRGlobal = false
        setHeartDisconnectedUI()
    }

    private fun updateBpm(bpm: Int) {
        Log.d(TAG, "updateBpm($bpm)")
        tvBpmValue.text = bpm.toString()

        // aggiorna history (max 100 punti)
        bpmHistory.add(bpm.toFloat())
        if (bpmHistory.size > 100) {
            bpmHistory.removeAt(0)
        }

        // se il ChartsFragment è attivo, aggiorna il grafico in tempo reale
        val chartsFragment =
            parentFragmentManager.findFragmentByTag("charts") as? ChartsFragment
        chartsFragment?.updateHeartRateChart(bpmHistory, bpm)

        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvLastReading.text = "Ultima lettura: $time"
        startHeartbeatAnimation()
    }
    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun setHeartConnectedUI() {
        setDotColor(R.color.accent_teal)
        tvBleStatusLabel.text = "Connesso"
        tvStatusLabel.text = "Ring connesso"
        btnConnectRing.text = "Disconnetti"
    }

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

    // ── Activity data (ML) ────────────────────────────────────────────────────

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

    // ── Animazioni ────────────────────────────────────────────────────────────

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
        val alpha = ObjectAnimator.ofFloat(view, "alpha", view.alpha, view.alpha * 0.4f, view.alpha)
            .apply {
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

// Data class per output modello ML
data class ActivityData(
    val label: String,
    val iconRes: Int,
    val confidence: Int
)