package com.example.myapplication

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter

class ChartsFragment : Fragment() {

    private lateinit var lineChart: LineChart
    private lateinit var tvCurrentBpm: TextView

    private val activityLabels  = listOf("Walking", "Running", "Sitting", "Standing", "Cycling")
    private val activityMinutes = listOf(42f, 18f, 65f, 20f, 15f)
    private val activityColors  = listOf(
        Color.parseColor("#4CAF74"), Color.parseColor("#66BB6A"),
        Color.parseColor("#7A9B7D"), Color.parseColor("#2E7D52"), Color.parseColor("#D4A044")
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_charts, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lineChart = view.findViewById(R.id.lineChart)
        tvCurrentBpm = view.findViewById(R.id.tvCurrentBpm)

        setupBarChart(view.findViewById(R.id.barChart))
        setupPieChart(view.findViewById(R.id.pieChart))

        // Recupera history dal companion object
        val history = CurrentActivityFragment.bpmHistory
        if (history.isNotEmpty()) {
            updateHeartRateChart(history, history.last().toInt())
        }
    }

    private fun setupBarChart(chart: BarChart) {
        val entries = activityMinutes.mapIndexed { i, v -> BarEntry(i.toFloat(), v) }
        chart.data = BarData(BarDataSet(entries, "Minuti").apply { colors = activityColors; valueTextColor = Color.WHITE })
        chart.xAxis.apply { valueFormatter = IndexAxisValueFormatter(activityLabels); position = XAxis.XAxisPosition.BOTTOM; textColor = Color.LTGRAY }
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.invalidate()
    }

    private fun setupPieChart(chart: PieChart) {
        val entries = activityLabels.mapIndexed { i, label -> PieEntry(activityMinutes[i], label) }
        chart.data = PieData(PieDataSet(entries, "").apply { colors = activityColors; valueTextColor = Color.WHITE; sliceSpace = 3f })
        chart.description.isEnabled = false
        chart.setHoleColor(Color.TRANSPARENT)
        chart.legend.textColor = Color.LTGRAY
        chart.invalidate()
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

        lineChart.data = LineData(dataSet)
        lineChart.description.isEnabled = false
        lineChart.xAxis.textColor = Color.LTGRAY
        lineChart.axisLeft.textColor = Color.LTGRAY
        lineChart.axisRight.isEnabled = false

        lineChart.invalidate()
        tvCurrentBpm.text = currentBpm.toString()
    }
}