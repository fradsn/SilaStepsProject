package com.example.myapplication.BT

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object BleScannerManager {

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var deviceAdapter: DeviceAdapter? = null
    private var isScanning = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanStateListener: ScanStateListener? = null

    interface ScanStateListener {
        fun onScanStarted()
        fun onScanStopped()
    }

    fun init(adapter: DeviceAdapter, listener: ScanStateListener) {
        this.deviceAdapter = adapter
        this.scanStateListener = listener
    }

    fun isScanning(): Boolean = isScanning

    @SuppressLint("MissingPermission")
    fun startScan(context: Context) {
        if (isScanning) return

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(context, "Please enable Bluetooth to scan", Toast.LENGTH_SHORT).show()
            scanStateListener?.onScanStopped()
            isScanning = false
            return
        }

        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        if (bluetoothLeScanner == null) return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning = true
        scanStateListener?.onScanStarted()
        bluetoothLeScanner?.startScan(null, settings, leScanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        isScanning = false

        scanStateListener?.onScanStopped()

        try {
            bluetoothLeScanner?.stopScan(leScanCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: ""
            val isShimmer = name.startsWith("Shimmer", ignoreCase = true)

            mainHandler.post {
                deviceAdapter?.addDevice(result.device, isShimmer)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            mainHandler.post {
                for (result in results) {
                    val name = result.device.name ?: ""
                    val isShimmer = name.startsWith("Shimmer", ignoreCase = true)
                    deviceAdapter?.addDevice(result.device, isShimmer)
                }
            }
        }
    }
}