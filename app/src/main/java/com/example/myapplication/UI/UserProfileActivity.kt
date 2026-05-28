package com.example.myapplication.UI

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.myapplication.R
import com.google.android.material.bottomnavigation.BottomNavigationView

class UserProfileActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_user_profile)

        val bottomView: BottomNavigationView = findViewById(R.id.bottomNavigationView)

        // fragment iniziale: Activity
        cambiaSchermata(CurrentActivityFragment(), "current")

        bottomView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.homeA -> {
                    cambiaSchermata(CurrentActivityFragment(), "current")
                }
                R.id.scheda -> {
                    // TAG "charts" per poterlo trovare da CurrentActivityFragment
                    cambiaSchermata(ChartsFragment(), "charts")
                }
                R.id.profilo -> {
                    cambiaSchermata(ProfileFragment(), "profile")
                }
            }
            true
        }
    }

    private fun cambiaSchermata(fragment: Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.frameLayout, fragment, tag)
            .commit()
    }
}