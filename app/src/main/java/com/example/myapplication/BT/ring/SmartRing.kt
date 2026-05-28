package com.example.myapplication.BT.ring

object SmartRing {

    // Gli stessi UUID del tuo Python
    const val SERVICE_UUID         = "BE940000-7333-BE46-B7AE-689E71722BD5"
    const val CHAR_CONTROL         = "BE940001-7333-BE46-B7AE-689E71722BD5" // Indicate
    const val CHAR_WRITE           = "BE940002-7333-BE46-B7AE-689E71722BD5" // WriteNoResp
    const val CHAR_BULK            = "BE940003-7333-BE46-B7AE-689E71722BD5" // Indicate

    // CRC-16/CCITT-XMODEM — identico al tuo crc16_compute()
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

    // Equivalente di build_packet()
    fun buildPacket(cmdId: Byte, key: Byte, payload: ByteArray = byteArrayOf()): ByteArray {
        val length = 6 + payload.size
        val header = byteArrayOf(
            cmdId.toByte(),
            key.toByte(),
            (length and 0xFF).toByte(),
            ((length shr 8) and 0xFF).toByte()
        )
        val body = header + payload
        val crc  = crc16(body)
        return body + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    // Parsing del pacchetto ricevuto
    data class ParsedPacket(val cmdId: Int, val key: Int, val payload: ByteArray)

    fun parsePacket(data: ByteArray): ParsedPacket? {
        if (data.size < 6) return null
        val length = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        if (data.size < length) return null
        val body     = data.copyOfRange(0, length - 2)
        val crcBytes = data.copyOfRange(length - 2, length)
        val crcCalc  = crc16(body)
        val crcRecv  = (crcBytes[0].toInt() and 0xFF) or ((crcBytes[1].toInt() and 0xFF) shl 8)
        if (crcCalc != crcRecv) return null
        val payload  = if (length > 6) data.copyOfRange(4, length - 2) else byteArrayOf()
        return ParsedPacket(data[0].toInt() and 0xFF, data[1].toInt() and 0xFF, payload)
    }
}