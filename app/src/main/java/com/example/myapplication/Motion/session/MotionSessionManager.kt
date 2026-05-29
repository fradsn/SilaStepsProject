package com.example.myapplication.Motion.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.myapplication.Motion.model.AccelWindow
import com.example.myapplication.Motion.pipeline.MotionPipeline
import com.example.myapplication.Motion.tflite.LocalPredictionResult
import com.example.myapplication.BT.Shimmer.ShimmerClassicManager
import java.util.concurrent.CopyOnWriteArraySet

data class MotionUiState(
    val shimmerConnected: Boolean = false,
    val shimmerReady: Boolean = false,
    val streaming: Boolean = false,
    val shimmerAddress: String? = null,
    val currentActivity: String = "Waiting...",
    val confidencePercent: Int = 0,
    val sessionStartMillis: Long = 0L,
    val lastError: String? = null
)

object MotionSessionManager : MotionPipeline.Listener {

    interface Observer {
        fun onMotionStateChanged(state: MotionUiState)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArraySet<Observer>()

    private var appContext: Context? = null
    private var motionPipeline: MotionPipeline? = null
    private var shimmerManager: ShimmerClassicManager? = null

    @Volatile
    private var state = MotionUiState()

    private val activityCounts = mutableMapOf(
        "Walking" to 0,
        "Jogging" to 0,
        "Sitting" to 0,
        "Standing" to 0
    )

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (motionPipeline == null) {
            motionPipeline = MotionPipeline(
                context = appContext!!,
                listener = this
            )
        }
    }

    fun addObserver(observer: Observer) {
        observers.add(observer)
        notifyObserver(observer, state)
    }

    fun removeObserver(observer: Observer) {
        observers.remove(observer)
    }

    fun getState(): MotionUiState = state

    fun isShimmerConnected(): Boolean = shimmerManager?.isConnected() == true

    fun getShimmerAddress(): String? = shimmerManager?.getAddress()

    fun setInferenceEnabled(enabled: Boolean) {
        motionPipeline?.setInferenceEnabled(enabled)
    }

    fun getActivityCounts(): Map<String, Int> = activityCounts.toMap()

    fun resetActivityCounts() {
        activityCounts.keys.forEach { key ->
            activityCounts[key] = 0
        }
        notifyAllObservers(state)
    }

    fun connectToShimmer(context: Context, macAddress: String) {
        initialize(context)

        shimmerManager?.disconnect()
        resetActivityCounts()

        shimmerManager = ShimmerClassicManager.getInstance(
            context = context.applicationContext,
            macAddress = macAddress,
            listener = motionPipeline ?: return
        )

        updateState {
            copy(
                shimmerConnected = false,
                shimmerReady = false,
                streaming = false,
                shimmerAddress = macAddress,
                currentActivity = "Connecting...",
                confidencePercent = 0,
                sessionStartMillis = 0L,
                lastError = null
            )
        }

        shimmerManager?.connect()
    }

    fun disconnectShimmer() {
        shimmerManager?.disconnect()
        motionPipeline?.setInferenceEnabled(false)
        motionPipeline?.reset()
        shimmerManager = null
        resetActivityCounts()

        updateState {
            copy(
                shimmerConnected = false,
                shimmerReady = false,
                streaming = false,
                shimmerAddress = null,
                currentActivity = "Disconnected",
                confidencePercent = 0,
                sessionStartMillis = 0L,
                lastError = null
            )
        }
    }

    override fun onShimmerConnected() {
        updateState {
            copy(
                shimmerConnected = true,
                currentActivity = "Shimmer connected",
                lastError = null
            )
        }

        shimmerManager?.setupShimmer()
    }

    override fun onShimmerDisconnected() {
        motionPipeline?.setInferenceEnabled(false)
        motionPipeline?.reset()
        shimmerManager = null
        resetActivityCounts()

        updateState {
            copy(
                shimmerConnected = false,
                shimmerReady = false,
                streaming = false,
                shimmerAddress = null,
                currentActivity = "Disconnected",
                confidencePercent = 0,
                sessionStartMillis = 0L,
                lastError = null
            )
        }
    }

    override fun onShimmerReady() {
        val startMillis = if (state.sessionStartMillis == 0L) {
            System.currentTimeMillis()
        } else {
            state.sessionStartMillis
        }

        motionPipeline?.setInferenceEnabled(true)

        updateState {
            copy(
                shimmerReady = true,
                streaming = true,
                sessionStartMillis = startMillis,
                currentActivity = "Streaming...",
                lastError = null
            )
        }

        shimmerManager?.startStreaming()
    }

    override fun onWindowCreated(window: AccelWindow) {
    }

    override fun onPredictionReceived(result: LocalPredictionResult) {
        val activity = result.prediction

        if (activityCounts.containsKey(activity)) {
            activityCounts[activity] = (activityCounts[activity] ?: 0) + 1
        }

        updateState {
            copy(
                currentActivity = activity,
                confidencePercent = (result.confidence * 100).toInt().coerceIn(0, 100),
                lastError = null
            )
        }
    }

    override fun onMotionError(message: String) {
        updateState {
            copy(lastError = message)
        }
    }

    @Synchronized
    private fun updateState(transform: MotionUiState.() -> MotionUiState) {
        state = state.transform()
        notifyAllObservers(state)
    }

    private fun notifyAllObservers(state: MotionUiState) {
        mainHandler.post {
            observers.forEach { notifyObserver(it, state) }
        }
    }

    private fun notifyObserver(observer: Observer, state: MotionUiState) {
        mainHandler.post {
            observer.onMotionStateChanged(state)
        }
    }
}