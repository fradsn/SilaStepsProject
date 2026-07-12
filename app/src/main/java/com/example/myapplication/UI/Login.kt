package com.example.myapplication.UI

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.R
import com.example.myapplication.db.GestoreStatistiche
import com.example.myapplication.services.LocationService
import com.google.firebase.auth.FirebaseAuth
import java.util.Calendar

class Login : AppCompatActivity() {
    private val auth = FirebaseAuth.getInstance()
    private lateinit var gestoreStatistiche: GestoreStatistiche

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gestoreStatistiche = GestoreStatistiche.getInstance(this)

        enableEdgeToEdge()
        setContentView(R.layout.activity_login)

        val textUsername : EditText = findViewById(R.id.user)
        val textPassword : EditText = findViewById(R.id.pass)
        val textLogin : Button = findViewById(R.id.accessBt)
        val textResetPass : TextView = findViewById(R.id.resetPass)
        val textRegister : Button = findViewById(R.id.registerButton)

        textRegister.setOnClickListener {
            try {
                val intent = Intent(this, RegisterActivity::class.java)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Registration screen unavailable", Toast.LENGTH_SHORT).show()
            }
        }

        textLogin.setOnClickListener {
            val username = textUsername.text.toString().trim()
            val password = textPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()){
                Toast.makeText(this, "Fields cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            auth.signInWithEmailAndPassword(username, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        Log.d("Login", "Authentication successful")

                        // FORZA IL RE-INDIRIZZAMENTO DEL DATABASE LOCALE SUL NUOVO UTENTE LOGGATO
                        GestoreStatistiche.resetInstance()

                        pulisciDB()

                        // GPS tracking
                        startGPS()

                        val intentLogin = Intent(this, UserProfileActivity::class.java)
                        startActivity(intentLogin)
                        finish()
                    } else {
                        Log.w("Login", "Authentication failed", task.exception)
                        Toast.makeText(
                            baseContext,
                            "Login failed: Invalid credentials",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
        }

        textResetPass.setOnClickListener{
            try {
                val intentReset = Intent(this, ResetActivity::class.java)
                startActivity(intentReset)
            } catch (e: Exception) {
                Toast.makeText(this, "Reset screen unavailable", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (auth.currentUser != null){
            // SE L'UTENTE È GIÀ LOGGATO IN CASH, PREPARIAMO IL SUO DATABASE DEDICATO PRIMA DI ENTRARE
            GestoreStatistiche.resetInstance()

            pulisciDB()

            // GPS tracking
            startGPS()

            val intentLogin = Intent(this, UserProfileActivity::class.java)
            startActivity(intentLogin)
            finish()
        }
    }

    private fun pulisciDB() {
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // Elimina i dati più vecchi della mezzanotte
        gestoreStatistiche.deleteOlderThan(midnight)
    }

    private fun controllaPermessiGPS(): Boolean {
        if (
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                1001
            )
        }

        var response = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
            ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                1002
            )

            response = response && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

        return response
    }

    private fun startGPS() {
        if (controllaPermessiGPS() && checkGpsOn()) {
            val intent = Intent(this, LocationService::class.java)
            ContextCompat.startForegroundService(this, intent)
        }
    }

    fun checkGpsOn(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

        val gpsOn = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)

        if (gpsOn) {
            Log.d("GPS", "GPS acceso")
            return true
        } else {
            Log.d("GPS", "GPS spento")
            return false
        }
    }


}