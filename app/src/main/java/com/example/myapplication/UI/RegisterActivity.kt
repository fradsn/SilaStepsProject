package com.example.myapplication.UI

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import com.google.firebase.auth.FirebaseAuth

class RegisterActivity : AppCompatActivity() {

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        val email: EditText = findViewById(R.id.mailUser)
        val password: EditText = findViewById(R.id.passUser)
        val confPass: EditText = findViewById(R.id.passUserConf)
        val btnRegister: Button = findViewById(R.id.Breg)
        val auth = FirebaseAuth.getInstance()

        btnRegister.setOnClickListener {
            val mail = email.text.toString().trim()
            val pass = password.text.toString().trim()
            val confP = confPass.text.toString().trim()

            if (mail.isEmpty() || pass.isEmpty() || confP.isEmpty()) {
                Toast.makeText(this, "Fields cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pass != confP) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Registrazione pulita isolata solo su Firebase Authentication
            auth.createUserWithEmailAndPassword(mail, pass)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        Log.d("RegisterActivity", "Firebase Auth successful")

                        // Invia l'email di verifica in background
                        auth.currentUser?.sendEmailVerification()

                        Toast.makeText(this, "Registration successful!", Toast.LENGTH_SHORT).show()

                        // SWITCH IMMEDIATO ALLA VIEW DI LOGIN
                        val intent = Intent(this, Login::class.java)
                        startActivity(intent)
                        finish() // Chiude RegisterActivity in modo pulito
                    } else {
                        val error = task.exception?.message ?: "Registration failed"
                        Log.e("RegisterActivity", "Auth failure: $error")
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    }
                }
        }
    }
}