package com.example.myapplication.UI

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.myapplication.services.GestoreStatistiche
import com.example.myapplication.services.TimeAxisFormatter
import com.example.myapplication.Motion.session.MotionSessionManager
import com.example.myapplication.Motion.session.MotionUiState
import com.example.myapplication.BT.ring.SmartRingManager
import com.example.myapplication.R
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF

class ChartsFragment : Fragment(), MotionSessionManager.Observer {

    private lateinit var pieChart: PieChart
    private lateinit var BPMChart: LineChart
    private lateinit var O2Chart: LineChart
    private lateinit var pressureChart: LineChart

    private lateinit var tvCurrentBpm: TextView
    private lateinit var tvLastO2: TextView
    private lateinit var tvLastPressure: TextView

    // CheckBox per il blocco dello scorrimento e dei dati
    private lateinit var cbLockScrollBpm: CheckBox
    private lateinit var cbLockScrollO2: CheckBox
    private lateinit var cbLockScrollPressure: CheckBox

    private val activityLabels = listOf("Walking", "Jogging", "Sitting", "Standing")
    private val activityColorMap = mapOf(
        "Walking" to Color.parseColor("#4CAF74"),
        "Jogging" to Color.parseColor("#66BB6A"),
        "Sitting" to Color.parseColor("#7A9B7D"),
        "Standing" to Color.parseColor("#2E7D52")
    )

    private lateinit var gestoreStatistiche: GestoreStatistiche

    // Flags per garantire che i dati storici vengano mostrati almeno una volta all'avvio
    private var isBpmFirstLoad = true
    private var isO2FirstLoad = true
    private var isPressureFirstLoad = true

