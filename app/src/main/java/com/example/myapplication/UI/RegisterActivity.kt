package com.example.myapplication.UI

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class RegisterActivity : AppCompatActivity() {

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        val email: EditText = findViewById(R.id.mailUser)
        val password: EditText = findViewById(R.id.passUser)
        val confPass: EditText = findViewById(R.id.passUserConf)
        val radioG: RadioGroup = findViewById(R.id.radioG)
        radioG.clearCheck()
        val btnRegister: Button = findViewById(R.id.Breg)
        val auth = FirebaseAuth.getInstance()

        btnRegister.setOnClickListener {
            val mail = email.text.toString().trim()
            val pass = password.text.toString().trim()
            val confP = confPass.text.toString().trim()
            val genderId = radioG.checkedRadioButtonId

            if (genderId == -1) {
                Toast.makeText(this, "Please select gender", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val gender = findViewById<RadioButton>(genderId).text.toString()
            Log.d("Register", "Selected gender: $gender")

            if (mail.isEmpty()) {
                Toast.makeText(this, "Please enter email", Toast.LENGTH_SHORT).show()
            } else if (pass.isEmpty()) {
                Toast.makeText(this, "Please enter password", Toast.LENGTH_SHORT).show()
            } else if (pass != confP) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
            } else {
                registerUser(mail, pass, gender, auth) { isSuccess, errorMessage ->
                    if (isSuccess) {
                        Toast.makeText(this, "Registration successful", Toast.LENGTH_SHORT).show()
                        // Redirect clean to Login screen instead of self-looping
                        val intent = Intent(this, Login::class.java)
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(this, errorMessage ?: "Registration failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun registerUser(
        email: String,
        pass: String,
        gender: String,
        auth: FirebaseAuth,
        callback: (Boolean, String?) -> Unit
    ) {
        auth.createUserWithEmailAndPassword(email, pass)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val userId = auth.currentUser?.uid
                    val database = FirebaseDatabase.getInstance("https://gymapp-48c7e-default-rtdb.europe-west1.firebasedatabase.app/")
                    val userRef = database.getReference("users").child(userId ?: "unknown")

                    auth.currentUser?.sendEmailVerification()

                    val userData = mapOf(
                        "email" to email,
                        "gender" to gender,
                        "ingressi" to 0
                    )

                    userRef.setValue(userData)
                        .addOnCompleteListener { dbTask ->
                            if (dbTask.isSuccessful) {
                                callback(true, null)
                            } else {
                                callback(false, "Database error save failed")
                            }
                        }
                } else {
                    val error = task.exception?.message ?: "Account creation failed"
                    callback(false, error)
                }
            }
    }
}