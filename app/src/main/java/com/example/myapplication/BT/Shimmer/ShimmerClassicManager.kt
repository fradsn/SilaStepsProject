package com.example.myapplication

import android.bluetooth.BluetoothAdapter
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

class ShimmerClassicManager(
    private val context: Context,
    private val macAddress: String,
    private val listener: ShimmerListener
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

        // Costanti di Calibrazione per Shimmer3 (v1.0.47)
        // Range ±4g -> Sensibilità = 819.0 LSB/g (Low Noise Accel)
        private const val ACCEL_SENSITIVITY_4G = 819.0
        // Range 500 dps -> Sensibilità = 65.5 LSB/(deg/s)
        private const val GYRO_SENSITIVITY_500DPS = 65.5

        // Matrice di allineamento standard Shimmer3 (corregge l'orientamento del chip rispetto al case)
        private val ALIGN_ACC = arrayOf(
            doubleArrayOf(0.0, -1.0, 0.0),
            doubleArrayOf(-1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, -1.0)
        )
        private val ALIGN_GYRO = arrayOf(
            doubleArrayOf(0.0, -1.0, 0.0),
            doubleArrayOf(-1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, -1.0)
        )
    }

    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var readThread: Thread? = null
    private var connected = false
    private var setup = false
    private var streaming = false
    private val frameBuffer = mutableListOf<Byte>()

    fun isConnected(): Boolean = connected
    fun getAddress(): String = macAddress

    fun connect() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val device = adapter.getRemoteDevice(macAddress)

        thread {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
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

        33

        thread {
            val commands = listOf(
                byteArrayOf(CMD_SET_SENSORS, 0xC0.toByte(), 0x00, 0x00),
                byteArrayOf(CMD_SET_ACCEL_RANGE, 0x01.toByte()),
                byteArrayOf(CMD_SET_GYRO_RANGE, 0x01.toByte())
            )

            for (cmd in commands) {
                send(cmd)
                val ack = readAck()
                val ackHex = ack?.let { "%02X".format(it.toInt() and 0xFF) } ?: "null"

                if (ack != 0xFF.toByte()) {
                    Handler(Looper.getMainLooper()).post {
                        listener.onError("Errore setup: cmd=${"%02X".format(cmd[0])}, ack=$ackHex")
                    }
                    return@thread
                }

                Thread.sleep(100)
            }

            setup = true
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Shimmer configurato", Toast.LENGTH_SHORT).show()
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
                        break // Connessione chiusa
                    }
                } catch (e: Exception) {
                    if (streaming) {
                        Handler(Looper.getMainLooper()).post {
                            listener.onError("Errore lettura: ${e.message}")
                        }
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
        // Pacchetto LogAndStream standard per Accel LN + Gyro:
        // 1 (Packet Type) + 3 (Timestamp) + 6 (Accel) + 6 (Gyro) = 16 byte
        val frameSize = 16

        while (frameBuffer.size >= frameSize) {
            // Shimmer LogAndStream DATA_PACKET è sempre 0x00
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

        // Byte 4-9: Giroscopio
        val rawGyroX = s16(frame[4], frame[5])
        val rawGyroY = s16(frame[6], frame[7])
        val rawGyroZ = s16(frame[8], frame[9])

        // Byte 10-15: Accelerometro Low Noise
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

        Log.d("SHIMMER_FINAL", "ACC [m/s^2]: X=%.3f, Y=%.3f, Z=%.3f".format(calAccX, calAccY, calAccZ))
        Log.d("SHIMMER_FINAL", "GYRO [°/s]: X=%.3f, Y=%.3f, Z=%.3f".format(calGyroX, calGyroY, calGyroZ))

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

    private fun calibrate(
        raw: DoubleArray,
        align: Array<DoubleArray>,
        sensitivity: Double,
        offset: Double
    ): DoubleArray {
        val calibrated = DoubleArray(3)
        for (i in 0..2) {
            val value = (raw[i] - offset) / sensitivity
            for (j in 0..2) {
                calibrated[j] += align[j][i] * value
            }
        }
        return calibrated
    }

    private fun send(cmd: Byte) = send(byteArrayOf(cmd))

    private fun send(bytes: ByteArray) {
        try {
            output?.write(bytes)
            output?.flush()
        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).post {
                listener.onError("Errore invio: ${e.message}")
            }
        }
    }

    fun disconnect() {
        streaming = false
        connected = false
        try {
            input?.close()
            output?.close()
        } catch (_: Exception) {
        }
        listener.onDisconnected()
    }
}