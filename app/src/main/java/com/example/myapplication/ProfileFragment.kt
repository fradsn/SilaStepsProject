package com.example.myapplication.ui.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
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

        // Visualizza l'email dell'utente corrente nell'header
        binding.profileEmail.text = "Benvenuto, ${auth.currentUser?.email ?: "Utente"}"

        // Gestione LOGOUT: Torna alla schermata Login e pulisce la cronologia delle attività
        binding.logoutButton.setOnClickListener {
            auth.signOut()
            val intent = Intent(requireContext(), Login::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            activity?.finish()
        }

        // Configura i click per i box del menu
        setupMenuHandlers()

        return binding.root
    }

    private fun setupMenuHandlers() {
        // 1. USER INFO: Apre il BottomSheet con salvataggio locale
        binding.menuUserInfo.setOnClickListener {
            showUserInfoDialog()
        }

        // 2. TROVA DISPOSITIVI: Placeholder per la futura logica Bluetooth
        binding.menuFindDevices.setOnClickListener {
            Toast.makeText(context, "Ricerca Smart Ring in corso...", Toast.LENGTH_SHORT).show()
        }

        // 3. DISPOSITIVI CONNESSI: Placeholder per lo stato della connessione
        binding.menuConnectedDevices.setOnClickListener {
            Toast.makeText(context, "Verifica stato dispositivi...", Toast.LENGTH_SHORT).show()
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

        // --- RECUPERO DATI LOCALI (SharedPreferences) ---
        // Usiamo l'UID dell'utente per rendere il salvataggio unico per ogni account sul PC
        val userId = auth.currentUser?.uid ?: "default_user"
        val sharedPref = requireActivity().getSharedPreferences("UserData_$userId", Context.MODE_PRIVATE)

        etName.setText(sharedPref.getString("name", ""))
        etSurname.setText(sharedPref.getString("surname", ""))
        etHeight.setText(sharedPref.getString("height", ""))
        etWeight.setText(sharedPref.getString("weight", ""))
        etAge.setText(sharedPref.getString("age", ""))

        // --- LOGICA DI SALVATAGGIO ---
        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            val surname = etSurname.text.toString().trim()

            if (name.isEmpty() || surname.isEmpty()) {
                Toast.makeText(context, "Nome e Cognome sono obbligatori", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            with(sharedPref.edit()) {
                putString("name", name)
                putString("surname", surname)
                putString("height", etHeight.text.toString().trim())
                putString("weight", etWeight.text.toString().trim())
                putString("age", etAge.text.toString().trim())
                apply() // Salva su disco in modo asincrono
            }

            Toast.makeText(context, "Profilo aggiornato localmente!", Toast.LENGTH_SHORT).show()
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