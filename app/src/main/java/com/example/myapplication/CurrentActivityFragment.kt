package com.example.myapplication

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
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
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class CurrentActivityFragment : Fragment() {

    private val TAG = "CurrentActivityFragment"

    companion object {
        var isRingConnectedGlobal: Boolean = false
        var isMeasuringHRGlobal: Boolean = false
        val bpmHistory: MutableList<Float> = mutableListOf()
    }

    private lateinit var tvBpmValue: TextView
    private lateinit var tvSpO2Value: TextView
    private lateinit var tvBpValue: TextView
    private lateinit var tvBleStatusLabel: TextView
    private lateinit var btnToggleHR: Button
    private lateinit var btnConnectRing: Button
    private lateinit var btnStartSpO2: Button
    private lateinit var btnStartBP: Button

    private lateinit var pulseRing1: View
    private lateinit var pulseRing2: View
    private lateinit var pulseRing3: View

    private var bleService: BLE? = null
    private val RING_MAC = "FE:1C:6D:14:03:0B"

    private val bleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "BLE_DATA_RX" -> {
                    val raw = intent.getStringExtra("data") ?: return
                    if (raw.startsWith("RX: ")) handleDecodedData(raw.removePrefix("RX: "))
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
        }
        override fun onServiceDisconnected(name: ComponentName?) { bleService = null }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.prova, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        startPulseAnimation()

        // Sincronizzazione stato al ritorno nel Fragment
        if (isRingConnectedGlobal) {
            onBleConnected()
            if (isMeasuringHRGlobal) btnToggleHR.text = "STOP BPM"
        }

        requireActivity().bindService(Intent(requireActivity(), BLE::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        btnConnectRing.setOnClickListener {
            if (isRingConnectedGlobal) bleService?.disconnectDevice() else checkPermissionsAndConnect()
        }

        btnToggleHR.setOnClickListener {
            if (isRingConnectedGlobal) {
                if (isMeasuringHRGlobal) stopAllSensors() else startHR()
                isMeasuringHRGlobal = !isMeasuringHRGlobal
                btnToggleHR.text = if (isMeasuringHRGlobal) "STOP BPM" else "START BPM"
            }
        }

        btnStartSpO2.setOnClickListener { if (isRingConnectedGlobal) startSpO2() }
        btnStartBP.setOnClickListener { if (isRingConnectedGlobal) startBP() }
    }

    private fun bindViews(view: View) {
        tvBpmValue = view.findViewById(R.id.tvBpmValue)
        tvSpO2Value = view.findViewById(R.id.tvSpO2Value)
        tvBpValue = view.findViewById(R.id.tvBpValue)
        tvBleStatusLabel = view.findViewById(R.id.tvBleStatusLabel)
        btnToggleHR = view.findViewById(R.id.btnToggleHR)
        btnConnectRing = view.findViewById(R.id.btnConnectRing)
        btnStartSpO2 = view.findViewById(R.id.btnStartSpO2)
        btnStartBP = view.findViewById(R.id.btnStartBP)
        pulseRing1 = view.findViewById(R.id.pulseRing1)
        pulseRing2 = view.findViewById(R.id.pulseRing2)
        pulseRing3 = view.findViewById(R.id.pulseRing3)
    }

    private fun handleDecodedData(hex: String) {
        Decoder.decode(hex)?.let { result ->
            when (result.type) {
                "BPM" -> {
                    if (result.value > 0) {
                        tvBpmValue.text = result.value.toString()
                        bpmHistory.add(result.value.toFloat())
                        if (bpmHistory.size > 100) bpmHistory.removeAt(0)

                        // Notifica al fragment dei grafici se esistente
                        (parentFragmentManager.findFragmentByTag("charts") as? ChartsFragment)
                            ?.updateHeartRateChart(bpmHistory, result.value)
                    }
                }
                "SPO2" -> tvSpO2Value.text = "${result.value} %"
                "BP" -> tvBpValue.text = "${result.sys} / ${result.dia}"
            }
        }
    }

    private fun startHR() {
        bleService?.sendCommand(0x03.toByte(), 0x0C.toByte(), byteArrayOf(0x01, 0x01), "Stream ON")
        Handler(Looper.getMainLooper()).postDelayed({
            bleService?.sendCommand(0x03.toByte(), 0x09.toByte(), byteArrayOf(0x01, 0x01, 0x01), "Start BPM")
        }, 600)
    }

    private fun stopAllSensors() {
        bleService?.sendCommand(0x03.toByte(), 0x09.toByte(), byteArrayOf(0x00, 0x01, 0x01), "Stop BPM")
        Handler(Looper.getMainLooper()).postDelayed({
            bleService?.sendCommand(0x03.toByte(), 0x0C.toByte(), byteArrayOf(0x00, 0x01), "Stream OFF")
        }, 600)
    }

    private fun startSpO2() {
        bleService?.sendCommand(0x03.toByte(), 0x0C.toByte(), byteArrayOf(0x01, 0x01), "Stream ON SpO2")
        Handler(Looper.getMainLooper()).postDelayed({
            bleService?.sendCommand(0x03.toByte(), 0x2F.toByte(), byteArrayOf(0x01, 0x02), "Start SpO2")
        }, 600)
    }

    private fun startBP() {
        bleService?.sendCommand(0x03.toByte(), 0x0C.toByte(), byteArrayOf(0x01, 0x01), "Stream ON BP")
        Handler(Looper.getMainLooper()).postDelayed({
            bleService?.sendCommand(0x03.toByte(), 0x2F.toByte(), byteArrayOf(0x01, 0x01), "Start BP")
        }, 600)
    }

    private fun checkPermissionsAndConnect() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionLauncher.launch(needed)
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms.values.all { it }) bleService?.connect(RING_MAC)
    }

    private fun onBleConnected() {
        isRingConnectedGlobal = true
        tvBleStatusLabel.text = "● Connesso"
        btnConnectRing.text = "DISCONNETTI"
    }

    private fun onBleDisconnected() {
        isRingConnectedGlobal = false
        isMeasuringHRGlobal = false
        tvBleStatusLabel.text = "● Non connesso"
        btnConnectRing.text = "CONNETTI RING"
        btnToggleHR.text = "START BPM"
        tvBpmValue.text = "--"
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

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply { addAction("BLE_DATA_RX"); addAction("BLE_STATUS_UPDATE") }
        requireContext().registerReceiver(bleReceiver, filter, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0)
    }

    override fun onStop() {
        super.onStop()
        try { requireContext().unregisterReceiver(bleReceiver) } catch (e: Exception) {}
    }
}