package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import java.util.UUID

/**
 * Gestione BLE per lo smart ring.
 * Traduzione da bluetooth_manager.py (bleak → Android BluetoothGatt).
 *
 * UUID dal protocollo originale:
 *   SERVICE_UUID  BE940000-...  (servizio principale)
 *   CHAR_CONTROL  BE940001-...  (notifiche di controllo)
 *   CHAR_WRITE    BE940002-...  (scrittura senza risposta)
 *   CHAR_BULK     BE940003-...  (bulk data)
 */
class SmartRingBleManager(private val context: Context) {

    // ── UUID del protocollo ───────────────────────────────────────────────────
    companion object {
        const val SERVICE_UUID  = "BE940000-7333-BE46-B7AE-689E71722BD5"
        const val CHAR_CONTROL  = "BE940001-7333-BE46-B7AE-689E71722BD5"
        const val CHAR_WRITE    = "BE940002-7333-BE46-B7AE-689E71722BD5"
        const val CHAR_BULK     = "BE940003-7333-BE46-B7AE-689E71722BD5"
        const val CCCD_UUID     = "00002902-0000-1000-8000-00805f9b34fb"
    }

    // ── Callback verso il Fragment ────────────────────────────────────────────
    interface RingCallback {
        fun onConnected()
        fun onDisconnected()
        fun onHeartRateReceived(bpm: Int)
        fun onBloodPressureReceived(systolic: Int, diastolic: Int)
        fun onDeviceFound(device: BluetoothDevice, rssi: Int)
    }

    private var callback: RingCallback? = null
    fun setCallback(cb: RingCallback) { callback = cb }

    // ── Stato interno ─────────────────────────────────────────────────────────
    private var gatt: BluetoothGatt?     = null
    private var scanner: BluetoothLeScanner? = null
    private val buffer = mutableListOf<Byte>()   // PacketAssembler equivalente

    // ── Scansione BLE (equivalente di start_scan()) ───────────────────────────
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        scanner = adapter.bluetoothLeScanner ?: return
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
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            callback?.onDeviceFound(result.device, result.rssi)
        }
    }

    // ── Connessione (equivalente di connect_to_device()) ─────────────────────
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan() // ferma la scan appena troviamo il dispositivo
        gatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    // ── Invio pacchetto (equivalente di _send_raw()) ──────────────────────────
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendPacket(cmdId: Int, key: Int, payload: ByteArray = byteArrayOf()) {
        val packet = SmartRingProtocol.buildPacket(cmdId, key, payload)
        val char = gatt
            ?.getService(UUID.fromString(SERVICE_UUID))
            ?.getCharacteristic(UUID.fromString(CHAR_WRITE))
            ?: return
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        char.value     = packet
        gatt?.writeCharacteristic(char)
    }

    // ── Comandi misurazioni ───────────────────────────────────────────────────
    /** Avvia misurazione battito cardiaco dal ring */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startHeartRate() {
        sendPacket(0x04, 0x0E, byteArrayOf(0x00, 0x01))
    }

    /** Avvia misurazione pressione sanguigna dal ring */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startBloodPressure() {
        sendPacket(0x04, 0x0E, byteArrayOf(0x01, 0x01))
    }

    // ── GATT Callback ─────────────────────────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
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

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            // MTU negoziata — ora scopri i servizi se non ancora fatto
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                enableNotify(g, CHAR_CONTROL)
                enableNotify(g, CHAR_BULK)
                callback?.onConnected()
            }
        }

        /**
         * Riceve i dati grezzi dal ring.
         * Equivalente di _notification_handler() in bluetooth_manager.py
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic
        ) {
            val data = char.value ?: return
            buffer.addAll(data.toList())
            tryParseBuffer()
        }

        // Abilita CCCD per le caratteristiche Indicate
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private fun enableNotify(g: BluetoothGatt, charUuid: String) {
            val svc  = g.getService(UUID.fromString(SERVICE_UUID)) ?: return
            val ch   = svc.getCharacteristic(UUID.fromString(charUuid)) ?: return
            g.setCharacteristicNotification(ch, true)
            val desc = ch.getDescriptor(UUID.fromString(CCCD_UUID)) ?: return
            desc.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            g.writeDescriptor(desc)
        }

        /**
         * Accumula i byte ricevuti e tenta di estrarre pacchetti completi.
         * Equivalente di PacketAssembler.process_chunk() in protocol.py
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private fun tryParseBuffer() {
            while (buffer.size >= 4) {
                val arr    = buffer.toByteArray()
                val length = (arr[2].toInt() and 0xFF) or ((arr[3].toInt() and 0xFF) shl 8)
                if (buffer.size < length) break   // pacchetto incompleto

                val packetBytes = arr.copyOfRange(0, length)
                repeat(length) { buffer.removeAt(0) }

                val parsed = SmartRingProtocol.parsePacket(packetBytes) ?: continue
                routePacket(parsed)
            }
        }

        /**
         * Smista i pacchetti parsati verso i callback giusti.
         * Equivalente del routing in _notification_handler()
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private fun routePacket(p: SmartRingProtocol.ParsedPacket) {
            when {
                // Measurement complete — Cmd 0x04, Key 0x0E (come nel tuo Python)
                p.cmdId == 0x04 && p.key == 0x0E -> {
                    if (p.payload.size >= 2) {
                        val measType   = p.payload[0].toInt() and 0xFF
                        val measResult = p.payload[1].toInt() and 0xFF

                        when (measType) {
                            0x00 -> {  // Heart Rate
                                callback?.onHeartRateReceived(measResult)
                            }
                            0x01 -> {  // Blood Pressure
                                val systolic  = measResult
                                val diastolic = if (p.payload.size > 2)
                                    p.payload[2].toInt() and 0xFF else 0
                                callback?.onBloodPressureReceived(systolic, diastolic)
                            }
                        }
                    }
                    // ACK obbligatorio (identico al tuo Python)
                    sendPacket(0x04, 0x0E, byteArrayOf(0x00))
                }
            }
        }
    }
}