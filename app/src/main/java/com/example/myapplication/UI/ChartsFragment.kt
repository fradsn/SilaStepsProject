package com.example.myapplication.UI

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.myapplication.GestoreStatistiche
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

    private lateinit var BPMChart: LineChart
    private lateinit var pieChart: PieChart
    private lateinit var tvCurrentBpm: TextView
    private lateinit var O2Chart: LineChart
    private lateinit var pressureChart: LineChart

    private lateinit var tvLastPressure: TextView
    private lateinit var tvLastO2: TextView
    private val activityLabels = listOf("Walking", "Jogging", "Sitting", "Standing")
    private lateinit var gestoreStatistiche: GestoreStatistiche

    private val activityColorMap = mapOf(
        "Walking" to Color.parseColor("#4CAF74"),
        "Jogging" to Color.parseColor("#66BB6A"),
        "Sitting" to Color.parseColor("#7A9B7D"),
        "Standing" to Color.parseColor("#2E7D52")
    )

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
        pieChart = view.findViewById(R.id.activityChart)
        tvCurrentBpm = view.findViewById(R.id.tvCurrentBpm)
        tvLastO2 = view.findViewById(R.id.tvLastO2)
        O2Chart = view.findViewById(R.id.O2Chart)

        tvLastPressure = view.findViewById(R.id.tvLastPressure)
        pressureChart = view.findViewById(R.id.bloodPressureChart)
        setupPieChartStyle()
        refreshPieChart()

        MotionSessionManager.addObserver(this)

        val history = CurrentActivityFragment.bpmHistory
        if (history.isNotEmpty()) {
            updateHeartRateChart(history, history.last().toInt())
        }
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

    fun updateHeartRateChart(bpmList: List<Float>, currentBpm: Int) {
        val entries = bpmList.mapIndexed { i, v -> Entry(i.toFloat(), v) }
        val dataSet = LineDataSet(entries, "BPM").apply {
            color = Color.parseColor("#D4A044")
            setDrawCircles(false)
            lineWidth = 2.5f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = Color.parseColor("#D4A044")
            fillAlpha = 40
        }

        BPMChart.data = LineData(dataSet)
        BPMChart.description.isEnabled = false
        BPMChart.xAxis.textColor = Color.LTGRAY
        BPMChart.axisLeft.textColor = Color.LTGRAY
        BPMChart.axisRight.isEnabled = false
        BPMChart.invalidate()

        tvCurrentBpm.text = currentBpm.toString()
    }
    private fun caricaO2() {
        val lista = gestoreStatistiche.getO2()
        if (lista.isEmpty()) return

        val ultimo = lista.last()
        tvLastO2.text = "${ultimo.value} %"

        val entries = lista.mapIndexed { index, item ->
            Entry(index.toFloat(), item.value.toFloat())
        }

        val dataSet = LineDataSet(entries, "SpO2").apply {
            color = Color.parseColor("#4CAF50")
            setCircleColor(Color.WHITE)
            lineWidth = 2f
            circleRadius = 3f
            valueTextColor = Color.WHITE
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        O2Chart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            axisRight.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.textColor = Color.WHITE
            axisLeft.textColor = Color.WHITE
            legend.textColor = Color.WHITE
            invalidate()
        }
    }

    private fun caricaPressione() {
        val lista = gestoreStatistiche.getPressioni()
        if (lista.isEmpty()) return

        val ultimo = lista.last()
        tvLastPressure.text = "${ultimo.systolic}/${ultimo.diastolic}"

        val entriesSys = lista.mapIndexed { index, item ->
            Entry(index.toFloat(), item.systolic.toFloat())
        }

        val entriesDia = lista.mapIndexed { index, item ->
            Entry(index.toFloat(), item.diastolic.toFloat())
        }

        val sysSet = LineDataSet(entriesSys, "Sistolica").apply {
            color = Color.parseColor("#E91E63")
            setCircleColor(Color.WHITE)
            lineWidth = 2f
            circleRadius = 3f
            valueTextColor = Color.WHITE
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        val diaSet = LineDataSet(entriesDia, "Diastolica").apply {
            color = Color.parseColor("#03A9F4")
            setCircleColor(Color.WHITE)
            lineWidth = 2f
            circleRadius = 3f
            valueTextColor = Color.WHITE
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        pressureChart.apply {
            data = LineData(sysSet, diaSet)
            description.isEnabled = false
            axisRight.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.textColor = Color.WHITE
            axisLeft.textColor = Color.WHITE
            legend.textColor = Color.WHITE
            invalidate()
        }
    }

}