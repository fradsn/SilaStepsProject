package com.example.myapplication.Motion.tflite

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class LocalPredictionResult(
    val prediction: String,
    val confidence: Float,
    val predictionIndex: Int
)

class Classifier(private val context: Context) {

    companion object {
        private const val TAG = "TFLITE_CLASSIFIER"
        private const val WINDOW_SIZE = 100
        private const val CHANNELS = 3
        private const val NUM_CLASSES = 4
        private const val MODEL_FILE = "ar_model.tflite"
        private const val SCALER_FILE = "scaler_params.json"
    }

    private val labels = listOf("Walking", "Jogging", "Sitting", "Standing")
    private var interpreter: Interpreter? = null
    private val mean = FloatArray(CHANNELS)
    private val scale = FloatArray(CHANNELS)

    init {
        loadScalerParams()
        initializeInterpreter()
    }

    private fun initializeInterpreter() {
        try {
            val options = Interpreter.Options().apply {
                setNumThreads(1)
            }

            interpreter = Interpreter(loadModelFile(), options)
            interpreter?.allocateTensors()

            val inputTensor = interpreter?.getInputTensor(0)
            val outputTensor = interpreter?.getOutputTensor(0)

            Log.d(TAG, "input shape=${inputTensor?.shape()?.contentToString()}")
            Log.d(TAG, "output shape=${outputTensor?.shape()?.contentToString()}")
            Log.d(TAG, "input dtype=${inputTensor?.dataType()}")
            Log.d(TAG, "output dtype=${outputTensor?.dataType()}")

            val inputShape = inputTensor?.shape()
                ?: throw IllegalStateException("Input tensor non disponibile")
            val outputShape = outputTensor?.shape()
                ?: throw IllegalStateException("Output tensor non disponibile")

            if (!inputShape.contentEquals(intArrayOf(1, CHANNELS, WINDOW_SIZE))) {
                throw IllegalStateException(
                    "Shape input inattesa: ${inputShape.contentToString()}, attesa [1, $CHANNELS, $WINDOW_SIZE]"
                )
            }

            if (!outputShape.contentEquals(intArrayOf(1, NUM_CLASSES))) {
                throw IllegalStateException(
                    "Shape output inattesa: ${outputShape.contentToString()}, attesa [1, $NUM_CLASSES]"
                )
            }

            if (inputTensor.dataType() != DataType.FLOAT32) {
                throw IllegalStateException("Input tensor non FLOAT32: ${inputTensor.dataType()}")
            }

            if (outputTensor.dataType() != DataType.FLOAT32) {
                throw IllegalStateException("Output tensor non FLOAT32: ${outputTensor.dataType()}")
            }
        } catch (e: FileNotFoundException) {
            throw IllegalStateException("File $MODEL_FILE non trovato in assets", e)
        } catch (e: Exception) {
            throw IllegalStateException("Errore inizializzazione classifier: ${e.message}", e)
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(MODEL_FILE)
        FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
            val fileChannel = inputStream.channel
            return fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                fileDescriptor.startOffset,
                fileDescriptor.declaredLength
            )
        }
    }

    fun predict(window: Array<FloatArray>): LocalPredictionResult {
        require(window.size == WINDOW_SIZE) {
            "Window size non valido: atteso $WINDOW_SIZE, ricevuto ${window.size}"
        }

        require(window.all { it.size == CHANNELS }) {
            "Numero canali non valido: attesi $CHANNELS"
        }

        val localInterpreter = interpreter
            ?: throw IllegalStateException("Interpreter non inizializzato")

        val input = Array(1) { Array(CHANNELS) { FloatArray(WINDOW_SIZE) } }

        for (channel in 0 until CHANNELS) {
            for (time in 0 until WINDOW_SIZE) {
                val denom = if (scale[channel] == 0f) 1f else scale[channel]
                input[0][channel][time] = (window[time][channel] - mean[channel]) / denom
            }
        }

        val output = Array(1) { FloatArray(NUM_CLASSES) }

        localInterpreter.run(input, output)

        val logits = output[0]
        val probs = softmax(logits)
        val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 0

        return LocalPredictionResult(
            prediction = labels.getOrElse(maxIdx) { "Unknown" },
            confidence = probs[maxIdx],
            predictionIndex = maxIdx
        )
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    private fun loadScalerParams() {
        try {
            val jsonText = context.assets.open(SCALER_FILE)
                .bufferedReader()
                .use { it.readText() }

            val json = JSONObject(jsonText)
            val meanArray = json.getJSONArray("mean")
            val scaleArray = json.getJSONArray("scale")

            for (i in 0 until CHANNELS) {
                mean[i] = meanArray.getDouble(i).toFloat()
                scale[i] = scaleArray.getDouble(i).toFloat()
            }
        } catch (e: FileNotFoundException) {
            throw IllegalStateException("File $SCALER_FILE non trovato in assets", e)
        } catch (e: Exception) {
            throw IllegalStateException("Errore caricamento scaler params: ${e.message}", e)
        }
    }

    private fun softmax(values: FloatArray): FloatArray {
        val max = values.maxOrNull() ?: 0f
        val exps = FloatArray(values.size)
        var sum = 0f

        for (i in values.indices) {
            exps[i] = kotlin.math.exp((values[i] - max).toDouble()).toFloat()
            sum += exps[i]
        }

        return FloatArray(values.size) { i ->
            if (sum == 0f) 0f else exps[i] / sum
        }
    }
}