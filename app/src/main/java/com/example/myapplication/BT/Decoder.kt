package com.example.myapplication

object Decoder {
    data class DecodedResult(val value: Int = 0, val sys: Int = 0, val dia: Int = 0, val type: String)

    fun decode(hex: String): DecodedResult? {
        val parts = hex.trim().split(" ")
        if (parts.size < 2) return null

        // Standby durante il calcolo
        if (parts[0] == "FF" && parts[1] == "FF") return DecodedResult(type = "WAIT")

        if (parts.size >= 5 && parts[0] == "06") {
            return try {
                when (parts[1]) {
                    "01" -> DecodedResult(value = parts[4].toInt(16), type = "BPM")
                    "02" -> DecodedResult(value = parts[4].toInt(16), type = "SPO2")
                    "03" -> {
                        // Protocollo desktop: Byte 4 = Sys, Byte 5 = Dia
                        val s = parts[4].toInt(16)
                        val d = if (parts.size > 5) parts[5].toInt(16) else 0
                        DecodedResult(sys = s, dia = d, type = "BP")
                    }
                    else -> null
                }
            } catch (e: Exception) { null }
        }

        // Rilevamento fine misurazione (ACK richiesto)
        if (parts[0] == "04" && parts[1] == "0E") return DecodedResult(type = "END_ACK")

        return null
    }
}