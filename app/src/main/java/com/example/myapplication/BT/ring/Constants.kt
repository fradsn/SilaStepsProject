package com.example.myapplication.BT.ring

import java.util.UUID

object Constants {
    val SERVICE_UUID: UUID = UUID.fromString("BE940000-7333-BE46-B7AE-689E71722BD5")
    val CHAR_COMMAND_CONTROL: UUID = UUID.fromString("BE940001-7333-BE46-B7AE-689E71722BD5")
    val CHAR_DATA_UPLOAD: UUID = UUID.fromString("BE940003-7333-BE46-B7AE-689E71722BD5")
}