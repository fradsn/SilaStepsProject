package com.example.myapplication.network

import com.example.myapplication.Motion.model.AccelWindow
import com.example.myapplication.Motion.pipeline.MotionPipeline
import com.example.myapplication.Motion.tflite.LocalPredictionResult
import java.util.concurrent.CopyOnWriteArrayList

// Data class per modellare il punto sintetico della finestra
data class AccelDataPoint(
    val timestamp: Long,
    val avgX: Double,
    val avgY: Double,
    val avgZ: Double
)

class IntercettaFinestrePredizione private constructor() : MotionPipeline.Listener {

    companion object {
        @Volatile
        private var INSTANCE: IntercettaFinestrePredizione? = null

        fun getInstance(): IntercettaFinestrePredizione {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: IntercettaFinestrePredizione().also { INSTANCE = it }
            }
        }
    }

    // Cache dinamica in memoria e thread-safe
    private val cacheList = CopyOnWriteArrayList<AccelDataPoint>()

    override fun onWindowCreated(window: AccelWindow) {
        val matrix = window.data

        // Estrazione e calcolo delle medie della finestra attuale
        val avgX = matrix.map { it[0] }.average()
        val avgY = matrix.map { it[1] }.average()
        val avgZ = matrix.map { it[2] }.average()
        val timestamp = System.currentTimeMillis()

        // Salvataggio nella lista dinamica
        cacheList.add(AccelDataPoint(timestamp, avgX, avgY, avgZ))
    }

    /**
     * Ritorna una copia dei dati accumulati e svuota atomicamente la lista
     * per i successivi 5 minuti di campionamento.
     */
    fun flushCache(): List<AccelDataPoint> {
        val snapshot = cacheList.toList()
        cacheList.clear()
        return snapshot
    }

    override fun onShimmerConnected() {}
    override fun onShimmerDisconnected() {}
    override fun onShimmerReady() {}
    override fun onPredictionReceived(result: LocalPredictionResult) {}
    override fun onMotionError(message: String) {}
}