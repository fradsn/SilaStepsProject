package com.example.myapplication.UI

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.myapplication.BT.ring.Decoder
import com.example.myapplication.BT.ring.SmartRingManager
import com.example.myapplication.Motion.session.MotionSessionManager
import com.example.myapplication.Motion.session.MotionUiState
import com.example.myapplication.R
import com.example.myapplication.services.GestoreStatistiche
import com.example.myapplication.services.HealthMonitoringService
import com.google.android.material.switchmaterial.SwitchMaterial

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
    private lateinit var switchAutoMeasurement: SwitchMaterial

    private lateinit var ivHeartIcon: ImageView
    private lateinit var ivO2Icon: ImageView
    private lateinit var ivBpIcon: ImageView

    private lateinit var tvActivityLabel: TextView
    private lateinit var tvConfidenceValue: TextView
    private lateinit var progressConfidence: ProgressBar
    private lateinit var ivActivityIcon: ImageView

    private lateinit var pulseRing1: View
    private lateinit var pulseRing2: View
    private lateinit var pulseRing3: View

    private var ringManager: SmartRingManager? = null

    // Animation references
    private var bpmAnimation: Animation? = null
    private var o2Animation: Animation? = null
    private var bpAnimation: Animation? = null

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshUiFromDatabase()

            val activeType = ringManager?.getActiveMeasurementType()
            val isAutoActive = HealthMonitoringService.isAutoMeasuringActive

            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread

                // Heart Rate Management (BPM)
                if (activeType == "BPM") {
                    if (bpmAnimation == null) {
                        bpmAnimation = createPulseAnimation()
                        ivHeartIcon.startAnimation(bpmAnimation)
                    }
                    if (!isAutoActive) btnToggleHR.text = "STOP BPM"
                } else {
                    if (bpmAnimation != null) {
                        ivHeartIcon.clearAnimation()
                        bpmAnimation = null
                    }
                    if (!isAutoActive) btnToggleHR.text = "START BPM"
                }

                // Oxygen Management (O2)
                if (activeType == "O2") {
                    if (o2Animation == null) {
                        o2Animation = createPulseAnimation()
                        ivO2Icon.startAnimation(o2Animation)
                    }
                } else {
                    if (o2Animation != null) {
                        ivO2Icon.clearAnimation()
                        o2Animation = null
                    }
                }

                // Blood Pressure Management (PRESSURE)
                if (activeType == "PRESSURE") {
                    if (bpAnimation == null) {
                        bpAnimation = createPulseAnimation()
                        ivBpIcon.startAnimation(bpAnimation)
                    }
                } else {
                    if (bpAnimation != null) {
                        ivBpIcon.clearAnimation()
                        bpAnimation = null
                    }
                }
            }

            pollHandler.postDelayed(this, 1000)
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

        tvBpmValue.text = lastBpm
        tvSpO2Value.text = lastSpO2
        tvBpValue.text = lastBP

        handlePreExistingAnimations()

        MotionSessionManager.initialize(requireContext())
        MotionSessionManager.addObserver(this)
        renderMotionState(MotionSessionManager.getState())

        setupSmartRingButtons()

        // Load auto-measurement preference state
        val sharedPref = requireContext().getSharedPreferences("RingPrefs", Context.MODE_PRIVATE)
        val autoEnabled = sharedPref.getBoolean("auto_measurement_enabled", false)
        switchAutoMeasurement.isChecked = autoEnabled
        updateManualButtonsState(autoEnabled)

        switchAutoMeasurement.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                val isConnected = ringManager?.isConnected() == true
                if (!isConnected) {
                    buttonView.isChecked = false
                    Toast.makeText(context, "Smart Ring not connected", Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }

                sharedPref.edit().putBoolean("auto_measurement_enabled", true).apply()
                updateManualButtonsState(true)

                val serviceIntent = Intent(context, HealthMonitoringService::class.java).apply {
                    action = "START_AUTO_MEASUREMENT"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context?.startForegroundService(serviceIntent)
                } else {
                    context?.startService(serviceIntent)
                }
                Toast.makeText(context, "Auto-monitoring enabled", Toast.LENGTH_SHORT).show()
            } else {
                sharedPref.edit().putBoolean("auto_measurement_enabled", false).apply()
                updateManualButtonsState(false)

                val serviceIntent = Intent(context, HealthMonitoringService::class.java).apply {
                    action = "STOP_AUTO_MEASUREMENT"
                }
                context?.startService(serviceIntent)
                Toast.makeText(context, "Auto-monitoring disabled", Toast.LENGTH_SHORT).show()
            }
        }
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
        switchAutoMeasurement = view.findViewById(R.id.switchAutoMeasurement)

        ivHeartIcon = view.findViewById(R.id.ivHeartIcon)
        ivO2Icon = view.findViewById(R.id.ivO2Icon)
        ivBpIcon = view.findViewById(R.id.ivBpIcon)
    }

    private fun updateManualButtonsState(isAutoActive: Boolean) {
        activity?.runOnUiThread {
            if (isAutoActive) {
                btnToggleHR.isEnabled = false
                btnStartSpO2.isEnabled = false
                btnStartBP.isEnabled = false

                btnToggleHR.text = "AUTO"
                btnStartSpO2.text = "AUTO"
                btnStartBP.text = "AUTO"
            } else {
                btnToggleHR.isEnabled = true
                btnStartSpO2.isEnabled = true
                btnStartBP.isEnabled = true

                btnToggleHR.text = if (ringManager?.getActiveMeasurementType() == "BPM") "STOP BPM" else "START BPM"
                btnStartSpO2.text = "MEASURE O₂"
                btnStartBP.text = "MEASURE BP"
            }
        }
    }

    private fun createPulseAnimation(): Animation {
        return AlphaAnimation(1.0f, 0.25f).apply {
            duration = 750
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
    }

    private fun handlePreExistingAnimations() {
        val activeType = ringManager?.getActiveMeasurementType()
        if (activeType == "BPM") {
            bpmAnimation = createPulseAnimation()
            ivHeartIcon.startAnimation(bpmAnimation)
        } else if (activeType == "O2") {
            o2Animation = createPulseAnimation()
            ivO2Icon.startAnimation(o2Animation)
        } else if (activeType == "PRESSURE") {
            bpAnimation = createPulseAnimation()
            ivBpIcon.startAnimation(bpAnimation)
        }
    }

    private fun setupSmartRingButtons() {
        btnToggleHR.setOnClickListener {
            val isConnected = ringManager?.isConnected() == true
            if (isConnected) {
                if (ringManager?.getActiveMeasurementType() == "BPM") {
                    ringManager?.stopAllMeasurements()
                    btnToggleHR.text = "START BPM"
                    ivHeartIcon.clearAnimation()
                    bpmAnimation = null
                } else {
                    val serviceIntent = Intent(context, HealthMonitoringService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context?.startForegroundService(serviceIntent)
                    } else {
                        context?.startService(serviceIntent)
                    }

                    ringManager?.startHeartRateMeasurement()
                    btnToggleHR.text = "STOP BPM"

                    bpmAnimation = createPulseAnimation()
                    ivHeartIcon.startAnimation(bpmAnimation)
                }
            } else {
                Toast.makeText(context, "Pair device in profile tab first", Toast.LENGTH_SHORT).show()
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

                    o2Animation = createPulseAnimation()
                    ivO2Icon.startAnimation(o2Animation)
                } else {
                    Toast.makeText(context, "Device busy. Stop current activity.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Smart ring not connected", Toast.LENGTH_SHORT).show()
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

                    bpAnimation = createPulseAnimation()
                    ivBpIcon.startAnimation(bpAnimation)
                } else {
                    Toast.makeText(context, "Device busy. Stop current activity.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Smart ring not connected", Toast.LENGTH_SHORT).show()
            }
        }

        btnStartBP.setOnLongClickListener {
            // Safety lock check before allowing calibration shortcut
            if (HealthMonitoringService.isAutoMeasuringActive) {
                Toast.makeText(context, "Cannot calibrate during auto-monitoring", Toast.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }

            if (ringManager?.isConnected() == true) {
                showCalibrationDialog()
                true
            } else {
                Toast.makeText(context, "Smart ring not connected", Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    private fun showCalibrationDialog() {
        val context = context ?: return
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        builder.setTitle("Blood Pressure Calibration")
        builder.setMessage("Stay still. Start the measurement on the smart ring and enter the values just read from your upper arm blood pressure monitor below:")

        val linearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 24, 60, 24)
        }

        val etSystolic = EditText(context).apply {
            hint = "Systolic Pressure [60-250]"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(resources.getColor(R.color.text_primary))
            setHintTextColor(resources.getColor(R.color.text_secondary))
        }

        val etDiastolic = EditText(context).apply {
            hint = "Diastolic Pressure [40-150]"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(resources.getColor(R.color.text_primary))
            setHintTextColor(resources.getColor(R.color.text_secondary))
            setPadding(paddingLeft, 32, paddingRight, paddingBottom)
        }

        linearLayout.addView(etSystolic)
        linearLayout.addView(etDiastolic)
        builder.setView(linearLayout)

        builder.setPositiveButton("Calibrate") { dialog, _ ->
            val sysStr = etSystolic.text.toString()
            val diaStr = etDiastolic.text.toString()

            if (sysStr.isNotEmpty() && diaStr.isNotEmpty()) {
                val systolic = sysStr.toIntOrNull() ?: 0
                val diastolic = diaStr.toIntOrNull() ?: 0
                ringManager?.sendBloodPressureCalibration(systolic, diastolic)
            } else {
                Toast.makeText(context, "Fill in both fields to proceed", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
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

        val colorAccent = when (state.currentActivity.lowercase()) {
            "walking" -> resources.getColor(R.color.primary_neon)
            "jogging", "running" -> resources.getColor(R.color.health_bpm)
            "sitting" -> resources.getColor(R.color.health_shimmer)
            "standing" -> resources.getColor(R.color.health_pressure)
            else -> resources.getColor(R.color.health_shimmer)
        }

        tvConfidenceValue.setTextColor(colorAccent)
        progressConfidence.progressTintList = android.content.res.ColorStateList.valueOf(colorAccent)

        setActivityIcon(state.currentActivity)
    }

    private fun setActivityIcon(activity: String) {
        val drawableRes = when (activity.lowercase()) {
            "walking" -> getDrawableIdByName("ic_activity_walking")
            "jogging", "running" -> getDrawableIdByName("ic_activity_running")
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
                if (!HealthMonitoringService.isAutoMeasuringActive) {
                    btnToggleHR.text = "START BPM"
                }
                tvBpmValue.text = lastBpm
                stopAllIconAnimations()
            }
        }
    }

    override fun onDataReceived(result: Decoder.DecodedResult) {
        if (result.type == "SPO2" || result.type == "BLOOD_PRESSURE" || result.type == "CALIBRATION_RESULT") {
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread

                when (result.type) {
                    "SPO2" -> {
                        ivO2Icon.clearAnimation()
                        o2Animation = null
                    }
                    "BLOOD_PRESSURE" -> {
                        ivBpIcon.clearAnimation()
                        bpAnimation = null
                    }
                    "CALIBRATION_RESULT" -> {
                        when (result.calibrationStatus) {
                            0 -> Toast.makeText(context, "Calibration successful", Toast.LENGTH_SHORT).show()
                            1 -> Toast.makeText(context, "Calibration error: Out-of-range parameters", Toast.LENGTH_SHORT).show()
                            2 -> Toast.makeText(context, "Calibration rejected by device", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    override fun onError(msg: String) {
        activity?.runOnUiThread {
            if (isAdded) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                stopAllIconAnimations()
            }
        }
    }

    private fun refreshUiFromDatabase() {
        if (!isAdded) return
        try {
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
        refreshUiFromDatabase()

        val activeInstance = SmartRingManager.Companion.getActiveInstance()
        if (activeInstance != null) {
            ringManager = activeInstance

            val isAutoActive = HealthMonitoringService.isAutoMeasuringActive
            updateManualButtonsState(isAutoActive)

            if (ringManager?.isConnected() == false) {
                if (!isAutoActive) btnToggleHR.text = "START BPM"
                tvBpmValue.text = lastBpm
                stopAllIconAnimations()
            }
        }

        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        MotionSessionManager.removeObserver(this)
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

    private fun stopAllIconAnimations() {
        activity?.runOnUiThread {
            ivHeartIcon.clearAnimation()
            ivO2Icon.clearAnimation()
            ivBpIcon.clearAnimation()
            bpmAnimation = null
            o2Animation = null
            bpAnimation = null
        }
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