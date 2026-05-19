package com.example.myapplication

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

data class MotionUiState(
    val shimmerConnected: Boolean = false,
    val shimmerReady: Boolean = false,
    val serverReachable: Boolean = false,
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
    private var baseUrl: String = "https://muster-spectacle-drizzle.ngrok-free.dev"

    private var pythonClient: PythonRealtimeClient? = null
    private var motionPipeline: MotionPipeline? = null
    private var shimmerManager: ShimmerClassicManager? = null

    @Volatile
    private var state = MotionUiState()

    fun initialize(
        context: Context,
        serverBaseUrl: String = "https://muster-spectacle-drizzle.ngrok-free.dev"
    ) {
        android.util.Log.d("MotionSessionManager", "INIT baseUrl = $serverBaseUrl")
        android.util.Log.d("MotionSessionManager", "FINAL baseUrl = $baseUrl")
        appContext = context.applicationContext

        val normalizedUrl = serverBaseUrl.trimEnd('/')
        val mustRecreateClient = pythonClient == null || baseUrl != normalizedUrl

        baseUrl = normalizedUrl

        if (mustRecreateClient) {
            pythonClient = PythonRealtimeClient(baseUrl)
            motionPipeline = MotionPipeline(
                pythonClient = pythonClient!!,
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

    fun connectToShimmer(context: Context, macAddress: String) {
        initialize(context)

        shimmerManager?.disconnect()
        shimmerManager = ShimmerClassicManager(
            context = context.applicationContext,
            macAddress = macAddress,
            listener = motionPipeline ?: return
        )

        updateState {
            copy(
                shimmerConnected = false,
                shimmerReady = false,
                serverReachable = false,
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
        motionPipeline?.reset()
        shimmerManager = null

        updateState {
            copy(
                shimmerConnected = false,
                shimmerReady = false,
                serverReachable = false,
                streaming = false,
                shimmerAddress = null,
                currentActivity = "Disconnected",
                confidencePercent = 0,
                sessionStartMillis = 0L
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

        pythonClient?.checkHealth(
            onResult = {
                updateState {
                    copy(
                        serverReachable = true,
                        lastError = null
                    )
                }
                shimmerManager?.setupShimmer()
            },
            onError = { error ->
                updateState {
                    copy(
                        serverReachable = false,
                        lastError = error,
                        currentActivity = "Server unreachable"
                    )
                }
            }
        )
    }

    override fun onShimmerDisconnected() {
        motionPipeline?.reset()
        shimmerManager = null

        updateState {
            copy(
                shimmerConnected = false,
                shimmerReady = false,
                serverReachable = false,
                streaming = false,
                shimmerAddress = null,
                currentActivity = "Disconnected",
                confidencePercent = 0,
                sessionStartMillis = 0L
            )
        }
    }

    override fun onShimmerReady() {
        val startMillis = if (state.sessionStartMillis == 0L) {
            System.currentTimeMillis()
        } else {
            state.sessionStartMillis
        }

        updateState {
            copy(
                shimmerReady = true,
                streaming = true,
                sessionStartMillis = startMillis,
                currentActivity = "Streaming..."
            )
        }

        shimmerManager?.startStreaming()
    }

    override fun onWindowCreated(window: AccelWindow) {
        // opzionale: qui potresti salvare info di debug
    }

    override fun onPredictionReceived(result: PredictionResult) {
        updateState {
            copy(
                currentActivity = result.prediction,
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