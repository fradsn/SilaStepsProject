package com.example.myapplication.Motion.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.myapplication.Motion.model.AccelWindow
import com.example.myapplication.Motion.pipeline.MotionPipeline
import com.example.myapplication.Motion.tflite.LocalPredictionResult
import com.example.myapplication.BT.Shimmer.ShimmerClassicManager
import com.example.myapplication.db.GestoreStatistiche
import com.example.myapplication.network.IntercettaFinestrePredizione
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

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
    private val observers = CopyOnWriteArrayList<WeakReference<Observer>>()

    private var motionPipeline: MotionPipeline? = null
    private var shimmerManager: ShimmerClassicManager? = null

    @Volatile
    private var state = MotionUiState()
    private var gestoreStatistiche: GestoreStatistiche? = null // Mantiene la consistenza di GestoreStatistiche

    fun initialize(context: Context) {
        val safeContext = context.applicationContext

        if (motionPipeline == null) {
            motionPipeline = MotionPipeline(
                context = safeContext,
                listener = this
            )
        }

        if (gestoreStatistiche == null) {
            gestoreStatistiche = GestoreStatistiche.getInstance(safeContext)
        }
    }

    fun addObserver(observer: Observer) {
        removeObserver(observer)
        observers.add(WeakReference(observer))
        notifyObserver(observer, state)
    }

    fun removeObserver(observer: Observer) {
        observers.removeAll { it.get() == null || it.get() == observer }
    }

    fun getState(): MotionUiState = state
    fun isShimmerConnected(): Boolean = shimmerManager?.isConnected() == true
    fun getShimmerAddress(): String? = shimmerManager?.getAddress()

    fun setInferenceEnabled(enabled: Boolean) {
        motionPipeline?.setInferenceEnabled(enabled)
    }

    fun connectToShimmer(context: Context, macAddress: String) {
        val safeContext = context.applicationContext
        initialize(safeContext)

        shimmerManager?.disconnect()
        shimmerManager?.updateListener(null)

        shimmerManager = ShimmerClassicManager.getInstance(
            context = safeContext,
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
        shimmerManager?.updateListener(null)
        motionPipeline?.setInferenceEnabled(false)
        motionPipeline?.reset()
        shimmerManager = null

        // Sincronizza lo sgancio dell'hardware anche con l'intercettore RAW
        IntercettaFinestrePredizione.getInstance().onShimmerDisconnected()

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
        IntercettaFinestrePredizione.getInstance().onShimmerConnected()
        shimmerManager?.setupShimmer()
    }

    override fun onShimmerDisconnected() {
        motionPipeline?.setInferenceEnabled(false)
        motionPipeline?.reset()
        shimmerManager?.updateListener(null)
        shimmerManager = null

        // Pulisce e notifica l'intercettore RAW della disconnessione
        IntercettaFinestrePredizione.getInstance().onShimmerDisconnected()

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

        IntercettaFinestrePredizione.getInstance().onShimmerReady()
        shimmerManager?.startStreaming()
    }

    // =====================================================================================
    // SOLUZIONE DEL BUG: COUPLING FLUSH VERSO L'INTERCETTORE DI MEMORIA RAM
    // =====================================================================================
    override fun onWindowCreated(window: AccelWindow) {
        // Intercetta la matrice e la inoltra istantaneamente al modulo cloud in background
        IntercettaFinestrePredizione.getInstance().onWindowCreated(window)
    }

    override fun onPredictionReceived(result: LocalPredictionResult) {
        val activity = result.prediction
        val confidence = (result.confidence * 100).toInt().coerceIn(0, 100)

        gestoreStatistiche?.salvaPredizione(activity, confidence)
        IntercettaFinestrePredizione.getInstance().onPredictionReceived(result)

        updateState {
            copy(
                currentActivity = activity,
                confidencePercent = confidence,
                lastError = null
            )
        }
    }

    override fun onMotionError(message: String) {
        IntercettaFinestrePredizione.getInstance().onMotionError(message)
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
            observers.forEach { ref ->
                val obs = ref.get()
                if (obs != null) {
                    notifyObserver(obs, state)
                } else {
                    observers.remove(ref)
                }
            }
        }
    }

    private fun notifyObserver(observer: Observer, state: MotionUiState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            observer.onMotionStateChanged(state)
        } else {
            mainHandler.post {
                observer.onMotionStateChanged(state)
            }
        }
    }
}