package com.example.myapplication.UI

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.myapplication.BT.ring.SmartRingManager
import com.example.myapplication.Motion.session.MotionSessionManager
import com.example.myapplication.R
import com.example.myapplication.db.GestoreStatistiche
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

class ChartsFragment : Fragment() {

    private lateinit var pieChart: PieChart
    private lateinit var BPMChart: LineChart
    private lateinit var O2Chart: LineChart
    private lateinit var pressureChart: LineChart
    private lateinit var stepsChart: BarChart
    private lateinit var map: MapView

    private lateinit var tvCurrentBpm: TextView
    private lateinit var tvLastO2: TextView
    private lateinit var tvLastPressure: TextView

    private lateinit var tvTotalSteps: TextView
    private lateinit var tvAverageSteps: TextView
    private lateinit var tvStepCompletion: TextView
    private lateinit var tvStepDistance: TextView
    private lateinit var tvStepCalories: TextView
    private lateinit var tvStepPeriodLabel: TextView

    private lateinit var stepPeriodToggle: MaterialButtonToggleGroup
    private lateinit var btnPreviousStepPeriod: MaterialButton
    private lateinit var btnNextStepPeriod: MaterialButton

    private lateinit var cbLockScrollBpm: CheckBox
    private lateinit var cbLockScrollO2: CheckBox
    private lateinit var cbLockScrollPressure: CheckBox
    private lateinit var cbEnableHistory: CheckBox

    private val activityLabels = listOf(
        "Walking",
        "Jogging",
        "Sitting",
        "Standing"
    )

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
    private var isPieChartFirstLoad = true

    private enum class StepPeriod {
        DAY,
        WEEK,
        MONTH
    }

    private data class StepChartData(
        val values: List<Int>,
        val labels: List<String>,
        val totalSteps: Int,
        val daysForAverage: Int,
        val periodLabel: String
    )

    private var selectedStepPeriod = StepPeriod.DAY
    private var selectedStepDate: LocalDate = LocalDate.now()

    /*
     * Parametri utilizzati per le stime.
     * 8000 passi rappresentano l'obiettivo giornaliero.
     * Ogni passo viene stimato in circa 70 centimetri.
     * Le calorie sono stimate in 0,04 kcal per passo.
     */
    private val stepLocale = Locale.ENGLISH
    private val dailyStepGoal = 8_000
    private val stepLengthKm = 0.0007
    private val caloriesPerStep = 0.04

    private val pollHandler = Handler(Looper.getMainLooper())

    private val pollRunnableStandard = object : Runnable {
        override fun run() {
            aggiornaGraficiStandard()
            pollHandler.postDelayed(this, 2_000)
        }
    }

