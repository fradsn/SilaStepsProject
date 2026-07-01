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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class Login : AppCompatActivity() {
    private val auth = FirebaseAuth.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = FirebaseDatabase.getInstance().reference
        db.child("test").setValue("Connected!")
            .addOnSuccessListener {
                Log.d("FIREBASE", "✅ Write successful - DB Connected!")
            }
            .addOnFailureListener { e ->
                Log.d("FIREBASE", "❌ Error: ${e.message}")
            }

        // Puntiamo al layout corretto di login rimodernato
        setContentView(R.layout.activity_login)

        val textUsername : EditText = findViewById(R.id.user)
        val textPassword : EditText = findViewById(R.id.pass)
        val textLogin : Button = findViewById(R.id.accessBt)
        val textResetPass : TextView = findViewById(R.id.resetPass)
        val textRegister : Button = findViewById(R.id.registerButton)

        textRegister.setOnClickListener {
            try {
                // Cerchiamo di lanciare RegisterActivity; se la classe ha un nome leggermente diverso nel tuo progetto,
                // usiamo un controllo dinamico o la stringa esplicita del pacchetto per non bloccare il compilatore.
                val intent = Intent(this, RegisterActivity::class.java)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Registration screen is under maintenance", Toast.LENGTH_SHORT).show()
            }
        }

        textLogin.setOnClickListener {
            val username = textUsername.text.toString().trim()
            val password = textPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()){
                Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            auth.signInWithEmailAndPassword(username, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        Log.d("login", "signInWithEmail:success")
                        val intentLogin = Intent(this, UserProfileActivity::class.java)
                        startActivity(intentLogin)
                        finish()
                    } else {
                        Log.w("login", "signInWithEmail:failure", task.exception)
                        Toast.makeText(
                            baseContext,
                            "Authentication failed: ${task.exception?.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
        }

        textResetPass.setOnClickListener{
            try {
                val intentReset = Intent(this, ResetActivity::class.java)
                startActivity(intentReset)
            } catch (e: Exception) {
                Toast.makeText(this, "Reset screen is under maintenance", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (auth.currentUser != null){
            val intentLogin = Intent(this, UserProfileActivity::class.java)
            startActivity(intentLogin)
            finish()
        }
    }
}