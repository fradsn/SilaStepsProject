package com.example.myapplication.db

import android.content.Context
import com.example.myapplication.db.dao.BpmDao
import com.example.myapplication.db.dao.O2Dao
import com.example.myapplication.db.dao.PositionDao
import com.example.myapplication.db.dao.PredictionDao
import com.example.myapplication.db.dao.PressureDao
import com.example.myapplication.db.dao.StepDao
import com.example.myapplication.db.models.PredictionEntry


import com.example.myapplication.db.dao.StepHistoryDao
import com.example.myapplication.db.models.DailyStepEntry
import com.example.myapplication.db.models.HourlyStepEntry
class GestoreStatistiche private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: GestoreStatistiche? = null

        fun getInstance(context: Context): GestoreStatistiche {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GestoreStatistiche(context.applicationContext).also { INSTANCE = it }
            }
        }

        // METODO ESSENZIALE: Chiamalo nei blocqui di Logout, Login e Registrazione per rinfrescare i puntatori
        fun resetInstance() {
            synchronized(this) {
                INSTANCE = null
            }
        }
    }


    private val bpmDao = BpmDao(context)
    private val pressureDao = PressureDao(context)
    private val o2Dao = O2Dao(context)
    private val predictionDao = PredictionDao(context)
    private val positionDao = PositionDao(context)
    private val stepDao = StepDao(context)
    private val stepHistoryDao = StepHistoryDao(context)

    fun salvaBpm(bpm: Int) = bpmDao.insert(bpm)
    fun salvaPressione(s: Int, d: Int) = pressureDao.insert(s, d)
    fun salvaO2(value: Int) = o2Dao.insert(value)
    fun salvaPosizione(latitude: Double, longitude: Double) = positionDao.insert(latitude,longitude)

    fun salvaPredizione(activity: String, confidence: Int) {
        predictionDao.insert(activity, confidence)
    }

    fun salvaPassi(value: Int) = stepDao.insert(value)
    fun salvaStoricoPassi(
        dailyEntries: List<DailyStepEntry>,
        hourlyEntries: List<HourlyStepEntry>
    ) {
        stepHistoryDao.upsertHistory(
            dailyEntries = dailyEntries,
            hourlyEntries = hourlyEntries
        )
    }
    fun getBpm() = bpmDao.getAll()
    fun getPressioni() = pressureDao.getAll()
    fun getO2() = o2Dao.getAll()
    fun getPrediction(): List<PredictionEntry> = predictionDao.getAll()
    fun getSteps() = stepDao.getAll()
    fun getPassiGiornalieri(
        firstDay: String,
        lastDay: String
    ): List<DailyStepEntry> {
        return stepHistoryDao.getDailyBetween(
            firstDay = firstDay,
            lastDay = lastDay
        )
    }

    fun getPassiOrari(
        day: String
    ): List<HourlyStepEntry> {
        return stepHistoryDao.getHourlyForDay(day)
    }

    fun getPositions() = positionDao.getAll()

    fun getActivityCount() = predictionDao.getActivityCount()

    fun deleteOlderThan(timestamp: Long) {
        bpmDao.deleteOlderThan(timestamp)
        pressureDao.deleteOlderThan(timestamp)
        o2Dao.deleteOlderThan(timestamp)
        predictionDao.deleteOlderThan(timestamp)
        positionDao.deleteOlderThan(timestamp)
        stepDao.deleteOlderThan(timestamp)
    }
}