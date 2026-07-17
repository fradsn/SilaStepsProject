package com.example.myapplication.steps

import android.content.Context
import com.example.myapplication.db.GestoreStatistiche
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StepDatabaseUpdater(
    context: Context
) {

    private val recordingManager =
        StepRecordingManager(context.applicationContext)

    private val statistiche =
        GestoreStatistiche.getInstance(
            context.applicationContext
        )

    private val updateMutex = Mutex()

    /*
     * Attiva la registrazione continua dei passi.
     * Viene chiamata una volta da AwsSyncService.
     */
    suspend fun initialize() {
        recordingManager.ensureSubscribed()
    }

    /*
     * Legge i passi e aggiorna il database
     * prima di ogni sincronizzazione cloud.
     */
    suspend fun updateToday() {
        updateMutex.withLock {
            val snapshot =
                recordingManager.readTodayStepHistory()

            // Aggiorna lo storico giornaliero e orario.
            statistiche.salvaStoricoPassi(
                dailyEntries = snapshot.dailyEntries,
                hourlyEntries = snapshot.hourlyEntries
            )

            /*
             * Inserisce sempre un nuovo record con un nuovo timestamp,
             * anche quando il numero dei passi non è cambiato.
             * In questo modo AWS trova dati nuovi ogni 5 minuti.
             */
            statistiche.salvaPassi(
                snapshot.todaySteps
            )
        }
    }
}