package com.example.myapplication.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.R
import com.example.myapplication.db.GestoreStatistiche
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

class LocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Gestore del ciclo di vita delle coroutine legato al Service
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var locationJob: Job? = null

    private lateinit var gestoreStatistiche: GestoreStatistiche

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        gestoreStatistiche = GestoreStatistiche.getInstance(this)

        // CONTROLLO DI SICUREZZA: Avvia la notifica in primo piano solo se ci sono i permessi
        if (haPermessiPosizione()) {
            startForegroundServiceNotification()
        } else {
            Log.e("LocationService", "Impossibile avviare il servizio in primo piano: permessi mancanti.")
            // Ferma immediatamente il servizio per evitare il crash irreversibile (SecurityException)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CONTROLLO DI SICUREZZA: Se i permessi mancano a runtime, non avviare i loop di tracciamento
        if (!haPermessiPosizione()) {
            Log.e("LocationService", "Richiesta interrotta in onStartCommand: permessi mancanti.")
            stopSelf()
            return START_NOT_STICKY
        }

        // Avvia il ciclo continuo se non è già attivo
        if (locationJob == null || locationJob?.isActive == false) {
            startPeriodicLocationUpdates(30 * 1000L) // 30 sec
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("LocationService", "Chiusura da Task Manager")

        // Ferma il servizio dopo lo swipe dell'utente
        stopSelf()
    }

    override fun onDestroy() {
        // Cancella le coroutine periodiche per evitare che continuino a girare
        serviceScope.cancel()
        super.onDestroy()
    }

    // Funzione per verificare se l'app possiede almeno uno dei permessi di localizzazione a runtime
    private fun haPermessiPosizione(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)

        return fineLocation == PackageManager.PERMISSION_GRANTED || coarseLocation == PackageManager.PERMISSION_GRANTED
    }

    private fun startForegroundServiceNotification() {
        val channelId = "location_channel"

        val channel = NotificationChannel(
            channelId,
            "Aggiornamento posizione",
            NotificationManager.IMPORTANCE_LOW
        )

        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("VitalActivity")
            .setContentText("Monitoraggio posizione attivo")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(1, notification)
    }

    private fun startPeriodicLocationUpdates(intervalMillis: Long) {
        locationJob = serviceScope.launch {
            while (isActive) {
                requestLocation()
                // Mette in pausa la coroutine senza bloccare il thread principale
                delay(intervalMillis)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocation() {
        // Un ulteriore controllo di sicurezza prima della richiesta asincrona
        if (!haPermessiPosizione()) return

        // 1) Primo tentativo: GPS
        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            null
        ).addOnSuccessListener { gpsLocation ->

            if (gpsLocation != null) {
                Log.d("Info posizione", "GPS OK → Lat: ${gpsLocation.latitude}, Lon: ${gpsLocation.longitude}")
                onPosizioneValida(gpsLocation)
                return@addOnSuccessListener
            }

            // 2) Fallback: Wi-Fi + celle
            Log.w("Info posizione", "GPS non disponibile. Avvio fallback...")

            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                null
            ).addOnSuccessListener { fallbackLocation ->

                if (fallbackLocation != null) {
                    Log.d("Info posizione", "Fallback OK → Lat: ${fallbackLocation.latitude}, Lon: ${fallbackLocation.longitude}")
                    onPosizioneValida(fallbackLocation)
                } else {
                    Log.e("Info posizione", "Nessuna posizione disponibile (GPS e fallback falliti)")
                }

            }.addOnFailureListener { exception ->
                Log.e("Info posizione", "Errore nel fallback", exception)
            }

        }.addOnFailureListener { exception ->
            Log.e("Info posizione", "Errore nel recupero GPS", exception)
        }
    }

    private fun onPosizioneValida(location: Location) {
        //ToDo: invio dati posizione

        gestoreStatistiche.salvaPosizione(location.latitude,location.longitude)
    }
}