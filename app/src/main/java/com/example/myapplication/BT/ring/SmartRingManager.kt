package com.example.myapplication.BT.ring

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

class SmartRingManager private constructor(
    private val context: Context,
    private val macAddress: String,
    private var listener: SmartRingListener
) {

    interface SmartRingListener {
        fun onConnected()
        fun onDisconnected()
        fun onDataReceived(result: Decoder.DecodedResult)
        fun onError(msg: String)
    }

    companion object {
        @Volatile
        private var INSTANCE: SmartRingManager? = null

        fun getInstance(context: Context, macAddress: String, listener: SmartRingListener): SmartRingManager {
            return INSTANCE ?: synchronized(this) {
                val currentInstance = INSTANCE
                if (currentInstance != null && currentInstance.getAddress() == macAddress) {
                    currentInstance.updateListener(listener)
                    currentInstance
                } else {
                    val newInstance = SmartRingManager(context.applicationContext, macAddress, listener)
                    INSTANCE = newInstance
                    newInstance
                }
            }
        }

        fun getActiveInstance(): SmartRingManager? = INSTANCE
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var connected = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentMeasuringType: String? = null

    private val measurementWatchdogRunnable = Runnable {
        if (currentMeasuringType != null) {
            val typeFailed = currentMeasuringType
            currentMeasuringType = null // Immediate hardware unlock
            Log.w("SMART_RING", "Watchdog triggered! Measurement $typeFailed timed out after 60 seconds. State forced to reset.")

            // Send clear alert message to user on the main UI thread
            mainHandler.post {
                val msg = if (typeFailed == "PRESSURE") "Blood Pressure timeout. Please stay still and try again."
                else "Blood Oxygen timeout. Please stay still and try again."
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun updateListener(newListener: SmartRingListener) {
        this.listener = newListener
    }

    fun isConnected(): Boolean = connected
    fun getAddress(): String = macAddress

    // Exposes whether the hardware is busy with an active task
    fun isMeasuring(): Boolean = currentMeasuringType != null

    // Allows reading the current active measurement type string
    fun getActiveMeasurementType(): String? = currentMeasuringType

    @SuppressLint("MissingPermission")
    fun connect(runtimeContext: Context) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            listener.onError("Bluetooth not supported")
            return
        }
        try {
            val device = adapter.getRemoteDevice(macAddress)
            bluetoothGatt = device.connectGatt(runtimeContext.applicationContext, false, gattCallback)
        } catch (e: Exception) {
            listener.onError("Gatt connection error: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopAllMeasurements()
        mainHandler.postDelayed({
            connected = false
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            mainHandler.post { listener.onDisconnected() }
        }, 1000)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                mainHandler.post {
                    listener.onConnected()
                    mainHandler.postDelayed({
                        try {
                            gatt.requestMtu(512)
                        } catch (e: Exception) {
                            Log.e("SMART_RING", "MTU Error: ${e.message}")
                        }
                    }, 300)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                bluetoothGatt = null
                currentMeasuringType = null // Reset state machine on disconnect
                mainHandler.post { listener.onDisconnected() }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mainHandler.postDelayed({ gatt.discoverServices() }, 300)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post { enableNotifications(gatt) }
                mainHandler.postDelayed({
                    syncUserInfo()
                    requestBatteryLevel()
                }, 2000)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            val hex = data.joinToString(" ") { String.format("%02X", it) }
            Decoder.decode(hex)?.let { decodedResult ->
                when (decodedResult.type) {
                    "SPO2" -> {
                        // Success! Remove the 60-second watchdog callback
                        mainHandler.removeCallbacks(measurementWatchdogRunnable)

                        // Maintain the state briefly for 2 seconds so the UI catches the update cleanly
                        val typeToReset = currentMeasuringType
                        if (typeToReset == "O2") {
                            mainHandler.postDelayed({
                                if (currentMeasuringType == typeToReset) {
                                    currentMeasuringType = null
                                    Log.d("SMART_RING", "SpO2 measurement complete. State reset.")
                                }
                            }, 2000)
                        }
                    }
                    "BP" -> {
                        // Success! Remove the 60-second watchdog callback
                        mainHandler.removeCallbacks(measurementWatchdogRunnable)

                        val typeToReset = currentMeasuringType
                        if (typeToReset == "PRESSURE") {
                            mainHandler.postDelayed({
                                if (currentMeasuringType == typeToReset) {
                                    currentMeasuringType = null
                                    Log.d("SMART_RING", "Blood Pressure measurement complete. State reset.")
                                }
                            }, 2000)
                        }
                    }
                    "CALIBRATION_RESULT" -> {
                        // Instantly unlock hardware on completion
                        currentMeasuringType = null
                        Log.d("SMART_RING", "Calibration result code: ${decodedResult.calibrationStatus}")

                        mainHandler.post {
                            when (decodedResult.calibrationStatus) {
                                0 -> {
                                    Log.d("SMART_RING", "Calibration completed successfully.")
                                    Toast.makeText(context, "Calibration successful", Toast.LENGTH_SHORT).show()
                                }
                                1 -> {
                                    Log.e("SMART_RING", "Calibration error: Invalid parameters.")
                                    Toast.makeText(context, "Calibration failed: invalid parameters", Toast.LENGTH_SHORT).show()
                                }
                                2 -> {
                                    Log.w("SMART_RING", "Calibration error: Device not in matching measurement mode.")
                                    Toast.makeText(context, "Device not ready for calibration", Toast.LENGTH_SHORT).show()
                                }
                                else -> {
                                    Log.e("SMART_RING", "Unknown calibration error code: ${decodedResult.calibrationStatus}")
                                    Toast.makeText(context, "Unknown calibration error", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    "MEASUREMENT_FAILED" -> {
                        mainHandler.removeCallbacks(measurementWatchdogRunnable)
                        // Reset immediately on explicit internal failure code without extra delays
                        currentMeasuringType = null
                        Log.w("SMART_RING", "Measurement failed by hardware. State unlocked immediately.")
                    }
                    "END_ACK" -> {
                        mainHandler.removeCallbacks(measurementWatchdogRunnable)
                        mainHandler.postDelayed({
                            currentMeasuringType = null
                            Log.d("SMART_RING", "END_ACK received. State cleared.")
                        }, 2000)
                    }
                }
                mainHandler.post { listener.onDataReceived(decodedResult) }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            Log.d("SMART_RING", "Descriptor written with status: $status")
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(PacketManager.SERVICE_UUID)
        if (service == null) {
            Log.e("SMART_RING", "Service UUID not found on hardware!")
            return
        }

        val charControl = service.getCharacteristic(PacketManager.CHAR_COMMAND_CONTROL)
        if (charControl != null) setIndicate(gatt, charControl)

        mainHandler.postDelayed({
            val charUpload = service.getCharacteristic(PacketManager.CHAR_DATA_UPLOAD)
            if (charUpload != null) setIndicate(gatt, charUpload)
        }, 600)
    }

    @SuppressLint("MissingPermission")
    private fun setIndicate(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        try {
            gatt.setCharacteristicNotification(char, true)
            val desc = char.getDescriptor(PacketManager.CCCD_UUID)
            if (desc != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    desc.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    gatt.writeDescriptor(desc)
                }
                Log.d("SMART_RING", "Notifications registered for: ${char.uuid}")
            }
        } catch (e: Exception) {
            Log.e("SMART_RING", "Critical error during setIndicate: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendCommand(id: Byte, key: Byte, payload: ByteArray) {
        val gatt = bluetoothGatt
        if (gatt == null || !connected) return

        val service = gatt.getService(PacketManager.SERVICE_UUID)
        val char = service?.getCharacteristic(PacketManager.CHAR_COMMAND_CONTROL)

        if (char != null) {
            val packet = PacketManager.buildPacket(id, key, payload)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(char, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                } else {
                    @Suppress("DEPRECATION")
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    char.value = packet
                    gatt.writeCharacteristic(char)
                }
            } catch (e: Exception) {
                Log.e("SMART_RING", "Error writing characteristic packet: ${e.message}")
            }
        }
    }

    fun requestBatteryLevel() {
        sendCommand(0x02.toByte(), 0x00.toByte(), byteArrayOf(0x47.toByte(), 0x43.toByte()))
    }

    fun sendBloodPressureCalibration(systolic: Int, diastolic: Int) {
        if (systolic in 60..250 && diastolic in 40..150) {
            val payload = byteArrayOf(systolic.toByte(), diastolic.toByte())
            sendCommand(0x03.toByte(), 0x03.toByte(), payload)
        } else {
            listener.onError("Invalid inputs (Systolic: 60-250, Diastolic: 40-150)")
        }
    }

    fun syncUserInfo() {
        val payload = PacketManager.buildUserInfoPayload(context)
        sendCommand(0x01.toByte(), 0x01.toByte(), payload)
    }

    fun startHeartRateMeasurement() {
        currentMeasuringType = "BPM"
        mainHandler.removeCallbacks(measurementWatchdogRunnable)
        sendCommand(0x03.toByte(), 0x0C.toByte(), byteArrayOf(0x01, 0x01))
        mainHandler.postDelayed({
            sendCommand(0x03.toByte(), 0x09.toByte(), byteArrayOf(0x01, 0x01, 0x01))
        }, 600)
    }

    fun startSpO2Measurement() {
        currentMeasuringType = "O2"
        mainHandler.removeCallbacks(measurementWatchdogRunnable)
        mainHandler.postDelayed(measurementWatchdogRunnable, 60000)
        sendCommand(0x03.toByte(), 0x0C.toByte(), byteArrayOf(0x01, 0x01))
        mainHandler.postDelayed({
            sendCommand(0x03.toByte(), 0x2F.toByte(), byteArrayOf(0x01, 0x02))
        }, 600)
    }

    fun startBloodPressureMeasurement() {
        currentMeasuringType = "PRESSURE"
        mainHandler.removeCallbacks(measurementWatchdogRunnable)
        mainHandler.postDelayed(measurementWatchdogRunnable, 60000)
        sendCommand(0x03.toByte(), 0x0C.toByte(), byteArrayOf(0x01, 0x01))
        mainHandler.postDelayed({
            sendCommand(0x03.toByte(), 0x2F.toByte(), byteArrayOf(0x01, 0x01))
        }, 600)
    }

    fun stopAllMeasurements() {
        mainHandler.removeCallbacks(measurementWatchdogRunnable)
        currentMeasuringType = null
        sendCommand(0x03.toByte(), 0x09.toByte(), byteArrayOf(0x00, 0x01, 0x01))
        mainHandler.postDelayed({
            sendCommand(0x03.toByte(), 0x0C.toByte(), byteArrayOf(0x00, 0x01))
        }, 600)
    }
}