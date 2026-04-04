package com.example.myapplication

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CurrentActivityFragment : Fragment(), SmartRingBleManager.RingCallback {

    // Views activity card
    private lateinit var pulseRing1: View
    private lateinit var pulseRing2: View
    private lateinit var pulseRing3: View
    private lateinit var statusDot: View
    private lateinit var tvActivityLabel: TextView
    private lateinit var tvConfidenceValue: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvSteps: TextView
    private lateinit var tvCalories: TextView
    private lateinit var ivActivityIcon: ImageView
    private lateinit var progressConfidence: ProgressBar
    private lateinit var cardCurrentActivity: View

    // Views heart rate card
    private lateinit var dotBleStatus: View
    private lateinit var tvBleStatusLabel: TextView
    private lateinit var tvBpmValue: TextView
    private lateinit var tvLastReading: TextView
    private lateinit var ivHeartIcon: TextView
    private lateinit var btnConnectRing: Button

    // BLE
    private lateinit var bleManager: SmartRingBleManager
    private var isConnected = false
    private var isConnecting = false
    private var hasConnectedToDevice = false

    // Animations
    private var heartbeatAnimator: AnimatorSet? = null

    private val placeholderActivity = ActivityData(
        label = "Walking",
        iconRes = R.drawable.ic_activity_walking,
        confidence = 87
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startBleFlow()
        } else {
            Toast.makeText(
                requireContext(),
                "Permessi Bluetooth necessari per connettere il ring",
                Toast.LENGTH_LONG
            ).show()
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

        bleManager = SmartRingBleManager(requireContext())
        bleManager.setCallback(this)

        bindViews(view)
        applyActivityData(placeholderActivity)
        startPulseAnimation()
        startStatusDotBlink()
        setHeartDisconnectedUI()

        btnConnectRing.setOnClickListener {
            if (isConnected || isConnecting) {
                disconnectRing()
            } else {
                checkPermissionsAndConnect()
            }
        }
    }

    private fun bindViews(view: View) {
        pulseRing1 = view.findViewById(R.id.pulseRing1)
        pulseRing2 = view.findViewById(R.id.pulseRing2)
        pulseRing3 = view.findViewById(R.id.pulseRing3)
        statusDot = view.findViewById(R.id.statusDot)
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
    }

    private fun checkPermissionsAndConnect() {
        val neededPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missingPermissions = neededPermissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startBleFlow()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleFlow() {
        hasConnectedToDevice = false
        isConnecting = true
        updateConnectingUI()

        // Controlla prima tra i dispositivi già accoppiati
        val bluetoothManager = requireContext()
            .getSystemService(android.content.Context.BLUETOOTH_SERVICE)
                as android.bluetooth.BluetoothManager
        val adapter = bluetoothManager.adapter

        val bondedRing = adapter?.bondedDevices?.firstOrNull { device ->
            // Adatta il filtro al nome del tuo ring
            device.name?.contains("ring", ignoreCase = true) == true ||
                    device.name?.contains("R0", ignoreCase = true) == true
        }

        if (bondedRing != null) {
            // Ring già accoppiato: connetti direttamente senza scan
            hasConnectedToDevice = true
            tvBleStatusLabel.text = "Riconnessione a ${bondedRing.name}..."
            bleManager.connect(bondedRing)
        } else {
            // Ring non trovato tra i bonded: avvia la scansione normale
            bleManager.startScan()
            Toast.makeText(requireContext(), "Ricerca ring in corso...", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectRing() {
        isConnecting = false
        hasConnectedToDevice = false
        bleManager.stopScan()      // ferma la scansione BLE
        bleManager.disconnect()    // chiude l'eventuale connessione GATT
        setHeartDisconnectedUI()
    }

    @SuppressLint("MissingPermission")
    override fun onConnected() {
        activity?.runOnUiThread {
            isConnected = true
            isConnecting = false
            setDotColor(R.color.accent_teal)
            tvBleStatusLabel.text = "Connesso"
            btnConnectRing.text = "Disconnetti"
            tvLastReading.text = "Dispositivo collegato"

            try {
                bleManager.startHeartRate()
            } catch (_: Exception) {
            }
        }
    }
    override fun onDisconnected() {
        activity?.runOnUiThread {
            isConnected = false
            isConnecting = false
            setHeartDisconnectedUI()
        }
    }

    override fun onHeartRateReceived(bpm: Int) {
        activity?.runOnUiThread {
            tvBpmValue.text = bpm.toString()
            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvLastReading.text = "Ultima lettura: $currentTime"
            startHeartbeatAnimation()
        }
    }

    override fun onBloodPressureReceived(systolic: Int, diastolic: Int) {
        // Lasciato vuoto per ora
    }

    @SuppressLint("MissingPermission")
    override fun onDeviceFound(device: BluetoothDevice, rssi: Int) {
        activity?.runOnUiThread {
            if (hasConnectedToDevice) return@runOnUiThread

            hasConnectedToDevice = true
            tvBleStatusLabel.text = "Connessione a ${device.name ?: "dispositivo"}..."
            bleManager.connect(device)
        }
    }

    private fun setHeartDisconnectedUI() {
        setDotColor(R.color.text_muted)
        tvBleStatusLabel.text = "Non connesso"
        btnConnectRing.text = "Connetti Ring"
        tvBpmValue.text = "--"
        tvLastReading.text = "Nessuna lettura disponibile"
        stopHeartbeatAnimation()
    }

    private fun updateConnectingUI() {
        setDotColor(R.color.accent_orange)
        tvBleStatusLabel.text = "Ricerca dispositivo..."
        btnConnectRing.text = "Annulla"
        tvBpmValue.text = "--"
        tvLastReading.text = "Scansione BLE in corso"
    }

    private fun setDotColor(colorRes: Int) {
        dotBleStatus.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), colorRes))
    }

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
        ivHeartIcon.scaleX = 1f
        ivHeartIcon.scaleY = 1f
    }

    fun updateActivityUI(data: ActivityData) {
        val fadeOut = ObjectAnimator.ofFloat(cardCurrentActivity, "alpha", 1f, 0f).apply {
            duration = 200
        }

        fadeOut.addUpdateListener {
            if (it.animatedFraction == 1f) {
                applyActivityData(data)
                ObjectAnimator.ofFloat(cardCurrentActivity, "alpha", 0f, 1f).apply {
                    duration = 300
                }.start()
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

        val alpha = ObjectAnimator.ofFloat(
            view,
            "alpha",
            view.alpha,
            view.alpha * 0.4f,
            view.alpha
        ).apply {
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

    @SuppressLint("MissingPermission")
    override fun onDestroyView() {
        heartbeatAnimator?.cancel()
        if (::bleManager.isInitialized) {
            bleManager.disconnect()
        }
        super.onDestroyView()
    }
}

data class ActivityData(
    val label: String,
    val iconRes: Int,
    val confidence: Int
)