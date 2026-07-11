package com.example.myapplication.workers

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.myapplication.db.GestoreStatistiche
import java.util.Calendar

class DailyCleanupWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            val gestore = GestoreStatistiche.getInstance(applicationContext)

            // Calcola la mezzanotte di oggi
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val midnight = cal.timeInMillis

            // Elimina i dati più vecchi della mezzanotte
            gestore.deleteOlderThan(midnight)

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