    private val pollRunnableDelay = object : Runnable {
        override fun run() {
            aggiornaGraficiDelay()
            pollHandler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val context = requireContext()

        gestoreStatistiche =
            GestoreStatistiche.getInstance(context)

        Configuration.getInstance().load(
            context,
            context.getSharedPreferences(
                "osmdroid",
                MODE_PRIVATE
            )
        )

        Configuration.getInstance().userAgentValue =
            context.packageName
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(
            R.layout.fragment_charts,
            container,
            false
        )
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        BPMChart = view.findViewById(R.id.BPMChart)
        tvCurrentBpm = view.findViewById(R.id.tvCurrentBpm)
        cbLockScrollBpm =
            view.findViewById(R.id.cbLockScrollBpm)

        O2Chart = view.findViewById(R.id.O2Chart)
        tvLastO2 = view.findViewById(R.id.tvLastO2)
        cbLockScrollO2 =
            view.findViewById(R.id.cbLockScrollO2)

        pressureChart =
            view.findViewById(R.id.bloodPressureChart)

        tvLastPressure =
            view.findViewById(R.id.tvLastPressure)

        cbLockScrollPressure =
            view.findViewById(R.id.cbLockScrollPressure)

        pieChart = view.findViewById(R.id.activityChart)

        setupPieChartStyle()
        refreshPieChart()

        map = view.findViewById(R.id.map)
        cbEnableHistory =
            view.findViewById(R.id.cbEnableHistory)

        setupMap()
        drawPath()

        /*
         * Riferimenti relativi al nuovo grafico dei passi.
         */
        stepsChart = view.findViewById(R.id.StepsChart)

        tvTotalSteps =
            view.findViewById(R.id.tvTotalSteps)

        tvAverageSteps =
            view.findViewById(R.id.tvAverageSteps)

        tvStepCompletion =
            view.findViewById(R.id.tvStepCompletion)

        tvStepDistance =
            view.findViewById(R.id.tvStepDistance)

        tvStepCalories =
            view.findViewById(R.id.tvStepCalories)

        tvStepPeriodLabel =
            view.findViewById(R.id.tvStepPeriodLabel)

        stepPeriodToggle =
            view.findViewById(R.id.stepPeriodToggle)

        btnPreviousStepPeriod =
            view.findViewById(R.id.btnPreviousStepPeriod)

        btnNextStepPeriod =
            view.findViewById(R.id.btnNextStepPeriod)

        val customMarker = CustomMarkerView(
            requireContext(),
            R.layout.custom_marker_view
        )

        BPMChart.marker = customMarker
        O2Chart.marker = customMarker
        pressureChart.marker = customMarker
        stepsChart.marker = customMarker

        setupStepsAnalytics()

        cbLockScrollBpm.setOnCheckedChangeListener {
                _, isChecked ->
            if (!isChecked) {
                caricaBpm()
            }
        }

        cbLockScrollO2.setOnCheckedChangeListener {
                _, isChecked ->
            if (!isChecked) {
                caricaO2()
            }
        }

        cbLockScrollPressure.setOnCheckedChangeListener {
                _, isChecked ->
            if (!isChecked) {
                caricaPressione()
            }
        }

        cbEnableHistory.setOnCheckedChangeListener {
                _, _ ->
            drawPath()
        }

        isBpmFirstLoad = true
        isO2FirstLoad = true
        isPressureFirstLoad = true

        aggiornaGraficiStandard()
        aggiornaGraficiDelay()

        pollHandler.post(pollRunnableStandard)
        pollHandler.post(pollRunnableDelay)
    }

    private fun drawPath() {
        map.overlayManager.clear()

        val positions = gestoreStatistiche
            .getPositions()
            .sortedBy { it.timestamp }

        if (positions.isEmpty()) {
            map.invalidate()
            return
        }

        val geoPoints: MutableList<GeoPoint> =
            mutableListOf()

        if (!cbEnableHistory.isChecked) {
            val currentPosition = positions.last()

            geoPoints.add(
                GeoPoint(
                    currentPosition.latitude,
                    currentPosition.longitude
                )
            )
        } else {
            geoPoints.addAll(
                positions.map { position ->
                    GeoPoint(
                        position.latitude,
                        position.longitude
                    )
                }
            )
        }

        val polyline = Polyline().apply {
            outlinePaint.color = Color.BLUE
            outlinePaint.strokeWidth = 5f
            setPoints(geoPoints)
        }

        map.overlayManager.add(polyline)

        geoPoints.forEach { point ->
            val marker = Marker(map).apply {
                position = point

                icon = ContextCompat.getDrawable(
                    requireContext(),
                    R.drawable.ic_dot
                )

                setAnchor(
                    Marker.ANCHOR_CENTER,
                    Marker.ANCHOR_CENTER
                )
            }

            map.overlayManager.add(marker)
        }

        val bounds =
            BoundingBox.fromGeoPoints(geoPoints)

        val padding = 0.0002

        val paddedBounds = BoundingBox(
            bounds.latNorth + padding,
            bounds.lonEast + padding,
            bounds.latSouth - padding,
            bounds.lonWest - padding
        )

        map.zoomToBoundingBox(
            paddedBounds,
            true
        )

        val maxZoom = 18.0

        if (map.zoomLevelDouble > maxZoom) {
            map.controller.setZoom(maxZoom)
        }

        map.invalidate()
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(false)
        map.isClickable = false
        map.isLongClickable = false
        map.controller.setZoom(15.0)
    }

    override fun onPause() {
        super.onPause()

        pollHandler.removeCallbacks(
            pollRunnableStandard
        )

        pollHandler.removeCallbacks(
            pollRunnableDelay
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()

        pollHandler.removeCallbacks(
            pollRunnableStandard
        )

        pollHandler.removeCallbacks(
            pollRunnableDelay
        )
    }

    private fun setupPieChartStyle() {
        pieChart.description.isEnabled = false
        pieChart.setUsePercentValues(true)
        pieChart.setHoleColor(Color.TRANSPARENT)

        pieChart.setCenterTextColor(
            resources.getColor(R.color.text_primary)
        )

        pieChart.setCenterTextSize(14f)

        pieChart.legend.textColor =
            resources.getColor(R.color.text_secondary)

        pieChart.setEntryLabelColor(
            resources.getColor(R.color.text_primary)
        )

        pieChart.centerText = "Activities"
    }

    private fun refreshPieChart() {
        val counts =
            gestoreStatistiche.getActivityCount()

        val total = counts.values.sum()

        if (total == 0) {
            pieChart.clear()
            pieChart.centerText = "No data available"
            pieChart.invalidate()
            return
        }

        val entries = activityLabels.mapNotNull { label ->
            val count = counts[label] ?: 0

            if (count > 0) {
                PieEntry(
                    count.toFloat(),
                    label
                )
            } else {
                null
            }
        }

        val colors = entries.mapNotNull { entry ->
            activityColorMap[entry.label]
        }

        val dataSet = PieDataSet(
            entries,
            ""
        ).apply {
            this.colors = colors

            valueTextColor =
                resources.getColor(R.color.text_primary)

            valueTextSize = 12f
            sliceSpace = 4f
        }

        val data = PieData(dataSet).apply {
            setValueFormatter(
                PercentFormatter(pieChart)
            )
        }

        pieChart.data = data
        pieChart.centerText = "Activities"
        pieChart.invalidate()
    }

    /*
     * Configurazione dei grafici biometrici già esistenti.
     * Questa parte non viene utilizzata dal grafico dei passi.
     */
    private fun configLineChartStyle(
        chart: LineChart
    ) {
        chart.description.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.setDrawGridBackground(false)

        chart.legend.textColor =
            resources.getColor(R.color.text_secondary)

        chart.setNoDataText(
            "Awaiting biometric streaming..."
        )

        chart.setNoDataTextColor(
            resources.getColor(R.color.text_secondary)
        )

        chart.setOnTouchListener { chartView, _ ->
            chartView.parent
                .requestDisallowInterceptTouchEvent(true)

            false
        }

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM

            textColor =
                resources.getColor(R.color.text_secondary)

            setDrawGridLines(false)
            setDrawAxisLine(false)

            granularity = 1f
            labelRotationAngle = -45f
            setAvoidFirstLastClipping(true)
        }

        chart.axisLeft.apply {
            textColor =
                resources.getColor(R.color.text_secondary)

            setDrawGridLines(true)

            gridColor = resources.getColor(
                R.color.surface_variant_dark
            )

            setDrawAxisLine(false)
        }
    }

    private fun caricaBpm() {
        val completeList =
            gestoreStatistiche.getBpm()

        if (completeList.isEmpty()) {
            return
        }

        val lastEntry = completeList.last()

        tvCurrentBpm.text =
            lastEntry.bpm.toString()

        if (cbLockScrollBpm.isChecked) {
            return
        }

        val list = completeList.takeLast(300)

        val timestamps =
            list.map { it.timestamp }

        val entries = list.mapIndexed {
                index, item ->
            Entry(
                index.toFloat(),
                item.bpm.toFloat()
            )
        }

        val mainColor =
            resources.getColor(R.color.health_bpm)

        val dataSet = LineDataSet(
            entries,
            "Heart Rate"
        ).apply {
            color = mainColor
            lineWidth = 3f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)

            fillFormatter =
                com.github.mikephil.charting.formatter
                    .IFillFormatter { _, _ ->
                        BPMChart.axisLeft.axisMinimum
                    }

            val gradientShader = LinearGradient(
                0f,
                0f,
                0f,
                BPMChart.height.toFloat(),
                mainColor,
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )

            val paint = BPMChart.getPaint(
                com.github.mikephil.charting.charts
                    .Chart.PAINT_GRID_BACKGROUND
            )

            paint.shader = gradientShader

            fillAlpha = 45
            setDrawCircles(false)
            setDrawValues(false)

            highLightColor =
                resources.getColor(R.color.text_primary)

            highlightLineWidth = 1f
        }

        BPMChart.apply {
            configLineChartStyle(this)

            data = LineData(dataSet)

            xAxis.valueFormatter =
                TimeAxisFormatter(timestamps)

            val maxVisibleX = 13f

            setVisibleXRangeMaximum(maxVisibleX)

            if (entries.size > maxVisibleX) {
                moveViewToX(
                    entries.size.toFloat() -
                            maxVisibleX
                )
            } else {
                invalidate()
            }
        }
    }

