package com.example.myapplication

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import java.util.Calendar

object SmartRingProtocol {
    private const val POLYNOMIAL = 0x1021

    // --- FUNZIONI ESISTENTI (Mantienile) ---
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

    // --- NUOVA FUNZIONE PER I DATI UTENTE ---
    fun buildUserInfoPayload(context: Context): ByteArray {
        val auth = FirebaseAuth.getInstance()
        val userId = auth.currentUser?.uid ?: "user"
        val sharedPref = context.getSharedPreferences("UserData_$userId", Context.MODE_PRIVATE)

        // Recupero dei dati con i valori di fallback (default) se non presenti
        val gender = sharedPref.getInt("gender_pos", 0).toByte() // 0=Uomo, 1=Donna
        val age = (sharedPref.getString("age", "25")?.toIntOrNull() ?: 25).toByte()
        val height = (sharedPref.getString("height", "175")?.toIntOrNull() ?: 175).toByte()
        val weight = (sharedPref.getString("weight", "70")?.toIntOrNull() ?: 70).toByte()

        // Obiettivo passi (default 10.000 passi = 0x2710)
        val stepTargetLow = (10000 and 0xFF).toByte()
        val stepTargetHigh = ((10000 ushr 8) and 0xFF).toByte()

        // Sincronizzazione Orario
        val cal = Calendar.getInstance()
        val year = (cal.get(Calendar.YEAR) - 2000).toByte()
        val month = (cal.get(Calendar.MONTH) + 1).toByte()
        val day = cal.get(Calendar.DAY_OF_MONTH).toByte()
        val hour = cal.get(Calendar.HOUR_OF_DAY).toByte()
        val minute = cal.get(Calendar.MINUTE).toByte()
        val second = cal.get(Calendar.SECOND).toByte()

        // Costruzione del payload (12 byte totali)
        return byteArrayOf(
            gender, age, height, weight,
            stepTargetLow, stepTargetHigh,
            year, month, day, hour, minute, second
        )
    }
}