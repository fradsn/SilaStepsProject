package com.example.myapplication.Motion.pipeline

import android.content.Context
import android.util.Log
import com.example.myapplication.ImuSample
import com.example.myapplication.Motion.model.AccelSample
import com.example.myapplication.Motion.model.AccelWindow
import com.example.myapplication.Motion.tflite.Classifier
import com.example.myapplication.Motion.tflite.LocalPredictionResult
import com.example.myapplication.ShimmerClassicManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MotionPipeline(
    private val context: Context,
    private val listener: Listener? = null
) : ShimmerClassicManager.ShimmerListener {

    interface Listener {
        fun onShimmerConnected()
        fun onShimmerDisconnected()
        fun onShimmerReady()
        fun onWindowCreated(window: AccelWindow)
        fun onPredictionReceived(result: LocalPredictionResult)
        fun onMotionError(message: String)
    }

    companion object {
        private const val TAG = "MotionPipeline"
    }

    private var inferenceEnabled = false
    private var classifier: Classifier? = null

    private val inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TFLiteWorker").apply { isDaemon = true }
    }

    private val inferenceRunning = AtomicBoolean(false)

    private val windowBuffer = SlidingWindowBuffer(
        windowSize = 100,
        stepSize = 50
    )

    fun setInferenceEnabled(enabled: Boolean) {
        inferenceEnabled = enabled

        if (enabled && classifier == null) {
            classifier = Classifier(context.applicationContext)
            Log.d(TAG, "Classifier initialized")
        }

        if (!enabled) {
            inferenceRunning.set(false)
            classifier?.close()
            classifier = null
            Log.d(TAG, "Inference disabled")
        }
    }

    override fun onConnected() {
        listener?.onShimmerConnected()
    }

    override fun onDisconnected() {
        windowBuffer.clear()
        inferenceRunning.set(false)
        listener?.onShimmerDisconnected()
    }

    override fun onSetup() {
        listener?.onShimmerReady()
    }

    override fun onError(msg: String) {
        listener?.onMotionError(msg)
    }

    override fun onSampleReceived(sample: ImuSample) {
        val accelSample = AccelSample(
            timestamp = System.currentTimeMillis(),
            x = sample.accX.toFloat(),
            y = sample.accY.toFloat(),
            z = sample.accZ.toFloat()
        )

        val window = windowBuffer.addSample(accelSample) ?: return
        listener?.onWindowCreated(window)

        if (!inferenceEnabled) return

        if (!inferenceRunning.compareAndSet(false, true)) {
            return
        }

        inferenceExecutor.execute {
            try {
                val localClassifier = classifier
                if (localClassifier == null) {
                    inferenceRunning.set(false)
                    return@execute
                }

                val result = localClassifier.predict(window.data)
                listener?.onPredictionReceived(result)
            } catch (e: Exception) {
                listener?.onMotionError("Errore inferenza locale: ${e.message}")
            } finally {
                inferenceRunning.set(false)
            }
        }
    }

    fun reset() {
        windowBuffer.clear()
    }

    fun close() {
        inferenceRunning.set(false)
        classifier?.close()
        classifier = null
        windowBuffer.clear()
        inferenceExecutor.shutdownNow()
    }
}