package com.example.myapplication

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PredictionResult(
    val status: String,
    val prediction: String,
    val predictionIndex: Int,
    val confidence: Double,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val windowSize: Int,
    val channels: Int
)

class PythonRealtimeClient(
    baseUrl: String
) {
    private val cleanBaseUrl = baseUrl.trimEnd('/')
    private val endpointUrl = "$cleanBaseUrl/predict"
    private val healthUrl = "$cleanBaseUrl/health"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun sendWindow(
        window: AccelWindow,
        onResult: ((PredictionResult) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val payload = buildPayload(window)

        val request = Request.Builder()
            .url(endpointUrl)
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        Thread {
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        onError?.invoke("HTTP ${response.code}: $body")
                        return@use
                    }
                    onResult?.invoke(parsePredictionResponse(body))
                }
            } catch (e: Exception) {
                onError?.invoke("${e.javaClass.simpleName}: ${e.message} | url=$endpointUrl")
            }
        }.start()
    }

    fun checkHealth(
        onResult: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val request = Request.Builder()
            .url(healthUrl)
            .addHeader("ngrok-skip-browser-warning", "true")
            .get()
            .build()

        Thread {
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        onError?.invoke("HTTP ${response.code}: $body")
                        return@use
                    }
                    onResult?.invoke(body)
                }
            } catch (e: Exception) {
                onError?.invoke("${e.javaClass.simpleName}: ${e.message} | url=$healthUrl")
            }
        }.start()
    }

    private fun buildPayload(window: AccelWindow): JSONObject {
        val dataArray = JSONArray()

        // 3 canali: acc_x, acc_y, acc_z
        window.data.forEach { row ->
            val jsonRow = JSONArray()
            jsonRow.put(row[0])
            jsonRow.put(row[1])
            jsonRow.put(row[2])
            dataArray.put(jsonRow)
        }

        return JSONObject().apply {
            put("start_timestamp", window.startTimestamp)
            put("end_timestamp", window.endTimestamp)
            put("window_size", window.windowSize)
            put("channels", window.channels)
            put("data", dataArray)
        }
    }

    private fun parsePredictionResponse(body: String): PredictionResult {
        val json = JSONObject(body)
        return PredictionResult(
            status = json.optString("status"),
            prediction = json.optString("prediction"),
            predictionIndex = json.optInt("prediction_index", -1),
            confidence = json.optDouble("confidence", 0.0),
            startTimestamp = json.optLong("start_timestamp", 0L),
            endTimestamp = json.optLong("end_timestamp", 0L),
            windowSize = json.optInt("window_size", 0),
            channels = json.optInt("channels", 0)
        )
    }
}