    // LOGICA DI RICHIESTA CICLICA (UI Polling Timer)
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            aggiornaGrafici()
            // Ripete l'interrogazione ogni 2000 millisecondi (2 secondi)
            pollHandler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gestoreStatistiche = GestoreStatistiche.getInstance(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_charts, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        BPMChart = view.findViewById(R.id.BPMChart)
        tvCurrentBpm = view.findViewById(R.id.tvCurrentBpm)
        cbLockScrollBpm = view.findViewById(R.id.cbLockScrollBpm)

        O2Chart = view.findViewById(R.id.O2Chart)
        tvLastO2 = view.findViewById(R.id.tvLastO2)
        cbLockScrollO2 = view.findViewById(R.id.cbLockScrollO2)

        pressureChart = view.findViewById(R.id.bloodPressureChart)
        tvLastPressure = view.findViewById(R.id.tvLastPressure)
        cbLockScrollPressure = view.findViewById(R.id.cbLockScrollPressure)

        pieChart = view.findViewById(R.id.activityChart)
        setupPieChartStyle()
        refreshPieChart()

        // Configurazione del Marker View Personalizzato per i dati puntuali al tocco
        val customMarker = CustomMarkerView(requireContext(), R.layout.custom_marker_view)
        BPMChart.marker = customMarker
        O2Chart.marker = customMarker
        pressureChart.marker = customMarker

        // Listener sui CheckBox per forzare il ripristino o lo sblocco grafico immediato al click
        cbLockScrollBpm.setOnCheckedChangeListener { _, isChecked -> if (!isChecked) caricaBpm() }
        cbLockScrollO2.setOnCheckedChangeListener { _, isChecked -> if (!isChecked) caricaO2() }
        cbLockScrollPressure.setOnCheckedChangeListener { _, isChecked -> if (!isChecked) caricaPressione() }

        MotionSessionManager.addObserver(this)

        // Resettiamo i flag al caricamento della View per forzare il primo riempimento dei grafici
        isBpmFirstLoad = true
        isO2FirstLoad = true
        isPressureFirstLoad = true

        // 1. Legge subito lo stato attuale del DB all'apertura (forzato dai flag true)
        aggiornaGrafici()

        // 2. AVVIA LA RICHIESTA CICLICA DI POLLING
        pollHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        MotionSessionManager.removeObserver(this)

        // IMPORTANTE: INTERROMPIAMO IL TIMER CICLICO quando l'utente esce dalla tab
        pollHandler.removeCallbacks(pollRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        MotionSessionManager.removeObserver(this)
        // IMPORTANTE: INTERROMPIAMO IL TIMER CICLICO quando l'utente esce dalla tab
        pollHandler.removeCallbacks(pollRunnable)
    }

    override fun onMotionStateChanged(state: MotionUiState) {
        if (!isAdded) return
        refreshPieChart()
    }

    private fun setupPieChartStyle() {
        pieChart.description.isEnabled = false
        pieChart.setUsePercentValues(true)
        pieChart.setHoleColor(Color.TRANSPARENT)
        pieChart.legend.textColor = Color.LTGRAY
        pieChart.setEntryLabelColor(Color.WHITE)
        pieChart.centerText = "Activities"
    }

    private fun refreshPieChart() {
        val counts = MotionSessionManager.getActivityCounts()
        val total = counts.values.sum()

        if (total == 0) {
            pieChart.clear()
            pieChart.centerText = "Nessun dato"
            pieChart.invalidate()
            return
        }

        val entries = activityLabels.mapNotNull { label ->
            val count = counts[label] ?: 0
            if (count > 0) PieEntry(count.toFloat(), label) else null
        }

        val colors = entries.mapNotNull { entry ->
            activityColorMap[entry.label]
        }

        val dataSet = PieDataSet(entries, "").apply {
            this.colors = colors
            valueTextColor = Color.WHITE
            valueTextSize = 11f
            sliceSpace = 3f
        }

        val data = PieData(dataSet).apply {
            setValueFormatter(PercentFormatter(pieChart))
        }

        pieChart.data = data
        pieChart.centerText = "Activities"
        pieChart.invalidate()
    }

    private fun caricaBpm() {
        val listaCompleta = gestoreStatistiche.getBpm()
        if (listaCompleta.isEmpty()) return

        // Aggiorna sempre il valore di testo in tempo reale in alto
        val ultimo = listaCompleta.last()
        tvCurrentBpm.text = ultimo.bpm.toString()

        // SE IL GRAFICO È BLOCCATO: Congeliamo il disegno dei dati e non modifichiamo nulla
        if (cbLockScrollBpm.isChecked) return

        val lista = listaCompleta.takeLast(300)
        val timestamps = lista.map { it.timestamp }
        val entries = lista.mapIndexed { index, item ->
            Entry(index.toFloat(), item.bpm.toFloat())
        }

        val dataSet = LineDataSet(entries, "BPM").apply {
            color = Color.parseColor("#FF9800")
            lineWidth = 2.5f
            mode = LineDataSet.Mode.CUBIC_BEZIER

            setDrawFilled(true)
            fillColor = Color.parseColor("#FF9800")
            fillAlpha = 30

            setDrawCircles(true)
            setCircleColor(Color.parseColor("#FF9800"))
            circleRadius = 3f
            circleHoleRadius = 1.5f
            setDrawCircleHole(true)

            setDrawValues(false)

            highLightColor = Color.WHITE
            highlightLineWidth = 1f
            enableDashedHighlightLine(10f, 5f, 0f)
        }

        BPMChart.apply {
            data = LineData(dataSet)

            description.isEnabled = false
            axisRight.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            legend.textColor = Color.WHITE
            setOnTouchListener { v, _ ->
                v.parent.requestDisallowInterceptTouchEvent(true)
                false
            }

            val maxVisibleX = 13f

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = Color.WHITE
                valueFormatter = TimeAxisFormatter(timestamps)
                granularity = 1f
                labelRotationAngle = -45f
                setAvoidFirstLastClipping(true)
            }

            axisLeft.textColor = Color.WHITE

            setVisibleXRangeMaximum(maxVisibleX)

            if (entries.size > maxVisibleX) {
                moveViewToX(entries.size.toFloat() - maxVisibleX)
            } else {
                invalidate()
            }
        }
    }

    private fun caricaO2() {
        val listaCompleta = gestoreStatistiche.getO2()
        if (listaCompleta.isEmpty()) return

        // Aggiorna sempre il testo in tempo reale
        val ultimo = listaCompleta.last()
        tvLastO2.text = "${ultimo.value} %"

        // SE IL GRAFICO È BLOCCATO: esce senza toccare i dati correnti del grafico
        if (cbLockScrollO2.isChecked) return

        val lista = listaCompleta.takeLast(150)
        val timestamps = lista.map { it.timestamp }
        val entries = lista.mapIndexed { index, item ->
            Entry(index.toFloat(), item.value.toFloat())
        }

        val dataSet = LineDataSet(entries, "SpO2").apply {
            color = Color.parseColor("#4CAF50")
            lineWidth = 2.5f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = Color.parseColor("#4CAF50")
            fillAlpha = 30

            setDrawCircles(true)
            setCircleColor(Color.parseColor("#4CAF50"))
            circleRadius = 3f
            setDrawValues(false)

            highLightColor = Color.WHITE
            highlightLineWidth = 1f
        }

        O2Chart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            axisRight.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            legend.textColor = Color.WHITE
            setOnTouchListener { v, _ ->
                v.parent.requestDisallowInterceptTouchEvent(true)
                false
            }

            val maxVisibleX = 13f
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = Color.WHITE
                valueFormatter = TimeAxisFormatter(timestamps)
                granularity = 1f
                labelRotationAngle = -45f
            }
            axisLeft.textColor = Color.WHITE

