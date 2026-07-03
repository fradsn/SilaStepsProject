package com.example.myapplication.UI

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import com.example.myapplication.services.GestoreStatistiche
import com.google.firebase.auth.FirebaseAuth

class Login : AppCompatActivity() {
    private val auth = FirebaseAuth.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

            val intentLogin = Intent(this, UserProfileActivity::class.java)
            startActivity(intentLogin)
            finish()
        }
    }
}