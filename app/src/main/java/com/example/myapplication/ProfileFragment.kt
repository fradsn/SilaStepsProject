package com.example.myapplication.ui.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.example.myapplication.Login
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentProfileBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var auth: FirebaseAuth

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        auth = FirebaseAuth.getInstance()

        binding.profileEmail.text = "Benvenuto, ${auth.currentUser?.email ?: "Utente"}"

        binding.logoutButton.setOnClickListener {
            auth.signOut()
            val intent = Intent(requireContext(), Login::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            activity?.finish()
        }

        setupMenuHandlers()
        return binding.root
    }

    private fun setupMenuHandlers() {
        binding.menuUserInfo.setOnClickListener { showUserInfoDialog() }
        binding.menuFindDevices.setOnClickListener {
            Toast.makeText(context, "Ricerca Bluetooth...", Toast.LENGTH_SHORT).show()
        }
        binding.menuConnectedDevices.setOnClickListener {
            Toast.makeText(context, "Verifica connessione...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showUserInfoDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.layout_user_info_sheet, null)

        val btnSave = view.findViewById<Button>(R.id.btnSaveInfo)
        val etName = view.findViewById<EditText>(R.id.editName)
        val etSurname = view.findViewById<EditText>(R.id.editSurname)
        val etHeight = view.findViewById<EditText>(R.id.editHeight)
        val etWeight = view.findViewById<EditText>(R.id.editWeight)
        val etAge = view.findViewById<EditText>(R.id.editAge)
        val spinnerGender = view.findViewById<Spinner>(R.id.spinnerGender)

        // Configurazione Spinner Sesso via codice
        val genderOptions = arrayOf("Uomo", "Donna")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, genderOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerGender.adapter = adapter

        // Recupero dati locali
        val userId = auth.currentUser?.uid ?: "user"
        val sharedPref = requireActivity().getSharedPreferences("UserData_$userId", Context.MODE_PRIVATE)

        etName.setText(sharedPref.getString("name", ""))
        etSurname.setText(sharedPref.getString("surname", ""))
        etHeight.setText(sharedPref.getString("height", ""))
        etWeight.setText(sharedPref.getString("weight", ""))
        etAge.setText(sharedPref.getString("age", ""))
        spinnerGender.setSelection(sharedPref.getInt("gender_pos", 0))

        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            val surname = etSurname.text.toString().trim()

            if (name.isEmpty() || surname.isEmpty()) {
                Toast.makeText(context, "Nome e Cognome obbligatori", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            with(sharedPref.edit()) {
                putString("name", name)
                putString("surname", surname)
                putString("height", etHeight.text.toString().trim())
                putString("weight", etWeight.text.toString().trim())
                putString("age", etAge.text.toString().trim())
                putInt("gender_pos", spinnerGender.selectedItemPosition)
                commit() // commit() garantisce che il dato venga scritto subito sul disco del telefono
            }

            Toast.makeText(context, "Profilo aggiornato!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}