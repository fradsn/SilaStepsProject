package com.example.myapplication

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
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
        private const val TAG = "ShimmerClassicManager"
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        private const val CMD_START_STREAM: Byte = 0x07
        private const val CMD_STOP_STREAM: Byte = 0x20
        private const val CMD_SET_SENSORS: Byte = 0x08
        private const val CMD_SET_SR: Byte = 0x05
        private const val CMD_SET_ACCEL_RANGE: Byte = 0x09
        private const val CMD_SET_GYRO_RANGE: Byte = 0x49

        @Volatile
        private var INSTANCE: ShimmerClassicManager? = null

        fun getInstance(
            context: Context,
            macAddress: String,
            listener: ShimmerListener
        ): ShimmerClassicManager {
            return synchronized(this) {
                val current = INSTANCE
                if (current != null && current.getAddress() == macAddress) {
                    current.updateListener(listener)
                    current
                } else {
                    current?.disconnect(silent = true)
                    val newInstance = ShimmerClassicManager(
                        context.applicationContext,
                        macAddress,
                        listener
                    )
                    INSTANCE = newInstance
                    newInstance
                }
            }
        }

        fun getActiveInstance(): ShimmerClassicManager? = INSTANCE

        fun clearInstance(instance: ShimmerClassicManager) {
            synchronized(this) {
                if (INSTANCE === instance) {
                    INSTANCE = null
                }
            }
        }
    }

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var input: InputStream? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var readThread: Thread? = null

    @Volatile
    private var connected = false

    @Volatile
    private var setup = false

    @Volatile
    private var streaming = false

    @Volatile
    private var connecting = false

    private val frameBuffer = mutableListOf<Byte>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun updateListener(newListener: ShimmerListener) {
        this.listener = newListener
    }

    fun isConnected(): Boolean = connected
    fun isStreaming(): Boolean = streaming
    fun getAddress(): String = macAddress

    fun connect() {
        if (connecting || connected) {
            Log.d(TAG, "connect() ignorato: connecting=$connecting connected=$connected")
            return
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            postError("Bluetooth non supportato")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                postError("Permesso BLUETOOTH_CONNECT mancante")
                return
            }
        }

        val device = try {
            adapter.getRemoteDevice(macAddress)
        } catch (e: IllegalArgumentException) {
            postError("MAC address non valido: $macAddress")
            return
        }

        connecting = true

        thread(name = "ShimmerConnect") {
            try {
                closeResources(notify = false)

                if (adapter.isDiscovering) {
                    adapter.cancelDiscovery()
                }

                val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                newSocket.connect()

                socket = newSocket
                input = newSocket.inputStream
                output = newSocket.outputStream
                connected = true
                setup = false
                streaming = false
                frameBuffer.clear()

                mainHandler.post {
                    listener.onConnected()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connessione fallita", e)
                closeResources(notify = false)
                postError("Connessione fallita: ${e.message}")
            } finally {
                connecting = false
            }
        }
    }

    fun setupShimmer() {
        if (!connected || input == null || output == null) {
            postError("Shimmer non connesso")
            return
        }

        thread(name = "ShimmerSetup") {
            try {
                val commands = listOf(
                    byteArrayOf(CMD_SET_SR, 0x90.toByte(), 0x02.toByte()),
                    byteArrayOf(CMD_SET_SENSORS, 0xC0.toByte(), 0x00, 0x00),
                    byteArrayOf(CMD_SET_ACCEL_RANGE, 0x01.toByte()),
                    byteArrayOf(CMD_SET_GYRO_RANGE, 0x01.toByte())
                )

                for (cmd in commands) {
                    send(cmd)
                    val ack = readAck()
                    val ackHex = ack?.let { "%02X".format(it.toInt() and 0xFF) } ?: "null"

                    if (ack != 0xFF.toByte()) {
                        postError("Errore setup: cmd=${"%02X".format(cmd[0])}, ack=$ackHex")
                        return@thread
                    }

                    Thread.sleep(100)
                }

                setup = true
                mainHandler.post {
                    Toast.makeText(
                        context,
                        "Shimmer configurato (1Hz, ±4g, 500dps)",
                        Toast.LENGTH_SHORT
                    ).show()
                    listener.onSetup()
                }
            } catch (e: Exception) {
                postError("Errore setup: ${e.message}")
            }
        }
    }

    private fun readAck(timeoutMs: Long = 1000): Byte? {
        val inputStream = input ?: return null
        val start = System.currentTimeMillis()
        while (connected && inputStream.available() == 0) {
            if (System.currentTimeMillis() - start > timeoutMs) return null
            Thread.sleep(10)
        }
        return if (connected) inputStream.read().toByte() else null
    }

    fun startStreaming() {
        if (!connected) {
            postError("Shimmer non connesso")
            return
        }
        if (!setup) {
            postError("Setup incompleto")
            return
        }
        if (streaming) return

        send(CMD_START_STREAM)
        startReaderThread()
    }

    fun stopStreaming() {
        if (!connected) return
        streaming = false
        try {
            send(CMD_STOP_STREAM)
        } catch (_: Exception) {
        }
    }

    private fun startReaderThread() {
        if (readThread?.isAlive == true || streaming) return

        streaming = true
        readThread = thread(name = "ShimmerReader") {
            val buffer = ByteArray(1024)

            while (streaming && connected) {
                try {
                    val len = input?.read(buffer) ?: -1
                    if (len > 0) {
                        onBytesReceived(buffer.copyOf(len))
                    } else if (len == -1) {
                        Log.d(TAG, "Socket chiuso dal device")
                        break
                    }
                } catch (e: Exception) {
                    if (streaming && connected) {
                        postError("Errore lettura: ${e.message}")
                    }
                    break
                }
            }

            if (connected) {
                disconnect()
            }
        }
    }

    private fun onBytesReceived(bytes: ByteArray) {
        synchronized(frameBuffer) {
            frameBuffer.addAll(bytes.toList())
            extractFrames()
        }
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

        val accSensi = 819.0
        val gyroSensi = 65.5

        val ax = rawAccX / accSensi
        val ay = rawAccY / accSensi
        val az = rawAccZ / accSensi

        val gx = rawGyroX / gyroSensi
        val gy = rawGyroY / gyroSensi
        val gz = rawGyroZ / gyroSensi

        val calAccX = -ay
        val calAccY = -ax
        val calAccZ = -az

        val calGyroX = -gy
        val calGyroY = -gx
        val calGyroZ = -gz

        Log.d(
            "SHIMMER_FINAL",
            "ACC [g]: X=%.3f, Y=%.3f, Z=%.3f".format(calAccX, calAccY, calAccZ)
        )
        Log.d(
            "SHIMMER_FINAL",
            "GYRO [°/s]: X=%.3f, Y=%.3f, Z=%.3f".format(calGyroX, calGyroY, calGyroZ)
        )

        listener.onSampleReceived(
            ImuSample(
                calAccX,
                calAccY,
                calAccZ,
                calGyroX,
                calGyroY,
                calGyroZ
            )
        )
    }

    private fun send(cmd: Byte) = send(byteArrayOf(cmd))

    private fun send(bytes: ByteArray) {
        try {
            output?.write(bytes)
            output?.flush()
        } catch (e: Exception) {
            postError("Errore invio: ${e.message}")
        }
    }

    fun disconnect() {
        disconnect(silent = false)
    }

    fun disconnect(silent: Boolean = false) {
        val wasConnected = connected || connecting || socket != null

        streaming = false
        setup = false
        connecting = false

        closeResources(notify = false)
        clearInstance(this)

        if (!silent && wasConnected) {
            mainHandler.post {
                listener.onDisconnected()
            }
        }
    }

    private fun closeResources(notify: Boolean = false) {
        try {
            readThread?.interrupt()
        } catch (_: Exception) {
        }
        readThread = null

        try {
            input?.close()
        } catch (_: Exception) {
        }
        input = null

        try {
            output?.close()
        } catch (_: Exception) {
        }
        output = null

        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null

        connected = false
        streaming = false
        setup = false

        synchronized(frameBuffer) {
            frameBuffer.clear()
        }

        if (notify) {
            mainHandler.post {
                listener.onDisconnected()
            }
        }
    }

    private fun postError(msg: String) {
        mainHandler.post {
            listener.onError(msg)
        }
    }
}