            setVisibleXRangeMaximum(maxVisibleX)
            if (entries.size > maxVisibleX) {
                moveViewToX(entries.size.toFloat() - maxVisibleX)
            } else {
                invalidate()
            }
        }
    }

    private fun caricaPressione() {
        val listaCompleta = gestoreStatistiche.getPressioni()
        if (listaCompleta.isEmpty()) return

        // Aggiorna sempre il testo in tempo reale
        val ultimo = listaCompleta.last()
        tvLastPressure.text = "${ultimo.systolic}/${ultimo.diastolic}"

        // SE IL GRAFICO È BLOCCATO: esce senza toccare i dati correnti del grafico
        if (cbLockScrollPressure.isChecked) return

        val lista = listaCompleta.takeLast(150)
        val timestamps = lista.map { it.timestamp }
        val entriesSys = lista.mapIndexed { index, item -> Entry(index.toFloat(), item.systolic.toFloat()) }
        val entriesDia = lista.mapIndexed { index, item -> Entry(index.toFloat(), item.diastolic.toFloat()) }

        val sysSet = LineDataSet(entriesSys, "Sistolica").apply {
            color = Color.parseColor("#E91E63")
            lineWidth = 2.5f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawCircles(true)
            setCircleColor(Color.parseColor("#E91E63"))
            circleRadius = 3f
            setDrawValues(false)
            highLightColor = Color.WHITE
        }

        val diaSet = LineDataSet(entriesDia, "Diastolica").apply {
            color = Color.parseColor("#03A9F4")
            lineWidth = 2.5f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawCircles(true)
            setCircleColor(Color.parseColor("#03A9F4"))
            circleRadius = 3f
            setDrawValues(false)
            highLightColor = Color.WHITE
        }

        pressureChart.apply {
            data = LineData(sysSet, diaSet)
            description.isEnabled = false
            axisRight.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            legend.textColor = Color.WHITE
            setOnTouchListener { v, _ ->
                v.parent.requestDisallowInterceptTouchEvent(true)
                false
            }

            val maxVisibleX = 13f
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = Color.WHITE
                valueFormatter = TimeAxisFormatter(timestamps)
                granularity = 1f
                labelRotationAngle = -45f
            }
            axisLeft.textColor = Color.WHITE

            setVisibleXRangeMaximum(maxVisibleX)
            if (entriesSys.size > maxVisibleX) {
                moveViewToX(entriesSys.size.toFloat() - maxVisibleX)
            } else {
                invalidate()
            }
        }
    }

    /**
     * Ottimizzazione del ciclo di aggiornamento.
     * Interroga lo SmartRingManager per aggiornare i grafici solo se l'hardware è attivo
     * sulla specifica metrica, oppure se è il primo avvio del fragment (per mostrare lo storico).
     */
    private fun aggiornaGrafici() {
        val ringManager = SmartRingManager.getActiveInstance()
        val activeMeasurement = ringManager?.getActiveMeasurementType()

        // 1. Ottimizzazione BPM (Aggiorna se è la prima volta o se l'anello sta misurando i BPM)
        if (isBpmFirstLoad || activeMeasurement == "BPM") {
            caricaBpm()
            Log.d("SMART_RING", "Aggiornato grafico BPM")
            isBpmFirstLoad = false
        }

        // 2. Ottimizzazione SpO2 (Aggiorna se è la prima volta o se l'anello sta misurando O2)
        if (isO2FirstLoad || activeMeasurement == "O2") {
            caricaO2()
            Log.d("SMART_RING", "Aggiornato grafico O2")
            isO2FirstLoad = false
        }

        // 3. Ottimizzazione Pressione (Aggiorna se è la prima volta o se l'anello sta misurando PRESSURE)
        if (isPressureFirstLoad || activeMeasurement == "PRESSURE") {
            caricaPressione()
            Log.d("SMART_RING", "Aggiornato grafico pressione")
            isPressureFirstLoad = false
        }
    }
}

// Classe di supporto per gestire i popup informativi al tocco (MarkerView)
class CustomMarkerView(context: Context, layoutResource: Int) : MarkerView(context, layoutResource) {

    private val tvMarkerValue: TextView = findViewById(R.id.tvMarkerValue)

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        if (e != null) {
            tvMarkerValue.text = e.y.toInt().toString()
        }
        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF {
        return MPPointF((-(width / 2)).toFloat(), (-height).toFloat())
    }
}