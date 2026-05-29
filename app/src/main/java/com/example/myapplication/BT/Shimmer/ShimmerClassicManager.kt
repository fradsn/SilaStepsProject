package com.example.myapplication.BT.Shimmer

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.ActivityCompat
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

data class ImuSample(
    val accX: Double,
    val accY: Double,
    val accZ: Double,
    val gyroX: Double,
    val gyroY: Double,
    val gyroZ: Double
)

// MODIFICA 1: Costruttore privato e listener diventa 'var'
class ShimmerClassicManager private constructor(
    private val context: Context,
    private val macAddress: String,
    private var listener: ShimmerListener
) {

    interface ShimmerListener {
        fun onConnected()
        fun onDisconnected()
        fun onSetup()
        fun onError(msg: String)
        fun onSampleReceived(sample: ImuSample)
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        // Comandi Shimmer
        private const val CMD_START_STREAM: Byte = 0x07
        private const val CMD_STOP_STREAM: Byte = 0x20
        private const val CMD_SET_SENSORS: Byte = 0x08
        private const val CMD_SET_SR: Byte = 0x05
        private const val CMD_SET_ACCEL_RANGE: Byte = 0x09
        private const val CMD_SET_GYRO_RANGE: Byte = 0x49

        // Costanti di Calibrazione
        private const val ACCEL_SENSITIVITY_4G = 819.0
        private const val GYRO_SENSITIVITY_500DPS = 65.5

        // --- GESTIONE SINGLETON ---
        @Volatile
        private var INSTANCE: ShimmerClassicManager? = null

        /**
         * Recupera l'istanza unica dello Shimmer.
         * Se non esiste o l'indirizzo MAC è differente, ne crea una nuova.
         * Se esiste già, aggiorna semplicemente il listener con l'Activity corrente.
         */
        fun getInstance(context: Context, macAddress: String, listener: ShimmerListener): ShimmerClassicManager {
            return INSTANCE ?: synchronized(this) {
                val currentInstance = INSTANCE
                if (currentInstance != null && currentInstance.getAddress() == macAddress) {
                    currentInstance.updateListener(listener)
                    currentInstance
                } else {
                    // Usiamo context.applicationContext per evitare Memory Leak legati alle Activity
                    val newInstance = ShimmerClassicManager(context.applicationContext, macAddress, listener)
                    INSTANCE = newInstance
                    newInstance
                }
            }
        }

        /**
         * Ritorna l'istanza attualmente attiva (può essere null se non è mai stata inizializzata)
         */
        fun getActiveInstance(): ShimmerClassicManager? = INSTANCE
    }

    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var readThread: Thread? = null
    private var connected = false
    private var setup = false
    private var streaming = false
    private val frameBuffer = mutableListOf<Byte>()

    // MODIFICA 2: Metodo per ri-agganciare il listener quando si cambia Activity
    fun updateListener(newListener: ShimmerListener) {
        this.listener = newListener
    }

    fun isConnected(): Boolean = connected
    fun getAddress(): String = macAddress

    fun connect() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val device = adapter.getRemoteDevice(macAddress)

        thread {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        listener.onError("Permesso BLUETOOTH_CONNECT mancante")
                        return@thread
                    }
                }
                val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
                sock.connect()
                input = sock.inputStream
                output = sock.outputStream
                connected = true
                listener.onConnected()
            } catch (e: Exception) {
                listener.onError("Connessione fallita: ${e.message}")
            }
        }
    }

    fun setupShimmer() {
        if (!connected || input == null || output == null) {
            listener.onError("Shimmer non connesso")
            return
        }

        thread {
            val commands = listOf(
                byteArrayOf(CMD_SET_SR, 0x90.toByte(), 0x02.toByte()),
                byteArrayOf(CMD_SET_SENSORS, 0xC0.toByte(), 0x00, 0x00),
                byteArrayOf(CMD_SET_ACCEL_RANGE, 0x01.toByte()),
                byteArrayOf(CMD_SET_GYRO_RANGE, 0x01.toByte())
            )

            for (cmd in commands) {
                send(cmd)
                val ack = readAck()
                if (ack != 0xFF.toByte()) {
                    Handler(Looper.getMainLooper()).post { listener.onError("Errore setup: No ACK per ${"%02X".format(cmd[0])}") }
                    return@thread
                }
                Thread.sleep(100)
            }

            setup = true
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Shimmer configurato (50Hz, ±4g, 500dps)", Toast.LENGTH_SHORT).show()
                listener.onSetup()
            }
        }
    }

    private fun readAck(timeoutMs: Long = 1000): Byte? {
        val inputStream = input ?: return null
        val start = System.currentTimeMillis()
        while (inputStream.available() == 0) {
            if (System.currentTimeMillis() - start > timeoutMs) return null
            Thread.sleep(10)
        }
        return inputStream.read().toByte()
    }

    fun startStreaming() {
        if (!setup) {
            listener.onError("Setup incompleto")
            return
        }
        send(CMD_START_STREAM)
        startReaderThread()
    }

    fun stopStreaming() {
        streaming = false
        send(CMD_STOP_STREAM)
    }

    private fun startReaderThread() {
        if (streaming) return
        streaming = true
        readThread = thread(name = "ShimmerReader") {
            val buffer = ByteArray(1024)
            while (streaming) {
                try {
                    val len = input?.read(buffer) ?: -1
                    if (len > 0) {
                        onBytesReceived(buffer.copyOf(len))
                    } else if (len == -1) {
                        break
                    }
                } catch (e: Exception) {
                    if (streaming) {
                        Handler(Looper.getMainLooper()).post { listener.onError("Errore lettura: ${e.message}") }
                    }
                    break
                }
            }
        }
    }

    private fun onBytesReceived(bytes: ByteArray) {
        frameBuffer.addAll(bytes.toList())
        extractFrames()
    }

    private fun extractFrames() {
        val frameSize = 16

        while (frameBuffer.size >= frameSize) {
            if (frameBuffer[0] != 0x00.toByte()) {
                frameBuffer.removeAt(0)
                continue
            }

            val frame = frameBuffer.subList(0, frameSize).toByteArray()
            repeat(frameSize) { frameBuffer.removeAt(0) }

            parseFrame(frame)
        }
    }

    private fun parseFrame(frame: ByteArray) {
        fun s16(lo: Byte, hi: Byte): Double {
            return ((hi.toInt() shl 8) or (lo.toInt() and 0xFF)).toShort().toDouble()
        }

        val rawGyroX = s16(frame[4], frame[5])
        val rawGyroY = s16(frame[6], frame[7])
        val rawGyroZ = s16(frame[8], frame[9])

        val rawAccX = s16(frame[10], frame[11])
        val rawAccY = s16(frame[12], frame[13])
        val rawAccZ = s16(frame[14], frame[15])

        val ax = rawAccX / ACCEL_SENSITIVITY_4G
        val ay = rawAccY / ACCEL_SENSITIVITY_4G
        val az = rawAccZ / ACCEL_SENSITIVITY_4G

        val gx = rawGyroX / GYRO_SENSITIVITY_500DPS
        val gy = rawGyroY / GYRO_SENSITIVITY_500DPS
        val gz = rawGyroZ / GYRO_SENSITIVITY_500DPS

        val calAccX = -ay
        val calAccY = -ax
        val calAccZ = -az

        val calGyroX = -gy
        val calGyroY = -gx
        val calGyroZ = -gz

        listener.onSampleReceived(ImuSample(calAccX, calAccY, calAccZ, calGyroX, calGyroY, calGyroZ))
    }

    private fun send(cmd: Byte) = send(byteArrayOf(cmd))

    private fun send(bytes: ByteArray) {
        try {
            output?.write(bytes)
            output?.flush()
        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).post { listener.onError("Errore invio: ${e.message}") }
        }
    }

    fun disconnect() {
        streaming = false
        connected = false
        try {
            input?.close()
            output?.close()
        } catch (_: Exception) {}
        listener.onDisconnected()
    }
}