package com.example.myapplication.UI

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import com.google.firebase.auth.FirebaseAuth

class ResetActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val auth = FirebaseAuth.getInstance()
        setContentView(R.layout.reset_layout)

        val textEmail : EditText = findViewById(R.id.emailForReset)
        val btnReset : Button = findViewById(R.id.resetBtn)

        btnReset.setOnClickListener {
            val email = textEmail.text.toString().trim()
            if (email.isEmpty()) {
                Toast.makeText(this, "Please enter your email", Toast.LENGTH_SHORT).show()
            } else {
                resetPassword(auth, email)
            }
        }
    }

    private fun resetPassword(auth: FirebaseAuth, mail: String) {
        auth.sendPasswordResetEmail(mail).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Toast.makeText(this, "Reset link sent successfully", Toast.LENGTH_SHORT).show()
                val intentResetOk = Intent(this, Login::class.java)
                startActivity(intentResetOk)
                finish()
            } else {
                Log.w("ResetPassword", "Reset email failure", task.exception)
                Toast.makeText(
                    baseContext,
                    "Error: Invalid or unregistered email",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}