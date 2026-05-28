package com.example.myapplication.BT.ring

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import java.util.Calendar

object SmartRingProtocol {
    private const val POLYNOMIAL = 0x1021

    fun calculateCRC16(data: ByteArray): ByteArray {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF shl 8) and 0xFFFF
            for (i in 0..7) {
                crc = if (crc and 0x8000 != 0) (crc shl 1 xor POLYNOMIAL) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())
    }

    fun buildPacket(id: Byte, key: Byte, payload: ByteArray): ByteArray {
        val totalLength = payload.size + 6
        val header = byteArrayOf(id, key, (totalLength and 0xFF).toByte(), ((totalLength ushr 8) and 0xFF).toByte())
        val packetWithoutCrc = header + payload
        return packetWithoutCrc + calculateCRC16(packetWithoutCrc)
    }

    // --- NUOVA FUNZIONE PER RICHIESTA BATTERIA ---
    fun buildBatteryRequestPacket(): ByteArray {
        // Command ID 0x02, Key 0x00, Payload 0x47 0x43
        return buildPacket(0x02.toByte(), 0x00.toByte(), byteArrayOf(0x47.toByte(), 0x43.toByte()))
    }

    fun buildUserInfoPayload(context: Context): ByteArray {
        val auth = FirebaseAuth.getInstance()
        val userId = auth.currentUser?.uid ?: "user"
        val sharedPref = context.getSharedPreferences("UserData_$userId", Context.MODE_PRIVATE)

        val gender = sharedPref.getInt("gender_pos", 0).toByte()
        val age = (sharedPref.getString("age", "25")?.toIntOrNull() ?: 25).toByte()
        val height = (sharedPref.getString("height", "175")?.toIntOrNull() ?: 175).toByte()
        val weight = (sharedPref.getString("weight", "70")?.toIntOrNull() ?: 70).toByte()

        val stepTargetLow = (10000 and 0xFF).toByte()
        val stepTargetHigh = ((10000 ushr 8) and 0xFF).toByte()

        val cal = Calendar.getInstance()
        val year = (cal.get(Calendar.YEAR) - 2000).toByte()
        val month = (cal.get(Calendar.MONTH) + 1).toByte()
        val day = cal.get(Calendar.DAY_OF_MONTH).toByte()
        val hour = cal.get(Calendar.HOUR_OF_DAY).toByte()
        val minute = cal.get(Calendar.MINUTE).toByte()
        val second = cal.get(Calendar.SECOND).toByte()

        return byteArrayOf(
            gender, age, height, weight,
            stepTargetLow, stepTargetHigh,
            year, month, day, hour, minute, second
        )
    }
}