package com.example.myapplication
import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import androidx.annotation.RequiresPermission
import java.util.UUID

class SmartRingBleManager(private val context: Context) {

    interface RingCallback {
        fun onConnected()
        fun onDisconnected()
        fun onHeartRateReceived(bpm: Int)
        fun onBloodPressureReceived(systolic: Int, diastolic: Int)
        fun onDeviceFound(device: BluetoothDevice, rssi: Int)
    }

    private var gatt: BluetoothGatt? = null
    private var callback: RingCallback? = null
    private val buffer = mutableListOf<Byte>() // PacketAssembler equivalente

    fun setCallback(cb: RingCallback) { callback = cb }

    // --- SCAN (equivalente di start_scan()) ---
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        scanner.startScan(null, settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                callback?.onDeviceFound(result.device, result.rssi)
            }
        })
    }

    // --- CONNESSIONE (equivalente di connect_to_device()) ---
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    // --- INVIO COMANDO (equivalente di _send_raw()) ---
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendPacket(cmdId: Int, key: Int, payload: ByteArray = byteArrayOf()) {
        val packet = SmartRingProtocol.buildPacket(cmdId, key, payload)
        val char = gatt
            ?.getService(UUID.fromString(SmartRingProtocol.SERVICE_UUID))
            ?.getCharacteristic(UUID.fromString(SmartRingProtocol.CHAR_WRITE))
            ?: return
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        char.value     = packet
        gatt?.writeCharacteristic(char)
    }

    // --- AVVIA MISURAZIONE HR (Cmd 0x04, Key corrispondente al tuo codice) ---
    fun startHeartRate() {
        sendPacket(0x04, 0x0E, byteArrayOf(0x01, 0x01)) // da adattare al tuo protocollo
    }

    // --- AVVIA PRESSIONE SANGUIGNA ---
    fun startBloodPressure() {
        sendPacket(0x04, 0x0E, byteArrayOf(0x01, 0x00))
    }

    // --- GATT CALLBACK (equivalente di _notification_handler()) ---
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    g.requestMtu(512) // richiede MTU più alta come nel tuo codice
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> callback?.onDisconnected()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            // Abilita le notifiche sulle due caratteristiche Indicate
            enableNotify(g, SmartRingProtocol.CHAR_CONTROL)
            enableNotify(g, SmartRingProtocol.CHAR_BULK)
            callback?.onConnected()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt,
                                             char: BluetoothGattCharacteristic) {
            val data = char.value ?: return
            // Accumula nel buffer (PacketAssembler)
            buffer.addAll(data.toList())
            tryParseBuffer()
        }

        private fun enableNotify(g: BluetoothGatt, charUuid: String) {
            val svc  = g.getService(UUID.fromString(SmartRingProtocol.SERVICE_UUID)) ?: return
            val ch   = svc.getCharacteristic(UUID.fromString(charUuid)) ?: return
            g.setCharacteristicNotification(ch, true)
            val desc = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            desc?.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            g.writeDescriptor(desc)
        }

        private fun tryParseBuffer() {
            if (buffer.size < 4) return
            val arr = buffer.toByteArray()
            val length = (arr[2].toInt() and 0xFF) or ((arr[3].toInt() and 0xFF) shl 8)
            if (buffer.size < length) return // pacchetto incompleto, aspetta
            val packetBytes = arr.copyOfRange(0, length)
            repeat(length) { buffer.removeAt(0) }

            val parsed = SmartRingProtocol.parsePacket(packetBytes) ?: return
            routePacket(parsed)
        }

        // Routing equivalente al _notification_handler() del tuo Python
        private fun routePacket(p: SmartRingProtocol.ParsedPacket) {
            when {
                p.cmdId == 0x04 && p.key == 0x0E -> {
                    // Measurement complete — leggi i valori
                    if (p.payload.size >= 2) {
                        val type = p.payload[0].toInt() and 0xFF
                        if (type == 0x00) { // HR
                            val bpm = p.payload[1].toInt() and 0xFF
                            callback?.onHeartRateReceived(bpm)
                        } else if (type == 0x01) { // BP
                            val sys  = p.payload[1].toInt() and 0xFF
                            val dia  = if (p.payload.size > 2) p.payload[2].toInt() and 0xFF else 0
                            callback?.onBloodPressureReceived(sys, dia)
                        }
                    }
                    // Invia ACK obbligatorio come nel tuo Python
                    sendPacket(0x04, 0x0E, byteArrayOf(0x00))
                }
            }
        }
    }
}