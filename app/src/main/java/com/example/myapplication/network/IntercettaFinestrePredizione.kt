package com.example.myapplication.network

import com.example.myapplication.Motion.model.AccelWindow
import com.example.myapplication.Motion.pipeline.MotionPipeline
import com.example.myapplication.Motion.tflite.LocalPredictionResult

class IntercettaFinestrePredizione : MotionPipeline.Listener {

    private lateinit var liste: Triple<List<Float>, List<Float>, List<Float>>


    override fun onShimmerConnected() {
        TODO("Not yet implemented")
    }

    override fun onShimmerDisconnected() {
        TODO("Not yet implemented")
    }

    override fun onShimmerReady() {
        TODO("Not yet implemented")
    }

    override fun onWindowCreated(window: AccelWindow) {
        val matrix = window.data

        val x = matrix.map { it[0] }
        val y = matrix.map { it[1] }
        val z = matrix.map { it[2] }

        liste = Triple(x, y, z)
    }

    override fun onPredictionReceived(result: LocalPredictionResult) {
        TODO("Not yet implemented")
    }

    override fun onMotionError(message: String) {
        TODO("Not yet implemented")
    }
}