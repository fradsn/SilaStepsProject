package com.example.myapplication.UI

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
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

class ChartsFragment : Fragment() {

    private lateinit var pieChart: PieChart
    private lateinit var BPMChart: LineChart
    private lateinit var O2Chart: LineChart
    private lateinit var pressureChart: LineChart
    private lateinit var stepsChart: LineChart

    private lateinit var tvCurrentBpm: TextView
    private lateinit var tvLastO2: TextView
    private lateinit var tvLastPressure: TextView
    private lateinit var tvTotalSteps: TextView

    private lateinit var cbLockScrollBpm: CheckBox
    private lateinit var cbLockScrollO2: CheckBox
    private lateinit var cbLockScrollPressure: CheckBox

    private val activityLabels = listOf("Walking", "Jogging", "Sitting", "Standing")

    // Mappatura dinamica basata sulla nuova tavolozza dei colori coerenti
    private val activityColorMap by lazy {
        mapOf(
            "Walking" to resources.getColor(R.color.primary_neon),
            "Jogging" to resources.getColor(R.color.health_bpm),
            "Sitting" to resources.getColor(R.color.health_shimmer),
            "Standing" to resources.getColor(R.color.health_pressure)
        )
    }

    private lateinit var gestoreStatistiche: GestoreStatistiche

    private var isBpmFirstLoad = true
    private var isO2FirstLoad = true
    private var isPressureFirstLoad = true

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            aggiornaGrafici()
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

        stepsChart = view.findViewById(R.id.StepsChart)
        tvTotalSteps = view.findViewById(R.id.tvTotalSteps)

        val customMarker = CustomMarkerView(requireContext(), R.layout.custom_marker_view)
        BPMChart.marker = customMarker
        O2Chart.marker = customMarker
        pressureChart.marker = customMarker

        cbLockScrollBpm.setOnCheckedChangeListener { _, isChecked -> if (!isChecked) caricaBpm() }
        cbLockScrollO2.setOnCheckedChangeListener { _, isChecked -> if (!isChecked) caricaO2() }
        cbLockScrollPressure.setOnCheckedChangeListener { _, isChecked -> if (!isChecked) caricaPressione() }

        isBpmFirstLoad = true
        isO2FirstLoad = true
        isPressureFirstLoad = true

