package com.example.myapplication.UI

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.myapplication.R
import com.example.myapplication.db.GestoreStatistiche
import com.example.myapplication.steps.StepRecordingManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import android.util.Log
class UserProfileActivity : AppCompatActivity() {

    private lateinit var stepRecordingManager: StepRecordingManager
    private lateinit var gestoreStatistiche: GestoreStatistiche

    private var stepReadInProgress = false

    private val activityRecognitionPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                subscribeReadAndSaveSteps()
            } else {
                Toast.makeText(
                    this,
                    "Il permesso Attività fisica è necessario per contare i passi",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_user_profile)

        stepRecordingManager =
            StepRecordingManager(applicationContext)

        gestoreStatistiche =
            GestoreStatistiche.getInstance(applicationContext)

        configureStepRecording()

        val bottomView: BottomNavigationView =
            findViewById(R.id.bottomNavigationView)

        cambiaSchermata(CurrentActivityFragment(), "current")

        bottomView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.homeA -> {
                    cambiaSchermata(CurrentActivityFragment(), "current")
                }

                R.id.scheda -> {
                    cambiaSchermata(ChartsFragment(), "charts")
                }

                R.id.profilo -> {
                    cambiaSchermata(ProfileFragment(), "profile")
                }
            }

            true
        }
    }

    override fun onResume() {
        super.onResume()

        if (
            ::stepRecordingManager.isInitialized &&
            hasStepCounterSensor() &&
            hasActivityRecognitionPermission()
        ) {
            subscribeReadAndSaveSteps()
        }
    }

    private fun configureStepRecording() {
        if (!hasStepCounterSensor()) {
            Toast.makeText(
                this,
                "Questo telefono non dispone del sensore contapassi",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (hasActivityRecognitionPermission()) {
            subscribeReadAndSaveSteps()
        } else {
            activityRecognitionPermissionLauncher.launch(
                Manifest.permission.ACTIVITY_RECOGNITION
            )
        }
    }

    private fun subscribeReadAndSaveSteps() {
        if (stepReadInProgress) return

        stepReadInProgress = true

        stepRecordingManager.subscribe { subscriptionResult ->
            subscriptionResult.onSuccess {
                stepRecordingManager.readRecentStepHistory(
                    days = 10
                ) { historyResult ->
                    stepReadInProgress = false

                    historyResult.onSuccess { snapshot ->
                        try {
                            // Salvataggio dedicato al grafico.
                            gestoreStatistiche.salvaStoricoPassi(
                                dailyEntries = snapshot.dailyEntries,
                                hourlyEntries = snapshot.hourlyEntries
                            )

                            val totalSteps = snapshot.todaySteps

                            Log.d(
                                "STEP_RECORDING",
                                "Passi letti oggi: $totalSteps"
                            )

                            // Salvataggio esistente utilizzato anche da AWS.
                            val lastSavedToday =
                                gestoreStatistiche
                                    .getSteps()
                                    .lastOrNull()
                                    ?.tot

                            if (lastSavedToday != totalSteps) {
                                gestoreStatistiche.salvaPassi(totalSteps)

                                Log.d(
                                    "STEP_RECORDING",
                                    "Passi salvati nel database: $totalSteps"
                                )
                            } else {
                                Log.d(
                                    "STEP_RECORDING",
                                    "Valore già presente: nessun nuovo salvataggio"
                                )
                            }

                            Log.d(
                                "STEP_RECORDING",
                                "Storico aggiornato: " +
                                        "${snapshot.dailyEntries.size} giorni, " +
                                        "${snapshot.hourlyEntries.size} ore"
                            )
                        } catch (exception: Exception) {
                            Log.e(
                                "STEP_RECORDING",
                                "Errore durante il salvataggio dello storico",
                                exception
                            )

                            Toast.makeText(
                                this,
                                "Errore durante il salvataggio dei passi",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    historyResult.onFailure { exception ->
                        Log.e(
                            "STEP_RECORDING",
                            "Errore lettura storico passi",
                            exception
                        )

                        Toast.makeText(
                            this,
                            "Impossibile leggere i passi: ${exception.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            subscriptionResult.onFailure { exception ->
                stepReadInProgress = false

                Log.e(
                    "STEP_RECORDING",
                    "Errore attivazione contapassi",
                    exception
                )

                Toast.makeText(
                    this,
                    "Impossibile attivare il contapassi: ${exception.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasStepCounterSensor(): Boolean {
        val sensorManager =
            getSystemService(Context.SENSOR_SERVICE) as SensorManager

        return sensorManager
            .getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }

    private fun cambiaSchermata(fragment: Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.frameLayout, fragment, tag)
            .commit()
    }
}