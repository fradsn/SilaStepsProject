package com.example.myapplication.services

import android.content.Context
import com.example.myapplication.db.SQLiteHelper
import com.example.myapplication.db.dao.BpmDao
import com.example.myapplication.db.dao.O2Dao
import com.example.myapplication.db.dao.PressureDao
import com.google.firebase.auth.FirebaseAuth

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

    // Identifica dinamicamente l'utente corrente di Firebase Auth per agganciare il DB corretto
    private val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: "default_user"
    private val dbHelper = SQLiteHelper(context, currentUid)

    // Passiamo l'helper specifico per utente ai rispettivi DAO
    // Nota: Se i tuoi DAO nel loro costruttore accettano il Context anziché lo SQLiteHelper,
    // assicurati che al loro interno istanzino lo helper usando il canale Firebase o adattali.
    private val bpmDao = BpmDao(context)
    private val pressureDao = PressureDao(context)
    private val o2Dao = O2Dao(context)

    fun salvaBpm(bpm: Int) = bpmDao.insert(bpm)
    fun salvaPressione(s: Int, d: Int) = pressureDao.insert(s, d)
    fun salvaO2(value: Int) = o2Dao.insert(value)

    fun getBpm() = bpmDao.getAll()
    fun getPressioni() = pressureDao.getAll()
    fun getO2() = o2Dao.getAll()

    fun deleteOlderThan(timestamp: Long) {
        bpmDao.deleteOlderThan(timestamp)
        pressureDao.deleteOlderThan(timestamp)
        o2Dao.deleteOlderThan(timestamp)
    }
}