package com.example.myapplication

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
}