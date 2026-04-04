package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth

class ProfileFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            Log.d("Firebase", "Email non trovata")
        } else {
            val benv: TextView = view.findViewById(R.id.benvenuto)
            val userId = auth.currentUser?.uid
            benv.text = "Benvenuto, ${auth.currentUser?.email}"

        }
        val logoutBtn: FloatingActionButton = view.findViewById(R.id.logout)
        logoutBtn.setOnClickListener {
            auth.signOut()
            val tornaAlLogin = Intent(requireContext(), Login::class.java)
            startActivity(tornaAlLogin)
            requireActivity().finish()
        }
}
}