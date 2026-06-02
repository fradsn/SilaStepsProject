package com.example.myapplication.services

import android.icu.text.SimpleDateFormat
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.formatter.ValueFormatter
import java.util.Date
import java.util.Locale

class TimeAxisFormatter(private val timestamps: List<Long>) : ValueFormatter() {
    private val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun getAxisLabel(value: Float, axis: AxisBase?): String {
        val index = value.toInt()
        return if (index in timestamps.indices) {
            sdf.format(Date(timestamps[index]))
        } else ""
    }
}
