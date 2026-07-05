package com.example.myapplication.network

import com.google.gson.annotations.SerializedName

data class AwsRecord(
    @SerializedName("userId") val userId: String,
    @SerializedName("x") val x: Double,
    @SerializedName("y") val y: Double,
    @SerializedName("z") val z: Double,
    @SerializedName("activity") val activity: String,
    @SerializedName("heartRate") val heartRate: Int,
    @SerializedName("spo2") val spo2: Int,
    @SerializedName("bloodPressure") val bloodPressure: String,
    @SerializedName("timestamp") val timestamp: String // Formato ISO 8601 UTC
)

data class AwsSyncPayload(
    @SerializedName("records") val records: List<AwsRecord>
)