    private fun caricaO2() {
        val completeList =
            gestoreStatistiche.getO2()

        if (completeList.isEmpty()) {
            return
        }

        val lastEntry = completeList.last()

        tvLastO2.text =
            "${lastEntry.value} %"

        if (cbLockScrollO2.isChecked) {
            return
        }

        val list = completeList.takeLast(150)

        val timestamps =
            list.map { it.timestamp }

        val entries = list.mapIndexed {
                index, item ->
            Entry(
                index.toFloat(),
                item.value.toFloat()
            )
        }

        val mainColor =
            resources.getColor(R.color.health_o2)

        val dataSet = LineDataSet(
            entries,
            "SpO2"
        ).apply {
            color = mainColor
            lineWidth = 3f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillAlpha = 40
            setDrawCircles(false)
            setDrawValues(false)

            highLightColor =
                resources.getColor(R.color.text_primary)
        }

        O2Chart.apply {
            configLineChartStyle(this)

            data = LineData(dataSet)

            xAxis.valueFormatter =
                TimeAxisFormatter(timestamps)

            val maxVisibleX = 13f

            setVisibleXRangeMaximum(maxVisibleX)

            if (entries.size > maxVisibleX) {
                moveViewToX(
                    entries.size.toFloat() -
                            maxVisibleX
                )
            } else {
                invalidate()
            }
        }
    }

