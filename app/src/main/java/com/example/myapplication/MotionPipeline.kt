package com.example.myapplication

import android.util.Log
import kotlin.math.sqrt

class MotionPipeline(
    private val pythonClient: PythonRealtimeClient,
    private val listener: Listener? = null
) : ShimmerClassicManager.ShimmerListener {

    companion object {
        private const val TAG = "MotionPipeline"

        // Soglia di deviazione standard del modulo acc sotto la quale la finestra è "ferma"
        // Valore in g (i dati Shimmer sono divisi per 819.0, quindi ~1g = 1.0)
        private const val STILLNESS_STD_THRESHOLD = 0.04f

        // Confidenza minima sotto la quale non aggiorniamo la UI
        private const val MIN_CONFIDENCE = 0.55f
    }

    interface Listener {
        fun onShimmerConnected()
        fun onShimmerDisconnected()
        fun onShimmerReady()
        fun onWindowCreated(window: AccelWindow)
        fun onPredictionReceived(result: PredictionResult)
        fun onMotionError(message: String)
    }

    private val windowBuffer = SlidingWindowBuffer(
        windowSize = 100,
        stepSize = 25
    )

    private var sampleCount = 0L
    private var firstSampleTime = 0L

    override fun onConnected() {
        listener?.onShimmerConnected()
    }

    override fun onDisconnected() {
        windowBuffer.clear()
        sampleCount = 0L
        firstSampleTime = 0L
        listener?.onShimmerDisconnected()
    }

    override fun onSetup() {
        listener?.onShimmerReady()
    }

    override fun onError(msg: String) {
        listener?.onMotionError(msg)
    }

    override fun onSampleReceived(sample: ImuSample) {
        val now = System.currentTimeMillis()
        if (firstSampleTime == 0L) firstSampleTime = now
        sampleCount++
        if (sampleCount % 50 == 0L) {
            val elapsed = (now - firstSampleTime) / 1000.0
            val hz = if (elapsed > 0) sampleCount / elapsed else 0.0
            Log.d(TAG, "Sample rate reale: %.1f Hz (totale campioni: $sampleCount)".format(hz))
        }

        // Solo accelerometro: 3 canali
        val imuSample = AccelSample(
            timestamp = now,
            x = sample.accX.toFloat(),
            y = sample.accY.toFloat(),
            z = sample.accZ.toFloat()
        )

        val window = windowBuffer.addSample(imuSample) ?: return

        listener?.onWindowCreated(window)

        val isStill = isWindowStill(window)
        if (isStill) {
            Log.d(TAG, "Finestra statica rilevata (std bassa), Skip invio al server")
            return
        }

        pythonClient.sendWindow(
            window = window,
            onResult = { result ->
                if (result.confidence >= MIN_CONFIDENCE) {
                    listener?.onPredictionReceived(result)
                } else {
                    Log.d(TAG, "Confidenza bassa (${result.confidence}), predizione scartata: ${result.prediction}")
                }
            },
            onError = { error ->
                listener?.onMotionError(error)
            }
        )
    }

    fun reset() {
        windowBuffer.clear()
        sampleCount = 0L
        firstSampleTime = 0L
    }

    private fun isWindowStill(window: AccelWindow): Boolean {
        val mags = FloatArray(window.windowSize) { i ->
            val x = window.data[i][0]
            val y = window.data[i][1]
            val z = window.data[i][2]
            sqrt(x * x + y * y + z * z)
        }
        val mean = mags.average().toFloat()
        val variance = mags.map { (it - mean) * (it - mean) }.average().toFloat()
        val std = sqrt(variance)
        Log.d(TAG, "Acc magnitude std = %.4f".format(std))
        return std < STILLNESS_STD_THRESHOLD
    }
}