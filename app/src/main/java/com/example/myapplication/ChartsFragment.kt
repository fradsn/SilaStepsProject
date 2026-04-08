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
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.PercentFormatter

class ChartsFragment : Fragment() {

    private val colorGreen      = Color.parseColor("#4CAF74")
    private val colorGreenLight = Color.parseColor("#66BB6A")
    private val colorAmber      = Color.parseColor("#D4A044")
    private val colorTeal       = Color.parseColor("#2E7D52")
    private val colorSage       = Color.parseColor("#7A9B7D")
    private val colorBg         = Color.parseColor("#152017")
    private val colorText       = Color.parseColor("#F0F7F0")
    private val colorMuted      = Color.parseColor("#7A9B7D")
    private val colorGrid       = Color.parseColor("#1F2B1F")

    private val activityLabels  = listOf("Walking", "Running", "Sitting", "Standing", "Cycling")
    private val activityMinutes = listOf(42f, 18f, 65f, 20f, 15f)
    private val activityColors  = listOf(colorGreen, colorGreenLight, colorSage, colorTeal, colorAmber)

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_charts, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSummaryCards(view)
        setupBarChart(view.findViewById(R.id.barChart))
        setupLineChart(view.findViewById(R.id.lineChart))
        setupPieChart(view.findViewById(R.id.pieChart))

        val tvCurrentBpm = view.findViewById<TextView>(R.id.tvCurrentBpm)

        // ripristina eventuale storico già raccolto
        val history = CurrentActivityFragment.bpmHistory
        if (history.isNotEmpty()) {
            updateHeartRateChart(history, history.last().toInt())
        } else {
            tvCurrentBpm.text = "--"
        }
    }

    // ─────────────────────── Summary Cards ────────────────────────────────────

    private fun setupSummaryCards(view: View) {
        view.findViewById<TextView>(R.id.tvTotalSteps).text    = "0"
        view.findViewById<TextView>(R.id.tvTotalCalories).text = "0"
        view.findViewById<TextView>(R.id.tvActiveMinutes).text = "0"
    }

    // ─────────────────────── Bar Chart ────────────────────────────────────────

    private fun setupBarChart(chart: BarChart) {
        val entries = activityMinutes.mapIndexed { i, v -> BarEntry(i.toFloat(), v) }

        val dataSet = BarDataSet(entries, "Minuti").apply {
            colors         = activityColors
            valueTextColor = colorText
            valueTextSize  = 11f
        }

        chart.apply {
            data = BarData(dataSet).apply { barWidth = 0.6f }
            description.isEnabled = false
            legend.isEnabled      = false
            setDrawGridBackground(false)
            setDrawBorders(false)
            setBackgroundColor(colorBg)
            setExtraOffsets(0f, 0f, 0f, 8f)
            animateY(800)

            xAxis.apply {
                valueFormatter     = IndexAxisValueFormatter(activityLabels)
                position           = XAxis.XAxisPosition.BOTTOM
                granularity        = 1f
                setDrawGridLines(false)
                textColor          = colorMuted
                textSize           = 11f
                labelRotationAngle = -20f
            }
            axisLeft.apply {
                textColor   = colorMuted
                gridColor   = colorGrid
                axisMinimum = 0f
                textSize    = 11f
            }
            axisRight.isEnabled = false
        }
    }

    // ─────────────────────── Line Chart ───────────────────────────────────────

    private fun setupLineChart(chart: LineChart) {
        // nessun dato sample: grafico vuoto inizialmente
        val entries = listOf<Entry>()

        val dataSet = LineDataSet(entries, "BPM").apply {
            color          = colorAmber
            lineWidth      = 2.5f
            circleRadius   = 3f
            setCircleColor(colorAmber)
            setDrawCircleHole(false)
            setDrawFilled(true)
            fillColor      = colorAmber
            fillAlpha      = 30
            valueTextColor = Color.TRANSPARENT
            mode           = LineDataSet.Mode.CUBIC_BEZIER
        }

        chart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled      = false
            setDrawGridBackground(false)
            setDrawBorders(false)
            setBackgroundColor(colorBg)
            animateX(1000)

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                textColor = colorMuted
                textSize  = 10f
                setDrawLabels(false)
            }
            axisLeft.apply {
                textColor   = colorMuted
                gridColor   = colorGrid
                axisMinimum = 40f
                axisMaximum = 180f
                textSize    = 10f
            }
            axisRight.isEnabled = false
        }
    }

    /**
     * Aggiorna il grafico con i dati reali dei BPM.
     */
    fun updateHeartRateChart(bpmList: List<Float>, currentBpm: Int) {
        val chart = view?.findViewById<LineChart>(R.id.lineChart) ?: return
        val entries = bpmList.mapIndexed { i, v -> Entry(i.toFloat(), v) }
        val dataSet = chart.data?.getDataSetByIndex(0) as? LineDataSet ?: return
        dataSet.values = entries
        chart.data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.invalidate()
        view?.findViewById<TextView>(R.id.tvCurrentBpm)?.text = currentBpm.toString()
    }

    // ─────────────────────── Pie Chart ────────────────────────────────────────

    private fun setupPieChart(chart: PieChart) {
        val entries = activityLabels.mapIndexed { i, label ->
            PieEntry(activityMinutes[i], label)
        }

        val dataSet = PieDataSet(entries, "").apply {
            colors         = activityColors
            valueTextColor = colorText
            valueTextSize  = 12f
            valueFormatter = PercentFormatter(chart)
            sliceSpace     = 3f
        }

        chart.apply {
            data = PieData(dataSet)
            description.isEnabled   = false
            isDrawHoleEnabled       = true
            holeRadius              = 52f
            transparentCircleRadius = 57f
            setHoleColor(colorBg)
            setTransparentCircleColor(colorBg)
            setTransparentCircleAlpha(80)
            setUsePercentValues(true)
            setDrawEntryLabels(false)
            setBackgroundColor(colorBg)
            legend.apply {
                isEnabled = true
                textColor = colorMuted
                textSize  = 11f
                form      = Legend.LegendForm.CIRCLE
            }
            animateY(1000)
        }
    }
}