    private fun caricaPressione() {
        val completeList =
            gestoreStatistiche.getPressioni()

        if (completeList.isEmpty()) {
            return
        }

        val lastEntry = completeList.last()

        tvLastPressure.text =
            "${lastEntry.systolic}/${lastEntry.diastolic}"

        if (cbLockScrollPressure.isChecked) {
            return
        }

        val list = completeList.takeLast(150)

        val timestamps =
            list.map { it.timestamp }

        val systolicEntries =
            list.mapIndexed { index, item ->
                Entry(
                    index.toFloat(),
                    item.systolic.toFloat()
                )
            }

        val diastolicEntries =
            list.mapIndexed { index, item ->
                Entry(
                    index.toFloat(),
                    item.diastolic.toFloat()
                )
            }

        val systolicColor =
            resources.getColor(R.color.health_pressure)

        val diastolicColor =
            resources.getColor(R.color.primary_neon)

        val systolicSet = LineDataSet(
            systolicEntries,
            "Systolic"
        ).apply {
            color = systolicColor
            lineWidth = 3f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawCircles(false)
            setDrawValues(false)

            highLightColor =
                resources.getColor(R.color.text_primary)
        }

        val diastolicSet = LineDataSet(
            diastolicEntries,
            "Diastolic"
        ).apply {
            color = diastolicColor
            lineWidth = 3f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawCircles(false)
            setDrawValues(false)

            highLightColor =
                resources.getColor(R.color.text_primary)
        }

        pressureChart.apply {
            configLineChartStyle(this)

            data = LineData(
                systolicSet,
                diastolicSet
            )

            xAxis.valueFormatter =
                TimeAxisFormatter(timestamps)

            val maxVisibleX = 13f

            setVisibleXRangeMaximum(maxVisibleX)

            if (systolicEntries.size > maxVisibleX) {
                moveViewToX(
                    systolicEntries.size.toFloat() -
                            maxVisibleX
                )
            } else {
                invalidate()
            }
        }
    }

    private fun aggiornaGraficiStandard() {
        val ringManager =
            SmartRingManager.getActiveInstance()

        val activeMeasurement =
            ringManager?.getActiveMeasurementType()

        if (
            isBpmFirstLoad ||
            activeMeasurement == "BPM"
        ) {
            caricaBpm()
            isBpmFirstLoad = false
        }

        if (
            isO2FirstLoad ||
            activeMeasurement == "O2"
        ) {
            caricaO2()
            isO2FirstLoad = false
        }

        if (
            isPressureFirstLoad ||
            activeMeasurement == "PRESSURE"
        ) {
            caricaPressione()
            isPressureFirstLoad = false
        }

        if (
            isPieChartFirstLoad ||
            MotionSessionManager.isShimmerConnected()
        ) {
            refreshPieChart()
            isPieChartFirstLoad = false
        }
    }