        aggiornaGrafici()
        pollHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pollHandler.removeCallbacks(pollRunnable)
    }

    private fun setupPieChartStyle() {
        pieChart.description.isEnabled = false
        pieChart.setUsePercentValues(true)
        pieChart.setHoleColor(Color.TRANSPARENT)
        pieChart.setCenterTextColor(resources.getColor(R.color.text_primary))
        pieChart.setCenterTextSize(14f)
        pieChart.legend.textColor = resources.getColor(R.color.text_secondary)
        pieChart.setEntryLabelColor(resources.getColor(R.color.text_primary))
        pieChart.centerText = "Activities"
    }

    private fun refreshPieChart() {
        val counts = gestoreStatistiche.getActivityCount()
        val total = counts.values.sum()

        if (total == 0) {
            pieChart.clear()
            pieChart.centerText = "No data available"
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
            valueTextColor = resources.getColor(R.color.text_primary)
            valueTextSize = 12f
            sliceSpace = 4f
        }

        val data = PieData(dataSet).apply {
            setValueFormatter(PercentFormatter(pieChart))
        }

        pieChart.data = data
        pieChart.centerText = "Activities"
        pieChart.invalidate()
    }

    private fun configLineChartStyle(chart: LineChart) {
        chart.description.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.setDrawGridBackground(false)
        chart.legend.textColor = resources.getColor(R.color.text_secondary)
        chart.setNoDataText("Awaiting biometric streaming...")
        chart.setNoDataTextColor(resources.getColor(R.color.text_secondary))

        chart.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            textColor = resources.getColor(R.color.text_secondary)
            setDrawGridLines(false) // Rimozione griglia verticale pesante
            setDrawAxisLine(false)
            granularity = 1f
            labelRotationAngle = -45f
            setAvoidFirstLastClipping(true)
        }

        chart.axisLeft.apply {
            textColor = resources.getColor(R.color.text_secondary)
            setDrawGridLines(true)
            gridColor = resources.getColor(R.color.surface_variant_dark) // Griglia orizzontale soft e soffusa
            setDrawAxisLine(false)
        }
    }

    private fun caricaBpm() {
        val listaCompleta = gestoreStatistiche.getBpm()
        if (listaCompleta.isEmpty()) return

        val ultimo = listaCompleta.last()
        tvCurrentBpm.text = ultimo.bpm.toString()

        if (cbLockScrollBpm.isChecked) return

        val lista = listaCompleta.takeLast(300)
        val timestamps = lista.map { it.timestamp }
        val entries = lista.mapIndexed { index, item -> Entry(index.toFloat(), item.bpm.toFloat()) }

        val mainColor = resources.getColor(R.color.health_bpm)
        val dataSet = LineDataSet(entries, "Heart Rate").apply {
            color = mainColor
            lineWidth = 3f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)

            // Creazione gradiente sfumato moderno sotto la linea del battito
            fillFormatter = com.github.mikephil.charting.formatter.IFillFormatter { _, _ -> BPMChart.axisLeft.axisMinimum }
            val gradientShader = LinearGradient(0f, 0f, 0f, BPMChart.height.toFloat(), mainColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            val paint = BPMChart.getPaint(com.github.mikephil.charting.charts.Chart.PAINT_GRID_BACKGROUND)
            paint.shader = gradientShader

            fillAlpha = 45
            setDrawCircles(false) // Disattiviamo i pallini continui per un look più fluido e moderno
            setDrawValues(false)
            highLightColor = resources.getColor(R.color.text_primary)
            highlightLineWidth = 1f
        }

        BPMChart.apply {
            configLineChartStyle(this)
            data = LineData(dataSet)
            xAxis.valueFormatter = TimeAxisFormatter(timestamps)
            val maxVisibleX = 13f
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

        val ultimo = listaCompleta.last()
        tvLastO2.text = "${ultimo.value} %"

        if (cbLockScrollO2.isChecked) return

        val lista = listaCompleta.takeLast(150)
        val timestamps = lista.map { it.timestamp }
        val entries = lista.mapIndexed { index, item -> Entry(index.toFloat(), item.value.toFloat()) }

        val mainColor = resources.getColor(R.color.health_o2)
        val dataSet = LineDataSet(entries, "SpO2").apply {
            color = mainColor
            lineWidth = 3f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillAlpha = 40
            setDrawCircles(false)
            setDrawValues(false)
            highLightColor = resources.getColor(R.color.text_primary)
        }

        O2Chart.apply {
            configLineChartStyle(this)
            data = LineData(dataSet)
            xAxis.valueFormatter = TimeAxisFormatter(timestamps)
            val maxVisibleX = 13f
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

        val ultimo = listaCompleta.last()
        tvLastPressure.text = "${ultimo.systolic}/${ultimo.diastolic}"

        if (cbLockScrollPressure.isChecked) return

        val lista = listaCompleta.takeLast(150)
        val timestamps = lista.map { it.timestamp }
        val entriesSys = lista.mapIndexed { index, item -> Entry(index.toFloat(), item.systolic.toFloat()) }
        val entriesDia = lista.mapIndexed { index, item -> Entry(index.toFloat(), item.diastolic.toFloat()) }

        val colorSys = resources.getColor(R.color.health_pressure)
        val colorDia = resources.getColor(R.color.primary_neon)

        val sysSet = LineDataSet(entriesSys, "Systolic").apply {
            color = colorSys
            lineWidth = 3f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawCircles(false)
            setDrawValues(false)
            highLightColor = resources.getColor(R.color.text_primary)
        }

        val diaSet = LineDataSet(entriesDia, "Diastolic").apply {
            color = colorDia
            lineWidth = 3f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawCircles(false)
            setDrawValues(false)
            highLightColor = resources.getColor(R.color.text_primary)
        }

        pressureChart.apply {
            configLineChartStyle(this)
            data = LineData(sysSet, diaSet)
            xAxis.valueFormatter = TimeAxisFormatter(timestamps)
            val maxVisibleX = 13f
            setVisibleXRangeMaximum(maxVisibleX)
            if (entriesSys.size > maxVisibleX) {
                moveViewToX(entriesSys.size.toFloat() - maxVisibleX)
            } else {
                invalidate()
            }
        }
    }

    private fun aggiornaGrafici() {
        val ringManager = SmartRingManager.getActiveInstance()
        val activeMeasurement = ringManager?.getActiveMeasurementType()

        if (isBpmFirstLoad || activeMeasurement == "BPM") {
            caricaBpm()
            isBpmFirstLoad = false
        }
        if (isO2FirstLoad || activeMeasurement == "O2") {
            caricaO2()
            isO2FirstLoad = false
        }
        if (isPressureFirstLoad || activeMeasurement == "PRESSURE") {
            caricaPressione()
            isPressureFirstLoad = false
        }

        refreshPieChart()
        aggiornaPassi()
    }

    private fun aggiornaPassi() {
        val listaCompleta = gestoreStatistiche.getSteps().sortedBy { it.timestamp }
        if (listaCompleta.isEmpty()) return

        tvTotalSteps.text = listaCompleta.last().tot.toString()

        val entries = listaCompleta.map {
            Entry(it.timestamp.toFloat(), it.tot.toFloat())
        }

        val timestamps = listaCompleta.map { it.timestamp }

        val dataSet = LineDataSet(entries, "Daily Steps").apply {
            color = Color.BLUE
            lineWidth = 3f
            setDrawCircles(false)
            setDrawValues(false)
        }

        val lineData = LineData(dataSet)
        stepsChart.data = lineData
        stepsChart.xAxis.valueFormatter = TimeAxisFormatter(timestamps)
        stepsChart.invalidate()
    }
}

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