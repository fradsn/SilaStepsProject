package com.example.myapplication.network

import com.google.gson.annotations.SerializedName

data class AwsRecord(
    @SerializedName("userId") val userId: String,
    @SerializedName("activity") val activity: String,
    @SerializedName("heartRate") val heartRate: Int,
    @SerializedName("spo2") val spo2: Int,
    @SerializedName("bloodPressure") val bloodPressure: String,
    @SerializedName("latitude") val latitude: Double,    // Coordinate GPS (es. 39.2983)
    @SerializedName("longitude") val longitude: Double,  // Coordinate GPS (es. 16.2530)
    @SerializedName("steps") val steps: Int,             // Numero di passi (es. 1540)
    @SerializedName("alert") val alert: String,          // Stato allarme (es. "normal", "tachycardia")

    @SerializedName("timestamp") val timestamp: String   // Formato ISO 8601 UTC
)

data class AwsSyncPayload(
    @SerializedName("records") val records: List<AwsRecord>
)