    private fun aggiornaGraficiDelay() {
        aggiornaPassi()
        drawPath()
    }

    /*
     * Configurazione pulsanti Giorno, Settimana e Mese.
     */
    private fun setupStepsAnalytics() {
        configureStepsChart()

        stepPeriodToggle.check(R.id.btnStepDay)

        stepPeriodToggle.addOnButtonCheckedListener {
                _, checkedId, isChecked ->

            if (!isChecked) {
                return@addOnButtonCheckedListener
            }

            selectedStepPeriod = when (checkedId) {
                R.id.btnStepWeek ->
                    StepPeriod.WEEK

                R.id.btnStepMonth ->
                    StepPeriod.MONTH

                else ->
                    StepPeriod.DAY
            }

            aggiornaPassi()
        }

        btnPreviousStepPeriod.setOnClickListener {
            moveSelectedStepPeriod(-1)
        }

        btnNextStepPeriod.setOnClickListener {
            if (!isCurrentStepPeriod()) {
                moveSelectedStepPeriod(1)
            }
        }
    }

    /*
     * Stile dell'unico BarChart utilizzato per i tre periodi.
     */
    private fun configureStepsChart() {
        stepsChart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            axisRight.isEnabled = false

            setDrawGridBackground(false)
            setScaleEnabled(false)
            setPinchZoom(false)
            isDoubleTapToZoomEnabled = false
            isDragEnabled = false
            setTouchEnabled(true)
            setFitBars(true)

            setNoDataText(
                "No step data available"
            )

            setNoDataTextColor(
                resources.getColor(
                    R.color.text_secondary
                )
            )

            xAxis.apply {
                position =
                    XAxis.XAxisPosition.BOTTOM

                textColor = resources.getColor(
                    R.color.text_secondary
                )

                setDrawGridLines(false)
                setDrawAxisLine(false)

                granularity = 1f
                isGranularityEnabled = true
                labelRotationAngle = 0f
            }

            axisLeft.apply {
                textColor = resources.getColor(
                    R.color.text_secondary
                )

                axisMinimum = 0f
                setDrawAxisLine(false)
                setDrawGridLines(true)

                gridColor = resources.getColor(
                    R.color.surface_variant_dark
                )

                enableGridDashedLine(
                    8f,
                    8f,
                    0f
                )
            }
        }
    }

    private fun moveSelectedStepPeriod(
        direction: Long
    ) {
        selectedStepDate =
            when (selectedStepPeriod) {
                StepPeriod.DAY ->
                    selectedStepDate.plusDays(direction)

                StepPeriod.WEEK ->
                    selectedStepDate.plusWeeks(direction)

                StepPeriod.MONTH ->
                    selectedStepDate.plusMonths(direction)
            }

        aggiornaPassi()
    }

    private fun aggiornaPassi() {
        if (!::stepsChart.isInitialized) {
            return
        }

        val chartData =
            when (selectedStepPeriod) {
                StepPeriod.DAY ->
                    createDayStepData()

                StepPeriod.WEEK ->
                    createWeekStepData()

                StepPeriod.MONTH ->
                    createMonthStepData()
            }

        renderStepChart(chartData)
        updateStepSummary(chartData)
        updateStepNavigation()
    }

    /*
     * Modalità Giorno: 24 barre, una per ogni ora.
     */
    private fun createDayStepData(): StepChartData {
        val selectedDay =
            selectedStepDate.toString()

        val hourlyMap = gestoreStatistiche
            .getPassiOrari(selectedDay)
            .associateBy { it.hour }

        val values = (0..23).map { hour ->
            hourlyMap[hour]?.steps ?: 0
        }

        val labels = (0..23).map { hour ->
            String.format(
                stepLocale,
                "%02d:00",
                hour
            )
        }

        val savedDailyTotal =
            gestoreStatistiche
                .getPassiGiornalieri(
                    selectedDay,
                    selectedDay
                )
                .firstOrNull()
                ?.steps

        val dateFormatter =
            DateTimeFormatter.ofPattern(
                "d MMMM yyyy",
                stepLocale
            )

        return StepChartData(
            values = values,
            labels = labels,
            totalSteps =
                savedDailyTotal ?: values.sum(),
            daysForAverage = 1,
            periodLabel =
                selectedStepDate.format(dateFormatter)
        )
    }

    /*
     * Modalità Settimana: da lunedì a domenica.
     */
    private fun createWeekStepData(): StepChartData {
        val firstDay =
            startOfWeek(selectedStepDate)

        val lastDay =
            firstDay.plusDays(6)

        val savedDays =
            gestoreStatistiche
                .getPassiGiornalieri(
                    firstDay.toString(),
                    lastDay.toString()
                )
                .associateBy { it.day }

        val dates = (0..6).map { offset ->
            firstDay.plusDays(
                offset.toLong()
            )
        }

        val values = dates.map { date ->
            savedDays[date.toString()]
                ?.steps
                ?: 0
        }

        val labels = dates.map { date ->
            date.dayOfWeek
                .getDisplayName(
                    TextStyle.SHORT,
                    stepLocale
                )
                .replace(".", "")
                .replaceFirstChar { character ->
                    character.uppercase(stepLocale)
                }
        }

        val shortFormatter =
            DateTimeFormatter.ofPattern(
                "d MMM",
                stepLocale
            )

        val longFormatter =
            DateTimeFormatter.ofPattern(
                "d MMM yyyy",
                stepLocale
            )

        return StepChartData(
            values = values,
            labels = labels,
            totalSteps = values.sum(),
            daysForAverage =
                calculateDaysForAverage(
                    firstDay,
                    lastDay
                ),
            periodLabel =
                "${firstDay.format(shortFormatter)} - " +
                        lastDay.format(longFormatter)
        )
    }

    /*
     * Modalità Mese: una barra per ogni giorno.
     */
    private fun createMonthStepData(): StepChartData {
        val firstDay =
            selectedStepDate.withDayOfMonth(1)

        val lastDay =
            selectedStepDate.withDayOfMonth(
                selectedStepDate.lengthOfMonth()
            )

        val savedDays =
            gestoreStatistiche
                .getPassiGiornalieri(
                    firstDay.toString(),
                    lastDay.toString()
                )
                .associateBy { it.day }

        val dates =
            (0 until selectedStepDate.lengthOfMonth())
                .map { offset ->
                    firstDay.plusDays(
                        offset.toLong()
                    )
                }

        val values = dates.map { date ->
            savedDays[date.toString()]
                ?.steps
                ?: 0
        }

        val labels = dates.map { date ->
            date.dayOfMonth.toString()
        }

        val monthLabel =
            firstDay.format(
                DateTimeFormatter.ofPattern(
                    "MMMM yyyy",
                    stepLocale
                )
            ).replaceFirstChar { character ->
                character.uppercase(stepLocale)
            }

        return StepChartData(
            values = values,
            labels = labels,
            totalSteps = values.sum(),
            daysForAverage =
                calculateDaysForAverage(
                    firstDay,
                    lastDay
                ),
            periodLabel = monthLabel
        )
    }

    private fun renderStepChart(
        chartData: StepChartData
    ) {
        val entries =
            chartData.values.mapIndexed {
                    index, steps ->

                BarEntry(
                    index.toFloat(),
                    steps.toFloat()
                )
            }

        val dataSet = BarDataSet(
            entries,
            "Steps"
        ).apply {
            color = resources.getColor(
                R.color.health_shimmer
            )

            highLightColor = resources.getColor(
                R.color.primary_neon
            )

            highLightAlpha = 100
            setDrawValues(false)
        }

        val barData = BarData(dataSet).apply {
            barWidth =
                when (selectedStepPeriod) {
                    StepPeriod.DAY -> 0.58f
                    StepPeriod.WEEK -> 0.52f
                    StepPeriod.MONTH -> 0.55f
                }
        }

        stepsChart.xAxis.valueFormatter =
            object : ValueFormatter() {

                override fun getAxisLabel(
                    value: Float,
                    axis: AxisBase?
                ): String {
                    val index =
                        value.roundToInt()

                    if (
                        index !in
                        chartData.labels.indices
                    ) {
                        return ""
                    }

                    val showLabel =
                        when (selectedStepPeriod) {
                            StepPeriod.DAY ->
                                index % 6 == 0 ||
                                        index ==
                                        chartData.labels.lastIndex

                            StepPeriod.WEEK ->
                                true

                            StepPeriod.MONTH ->
                                index % 2 == 0 ||
                                        index ==
                                        chartData.labels.lastIndex
                        }

                    return if (showLabel) {
                        chartData.labels[index]
                    } else {
                        ""
                    }
                }
            }

        val labelCount =
            when (selectedStepPeriod) {
                StepPeriod.DAY -> 5
                StepPeriod.WEEK -> 7
                StepPeriod.MONTH -> 8
            }

        stepsChart.xAxis.setLabelCount(
            labelCount,
            false
        )

        stepsChart.apply {
            data = barData
            axisLeft.axisMinimum = 0f
            fitScreen()
            notifyDataSetChanged()
            animateY(350)
            invalidate()
        }
    }

    /*
     * Aggiornamento dei riepiloghi sotto il grafico.
     */
    private fun updateStepSummary(
        chartData: StepChartData
    ) {
        val totalSteps =
            chartData.totalSteps

        val averageSteps = (
                totalSteps.toDouble() /
                        chartData.daysForAverage
                            .coerceAtLeast(1)
                ).roundToInt()

        val completionPercentage = (
                averageSteps.toDouble() /
                        dailyStepGoal.toDouble() *
                        100.0
                ).roundToInt()
            .coerceIn(0, 100)

        val distanceKm =
            totalSteps * stepLengthKm

        val calories =
            totalSteps * caloriesPerStep

        val integerFormatter =
            NumberFormat.getIntegerInstance(
                stepLocale
            )

        tvTotalSteps.text =
            integerFormatter.format(totalSteps)

        tvAverageSteps.text =
            integerFormatter.format(averageSteps)

        tvStepCompletion.text =
            "$completionPercentage%"

        tvStepDistance.text =
            String.format(
                stepLocale,
                "%.2f km",
                distanceKm
            )

        tvStepCalories.text =
            String.format(
                stepLocale,
                "%.1f kcal",
                calories
            )

        tvStepPeriodLabel.text =
            chartData.periodLabel
    }

    private fun calculateDaysForAverage(
        firstDay: LocalDate,
        lastDay: LocalDate
    ): Int {
        val today = LocalDate.now()

        val effectiveLastDay =
            if (lastDay.isAfter(today)) {
                today
            } else {
                lastDay
            }

        if (effectiveLastDay.isBefore(firstDay)) {
            return 1
        }

        return (
                ChronoUnit.DAYS.between(
                    firstDay,
                    effectiveLastDay
                ) + 1
                ).toInt()
            .coerceAtLeast(1)
    }

    private fun startOfWeek(
        date: LocalDate
    ): LocalDate {
        return date.with(
            TemporalAdjusters.previousOrSame(
                DayOfWeek.MONDAY
            )
        )
    }

    private fun isCurrentStepPeriod(): Boolean {
        val today = LocalDate.now()

        return when (selectedStepPeriod) {
            StepPeriod.DAY ->
                selectedStepDate == today

            StepPeriod.WEEK ->
                startOfWeek(selectedStepDate) ==
                        startOfWeek(today)

            StepPeriod.MONTH ->
                selectedStepDate.year ==
                        today.year &&
                        selectedStepDate.month ==
                        today.month
        }
    }

    /*
     * Impedisce di navigare oltre il giorno corrente.
     */
    private fun updateStepNavigation() {
        val canMoveForward =
            !isCurrentStepPeriod()

        btnNextStepPeriod.isEnabled =
            canMoveForward

        btnNextStepPeriod.alpha =
            if (canMoveForward) {
                1f
            } else {
                0.35f
            }
    }
}

class CustomMarkerView(
    context: Context,
    layoutResource: Int
) : MarkerView(
    context,
    layoutResource
) {

    private val tvMarkerValue: TextView =
        findViewById(R.id.tvMarkerValue)

    override fun refreshContent(
        entry: Entry?,
        highlight: Highlight?
    ) {
        if (entry != null) {
            tvMarkerValue.text =
                entry.y.toInt().toString()
        }

        super.refreshContent(
            entry,
            highlight
        )
    }

    override fun getOffset(): MPPointF {
        return MPPointF(
            (-(width / 2)).toFloat(),
            (-height).toFloat()
        )
    }
}