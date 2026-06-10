package com.example.myapplication.BT.ring

object Decoder {
    data class DecodedResult(
        val value: Int = 0,
        val sys: Int = 0,
        val dia: Int = 0,
        val battery: Int = -1,
        val chargingStatus: Int = 0,
        val calibrationStatus: Int = -1, // -1 = non presente, 0 = successo, >0 = errore
        val type: String
    )

    fun decode(hex: String): DecodedResult? {
        val parts = hex.trim().split(" ")
        if (parts.size < 4) return null

        // Standby durante il calcolo
        if (parts[0] == "FF" && parts[1] == "FF") return DecodedResult(type = "WAIT")

        // Decodifica Risposta Informazioni Base / Batteria
        if (parts[0] == "02" && parts[1] == "00" && parts.size >= 10) {
            return try {
                val status = parts[8].toInt(16) // Battery Status (0=normale, 1=bassa, 2=carica, 3=pieno)
                val level = parts[9].toInt(16)  // Battery Level (0-100)
                DecodedResult(battery = level, chargingStatus = status, type = "BATTERY")
            } catch (e: Exception) { null }
        }

        // Decodifica Risposta Calibrazione Pressione (Sezione 3.5.4.4)
        // L'anello risponde con ID=03, KEY=03. Il byte del risultato è in parts[4]
        if (parts[0] == "03" && parts[1] == "03" && parts.size >= 5) {
            return try {
                val resCode = parts[4].toInt(16) // 0x00=Successo, 0x01=Parametri errati, 0x02=Dispositivo non in misurazione
                DecodedResult(calibrationStatus = resCode, type = "CALIBRATION_RESULT")
            } catch (e: Exception) { null }
        }

        if (parts.size >= 5 && parts[0] == "06") {
            return try {
                when (parts[1]) {
                    "01" -> DecodedResult(value = parts[4].toInt(16), type = "BPM")
                    "02" -> {
                        val o2Value = parts[4].toInt(16)
                        // Se il valore è 0, la misurazione dell'ossigeno è fallita
                        if (o2Value == 0) {
                            DecodedResult(type = "MEASUREMENT_FAILED")
                        } else {
                            DecodedResult(value = o2Value, type = "SPO2")
                        }
                    }
                    "03" -> {
                        val s = parts[4].toInt(16)
                        val d = if (parts.size > 5) parts[5].toInt(16) else 0
                        if (s == 0 && d == 0) {
                            DecodedResult(sys = 0, dia = 0, type = "MEASUREMENT_FAILED")
                        } else {
                            DecodedResult(sys = s, dia = d, type = "BP")
                        }
                    }
                    else -> null
                }
            } catch (e: Exception) { null }
        }

        if (parts[0] == "04" && parts[1] == "0E") return DecodedResult(type = "END_ACK")

        return null
    }
}