package com.example.myapplication.service

import android.content.Context
import com.example.myapplication.db.dao.BpmDao
import com.example.myapplication.db.dao.O2Dao
import com.example.myapplication.db.dao.PressureDao

class GestoreStatistiche private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: GestoreStatistiche? = null

        fun getInstance(context: Context): GestoreStatistiche {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GestoreStatistiche(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val bpmDao = BpmDao(context)
    private val pressureDao = PressureDao(context)
    private val o2Dao = O2Dao(context)

    fun salvaBpm(bpm: Int) = bpmDao.insert(bpm)
    fun salvaPressione(s: Int, d: Int) = pressureDao.insert(s, d)
    fun salvaO2(value: Int) = o2Dao.insert(value)

    fun getBpm() = bpmDao.getAll()
    fun getPressioni() = pressureDao.getAll()
    fun getO2() = o2Dao.getAll()
}