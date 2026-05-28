package com.example.myapplication.BT.ring

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.widget.Toast
import androidx.annotation.RequiresPermission
import java.util.UUID

// MODIFICA: Il costruttore ora accetta anche l'adapter
class SmartRingBleManager(
    private val context: Context,
    private val adapter: DeviceAdapter
) {

    companion object {
        const val SERVICE_UUID = "BE940000-7333-BE46-B7AE-689E71722BD5"
        const val CHAR_CONTROL = "BE940001-7333-BE46-B7AE-689E71722BD5"
        const val CHAR_WRITE = "BE940002-7333-BE46-B7AE-689E71722BD5"
        const val CHAR_BULK = "BE940003-7333-BE46-B7AE-689E71722BD5"
        const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }

    interface RingCallback {
        fun onConnected()
        fun onDisconnected()
        fun onHeartRateReceived(bpm: Int)
        fun onBloodPressureReceived(systolic: Int, diastolic: Int)
        fun onDeviceFound(device: BluetoothDevice, rssi: Int)
    }

    private var callback: RingCallback? = null
    fun setCallback(cb: RingCallback) { callback = cb }

    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private val buffer = mutableListOf<Byte>()

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)

    fun startScan() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        // Controllo se il Bluetooth è attivo
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(context, "Attiva il Bluetooth per cercare i dispositivi", Toast.LENGTH_LONG).show() //
            return // Blocca la scansione
        }

        scanner = bluetoothAdapter.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, scanCallback)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        scanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        // In SmartRingBleManager.kt
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: ""
            val isShimmer = name.startsWith("Shimmer", ignoreCase = true)
            adapter.addDevice(result.device, isShimmer)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        gatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        stopAllMeasurements()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendPacket(cmdId: Int, key: Int, payload: ByteArray = byteArrayOf()) {
        val packet = SmartRingProtocol.buildPacket(cmdId.toByte(), key.toByte(), payload)
        val char = gatt
            ?.getService(UUID.fromString(SERVICE_UUID)) // Nota: Usato SERVICE_UUID locale
            ?.getCharacteristic(UUID.fromString(CHAR_CONTROL))
            ?: return
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        char.value = packet
        gatt?.writeCharacteristic(char)
    }

    // ---- comandi misure ----------------------------------------------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startHeartRate() {
        sendPacket(0x04, 0x0E, byteArrayOf(0x00, 0x01))
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startBloodPressure() {
        sendPacket(0x04, 0x0E, byteArrayOf(0x01, 0x01))
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun stopAllMeasurements() {
        // Implementazione stop se necessaria
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(
            g: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    g.requestMtu(512)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    callback?.onDisconnected()
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                enableNotify(g, CHAR_CONTROL)
                enableNotify(g, CHAR_BULK)
                callback?.onConnected()
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic
        ) {
            val data = char.value ?: return
            buffer.addAll(data.toList())
            tryParseBuffer()
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private fun enableNotify(g: BluetoothGatt, charUuid: String) {
            val svc = g.getService(UUID.fromString(SERVICE_UUID)) ?: return
            val ch = svc.getCharacteristic(UUID.fromString(charUuid)) ?: return
            g.setCharacteristicNotification(ch, true)
            val desc = ch.getDescriptor(UUID.fromString(CCCD_UUID)) ?: return
            desc.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            g.writeDescriptor(desc)
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private fun tryParseBuffer() {
            while (buffer.size >= 4) {
                val arr = buffer.toByteArray()
                val length =
                    (arr[2].toInt() and 0xFF) or ((arr[3].toInt() and 0xFF) shl 8)
                if (buffer.size < length) break

                val packetBytes = arr.copyOfRange(0, length)
                repeat(length) { buffer.removeAt(0) }

                // Assicurati che le classi SmartRing o SmartRingProtocol esistano
                val parsed = SmartRing.parsePacket(packetBytes) ?: continue
                routePacket(parsed)
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private fun routePacket(p: SmartRing.ParsedPacket) {
            when {
                p.cmdId == 0x04 && p.key == 0x0E -> {
                    if (p.payload.size >= 2) {
                        val measType = p.payload[0].toInt() and 0xFF
                        val measResult = p.payload[1].toInt() and 0xFF
                        when (measType) {
                            0x00 -> callback?.onHeartRateReceived(measResult)
                            0x01 -> {
                                val systolic = measResult
                                val diastolic = if (p.payload.size > 2) p.payload[2].toInt() and 0xFF else 0
                                callback?.onBloodPressureReceived(systolic, diastolic)
                            }
                        }
                    }
                    sendPacket(0x04, 0x0E, byteArrayOf(0x00))
                }
            }
        }
    }
}