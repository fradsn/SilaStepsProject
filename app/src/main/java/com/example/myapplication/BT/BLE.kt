package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.os.*
import java.util.UUID

class BLE : Service() {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private val binder = LocalBinder()

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val intent = Intent("BLE_STATUS_UPDATE")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                broadcastLog("CONNESSO. Ricerca servizi...")
                intent.putExtra("status", "CONNESSO")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                broadcastLog("DISCONNESSO.")
                intent.putExtra("status", "DISCONNESSO")
                bluetoothGatt = null
            }
            sendBroadcast(intent)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastLog("Canali pronti.")
                enableNotifications(gatt)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val hex = characteristic.value.joinToString(" ") { String.format("%02X", it) }
            sendBroadcast(Intent("BLE_DATA_RX").putExtra("data", "RX: $hex"))
        }
    }

    @SuppressLint("MissingPermission")
    fun sendCommand(id: Byte, key: Byte, payload: ByteArray, desc: String = "") {
        val service = bluetoothGatt?.getService(Constants.SERVICE_UUID)
        val char = service?.getCharacteristic(Constants.CHAR_COMMAND_CONTROL)
        if (char != null) {
            val finalPacket = SmartRingProtocol.buildPacket(id, key, payload)
            val hex = finalPacket.joinToString(" ") { String.format("%02X", it) }
            val logEntry = if (desc.isNotEmpty()) "TX ($desc): $hex" else "TX: $hex"
            broadcastLog(logEntry)

            char.value = finalPacket
            bluetoothGatt?.writeCharacteristic(char)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(Constants.SERVICE_UUID) ?: return
        service.getCharacteristic(Constants.CHAR_COMMAND_CONTROL)?.let { setIndicate(gatt, it) }
        Handler(Looper.getMainLooper()).postDelayed({
            service.getCharacteristic(Constants.CHAR_DATA_UPLOAD)?.let { setIndicate(gatt, it) }
        }, 500)
    }

    @SuppressLint("MissingPermission")
    private fun setIndicate(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(char, true)
        val desc = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        desc?.let {
            it.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            gatt.writeDescriptor(it)
        }
    }

    fun initialize(): Boolean {
        bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        return bluetoothAdapter != null
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        bluetoothGatt = bluetoothAdapter?.getRemoteDevice(address)?.connectGatt(this, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnectDevice() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        sendBroadcast(Intent("BLE_STATUS_UPDATE").putExtra("status", "DISCONNESSO"))
    }

    private fun broadcastLog(msg: String) {
        sendBroadcast(Intent("BLE_DATA_RX").putExtra("data", msg))
    }

    override fun onBind(intent: Intent): IBinder = binder
    inner class LocalBinder : Binder() { fun getService(): BLE = this@BLE }
}