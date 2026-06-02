package com.example.myapplication.UI

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.myapplication.services.GestoreStatistiche
import com.example.myapplication.services.TimeAxisFormatter
import com.example.myapplication.Motion.session.MotionSessionManager
import com.example.myapplication.Motion.session.MotionUiState
import com.example.myapplication.R
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter

class ChartsFragment : Fragment(), MotionSessionManager.Observer {

    private lateinit var pieChart: PieChart
    private lateinit var BPMChart: LineChart
    private lateinit var O2Chart: LineChart
    private lateinit var pressureChart: LineChart

    private lateinit var tvCurrentBpm: TextView
    private lateinit var tvLastO2: TextView
    private lateinit var tvLastPressure: TextView

    private val activityLabels = listOf("Walking", "Jogging", "Sitting", "Standing")
    private val activityColorMap = mapOf(
        "Walking" to Color.parseColor("#4CAF74"),
        "Jogging" to Color.parseColor("#66BB6A"),
        "Sitting" to Color.parseColor("#7A9B7D"),
        "Standing" to Color.parseColor("#2E7D52")
    )

    private lateinit var gestoreStatistiche: GestoreStatistiche

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

        tvLastO2 = view.findViewById(R.id.tvLastO2)
        O2Chart = view.findViewById(R.id.O2Chart)

        tvLastPressure = view.findViewById(R.id.tvLastPressure)
        pressureChart = view.findViewById(R.id.bloodPressureChart)

        pieChart = view.findViewById(R.id.activityChart)
        setupPieChartStyle()
        refreshPieChart()

        MotionSessionManager.addObserver(this)

        caricaBpm()
        caricaO2()
        caricaPressione()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        MotionSessionManager.removeObserver(this)
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
        val lista = gestoreStatistiche.getBpm()
        if (lista.isEmpty()) return

        // Aggiorna valore corrente
        val ultimo = lista.last()
        tvCurrentBpm.text = ultimo.bpm.toString()

        // Lista timestamp
        val timestamps = lista.map { it.timestamp }

        // Entries
        val entries = lista.mapIndexed { index, item ->
            Entry(index.toFloat(), item.bpm.toFloat())
        }

        val dataSet = LineDataSet(entries, "BPM").apply {
            color = Color.parseColor("#FF9800")
            lineWidth = 2f
            mode = LineDataSet.Mode.CUBIC_BEZIER

            valueTextColor = Color.WHITE
            setCircleColor(Color.WHITE)
            circleRadius = 3f

            setDrawCircles(false)
            setDrawValues(false)
        }

        BPMChart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            axisRight.isEnabled = false

            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.textColor = Color.WHITE
            axisLeft.textColor = Color.WHITE
            legend.textColor = Color.WHITE

            xAxis.valueFormatter = TimeAxisFormatter(timestamps)
            xAxis.granularity = 1f
            xAxis.labelRotationAngle = -45f

            invalidate()
        }
    }

    private fun caricaO2() {
        val lista = gestoreStatistiche.getO2()
        if (lista.isEmpty()) return

        val ultimo = lista.last()
        tvLastO2.text = "${ultimo.value} %"

        val timestamps = lista.map { it.timestamp }

        val entries = lista.mapIndexed { index, item ->
            Entry(index.toFloat(), item.value.toFloat())
        }

        val dataSet = LineDataSet(entries, "SpO2").apply {
            color = Color.parseColor("#4CAF50")
            lineWidth = 2f
            mode = LineDataSet.Mode.CUBIC_BEZIER

            valueTextColor = Color.WHITE
            setCircleColor(Color.WHITE)
            circleRadius = 3f

            setDrawCircles(false)
            setDrawValues(false)
        }

        O2Chart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            axisRight.isEnabled = false

            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.textColor = Color.WHITE
            axisLeft.textColor = Color.WHITE
            legend.textColor = Color.WHITE

            xAxis.valueFormatter = TimeAxisFormatter(timestamps)
            xAxis.granularity = 1f
            xAxis.labelRotationAngle = -45f

            invalidate()
        }
    }

    private fun caricaPressione() {
        val lista = gestoreStatistiche.getPressioni()
        if (lista.isEmpty()) return

        val ultimo = lista.last()
        tvLastPressure.text = "${ultimo.systolic}/${ultimo.diastolic}"

        val timestamps = lista.map { it.timestamp }

        val entriesSys = lista.mapIndexed { index, item ->
            Entry(index.toFloat(), item.systolic.toFloat())
        }

        val entriesDia = lista.mapIndexed { index, item ->
            Entry(index.toFloat(), item.diastolic.toFloat())
        }

        val sysSet = LineDataSet(entriesSys, "Sistolica").apply {
            color = Color.parseColor("#E91E63")
            lineWidth = 2f
            mode = LineDataSet.Mode.CUBIC_BEZIER

            valueTextColor = Color.WHITE
            setCircleColor(Color.WHITE)
            circleRadius = 3f

            setDrawCircles(false)
            setDrawValues(false)
        }

        val diaSet = LineDataSet(entriesDia, "Diastolica").apply {
            color = Color.parseColor("#03A9F4")
            lineWidth = 2f
            mode = LineDataSet.Mode.CUBIC_BEZIER

            valueTextColor = Color.WHITE
            setCircleColor(Color.WHITE)
            circleRadius = 3f

            setDrawCircles(false)
            setDrawValues(false)
        }

        pressureChart.apply {
            data = LineData(sysSet, diaSet)
            description.isEnabled = false
            axisRight.isEnabled = false

            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.textColor = Color.WHITE
            axisLeft.textColor = Color.WHITE
            legend.textColor = Color.WHITE

            xAxis.valueFormatter = TimeAxisFormatter(timestamps)
            xAxis.granularity = 1f
            xAxis.labelRotationAngle = -45f

            invalidate()
        }
    }

}