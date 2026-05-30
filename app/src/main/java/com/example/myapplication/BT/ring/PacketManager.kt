package com.example.myapplication.BT.ring

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import java.util.Calendar
import java.util.UUID

object PacketManager {

    val SERVICE_UUID: UUID = UUID.fromString("BE940000-7333-BE46-B7AE-689E71722BD5")
    val CHAR_COMMAND_CONTROL: UUID = UUID.fromString("BE940001-7333-BE46-B7AE-689E71722BD5")
    val CHAR_DATA_UPLOAD: UUID = UUID.fromString("BE940003-7333-BE46-B7AE-689E71722BD5")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    fun buildPacket(cmdId: Byte, key: Byte, payload: ByteArray = byteArrayOf()): ByteArray {
        val length = 6 + payload.size
        val header = byteArrayOf(cmdId, key, (length and 0xFF).toByte(), ((length shr 8) and 0xFF).toByte())
        val body = header + payload
        val crc = crc16(body)
        return body + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    fun buildUserInfoPayload(context: Context): ByteArray {
        val auth = FirebaseAuth.getInstance()
        val userId = auth.currentUser?.uid ?: "user"
        val sharedPref = context.getSharedPreferences("UserData_$userId", Context.MODE_PRIVATE)

        val gender = sharedPref.getInt("gender_pos", 0).toByte()
        val age = (sharedPref.getString("age", "25")?.toIntOrNull() ?: 25).toByte()
        val height = ((sharedPref.getString("height", "175")?.toIntOrNull() ?: 175) and 0xFF).toByte()
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

        return byteArrayOf(gender, age, height, weight, stepTargetLow, stepTargetHigh, year, month, day, hour, minute, second)
    }
}