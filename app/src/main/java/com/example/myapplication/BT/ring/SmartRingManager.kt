package com.example.myapplication.BT.ring

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

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

    fun updateListener(newListener: SmartRingListener) {
        this.listener = newListener
    }

    fun isConnected(): Boolean = connected
    fun getAddress(): String = macAddress

    // Espone all'esterno se l'hardware è impegnato in un task
    fun isMeasuring(): Boolean = currentMeasuringType != null

    // Permette di leggere l'esatta stringa identificativa della misurazione in corso
    fun getActiveMeasurementType(): String? = currentMeasuringType

    @SuppressLint("MissingPermission")
    fun connect(runtimeContext: Context) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            listener.onError("Bluetooth non supportato")
            return
        }
        try {
            val device = adapter.getRemoteDevice(macAddress)
            bluetoothGatt = device.connectGatt(runtimeContext.applicationContext, false, gattCallback)
        } catch (e: Exception) {
            listener.onError("Errore GATT: ${e.message}")
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
                            Log.e("SMART_RING", "Errore MTU: ${e.message}")
                        }
                    }, 300)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                bluetoothGatt = null
                currentMeasuringType = null // Ripristino dello stato hardware alla disconnessione
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
                    "O2", "SPO2" -> {
                            currentMeasuringType = null
                            Log.d("SMART_RING", "Misurazione SpO2 completata. Stato resettato.")
                    }
                    "PRESSURE", "BP" -> {
                            currentMeasuringType = null
                            Log.d("SMART_RING", "Misurazione Pressione completata. Stato resettato.")
                    }
                    // Nota: Non resettiamo qui il "BPM" perché è uno stream continuo
                    // e si deve stoppare solo con stopAllMeasurements()
                }
                mainHandler.post { listener.onDataReceived(decodedResult) }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            Log.d("SMART_RING", "Descrittore scritto con stato: $status")
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(PacketManager.SERVICE_UUID)
        if (service == null) {
            Log.e("SMART_RING", "Servizio UUID non trovato sull'hardware!")
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
                Log.d("SMART_RING", "Notifiche registrate con successo per: ${char.uuid}")
            }
        } catch (e: Exception) {
            Log.e("SMART_RING", "Errore critico durante setIndicate: ${e.message}")
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
                Log.e("SMART_RING", "Errore durante l'invio del pacchetto: ${e.message}")
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
            listener.onError("Valori non validi (Sistolica: 60-250, Diastolica: 40-150)")
        }
    }

    fun syncUserInfo() {
        val payload = PacketManager.buildUserInfoPayload(context)
        sendCommand(0x01.toByte(), 0x01.toByte(), payload)
    }

    fun startHeartRateMeasurement() {
        currentMeasuringType = "BPM"
        sendCommand(0x03.toByte(), 0x0C.toByte(), byteArrayOf(0x01, 0x01))
        mainHandler.postDelayed({
            sendCommand(0x03.toByte(), 0x09.toByte(), byteArrayOf(0x01, 0x01, 0x01))
        }, 600)
    }

    fun startSpO2Measurement() {
        currentMeasuringType = "O2"
        sendCommand(0x03.toByte(), 0x0C.toByte(), byteArrayOf(0x01, 0x01))
        mainHandler.postDelayed({
            sendCommand(0x03.toByte(), 0x2F.toByte(), byteArrayOf(0x01, 0x02))
        }, 600)
    }

    fun startBloodPressureMeasurement() {
        currentMeasuringType = "PRESSURE"
        sendCommand(0x03.toByte(), 0x0C.toByte(), byteArrayOf(0x01, 0x01))
        mainHandler.postDelayed({
            sendCommand(0x03.toByte(), 0x2F.toByte(), byteArrayOf(0x01, 0x01))
        }, 600)
    }

    fun stopAllMeasurements() {
        currentMeasuringType = null // Reset dello stato
        sendCommand(0x03.toByte(), 0x09.toByte(), byteArrayOf(0x00, 0x01, 0x01))
        mainHandler.postDelayed({
            sendCommand(0x03.toByte(), 0x0C.toByte(), byteArrayOf(0x00, 0x01))
        }, 600)
    }
}