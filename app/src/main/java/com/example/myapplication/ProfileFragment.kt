package com.example.myapplication.ui.profile

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.Login
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentProfileBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var auth: FirebaseAuth

    // Bluetooth
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    private lateinit var deviceAdapter: DeviceAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        auth = FirebaseAuth.getInstance()

        binding.profileEmail.text = "Benvenuto, ${auth.currentUser?.email ?: "Utente"}"

        // Bottone Logout
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
        // 1. User Info
        binding.menuUserInfo.setOnClickListener {
            showUserInfoDialog()
        }

        // 2. Trova Dispositivi
        binding.menuFindDevices.setOnClickListener {
            showFindDevicesDialog()
        }

        // 3. Dispositivi Connessi (Placeholder)
        binding.menuConnectedDevices.setOnClickListener {
            Toast.makeText(context, "Verifica connessione...", Toast.LENGTH_SHORT).show()
        }
    }

    // --- DIALOG USER INFO ---
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

        // Configurazione Spinner Sesso
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
            if (name.isEmpty()) {
                Toast.makeText(context, "Inserisci almeno il nome", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            with(sharedPref.edit()) {
                putString("name", name)
                putString("surname", etSurname.text.toString().trim())
                putString("height", etHeight.text.toString().trim())
                putString("weight", etWeight.text.toString().trim())
                putString("age", etAge.text.toString().trim())
                putInt("gender_pos", spinnerGender.selectedItemPosition)
                commit() // commit() salva fisicamente sul telefono
            }
            Toast.makeText(context, "Profilo aggiornato!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    // --- DIALOG TROVA DISPOSITIVI ---
    private fun showFindDevicesDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.layout_find_devices_sheet, null)

        val btnScan = view.findViewById<Button>(R.id.btnStartScan)
        val progress = view.findViewById<ProgressBar>(R.id.scanProgress)
        val rv = view.findViewById<RecyclerView>(R.id.rvDevices)

        // Configurazione RecyclerView e Adapter
        deviceAdapter = DeviceAdapter { device ->
            // Per ora mostriamo solo l'indirizzo cliccato
            Toast.makeText(context, "Dispositivo: ${device.address}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = deviceAdapter

        btnScan.setOnClickListener {
            if (bluetoothAdapter == null) {
                Toast.makeText(context, "Bluetooth non supportato", Toast.LENGTH_SHORT).show()
            } else if (!bluetoothAdapter!!.isEnabled) {
                Toast.makeText(context, "Attiva il Bluetooth prima di cercare", Toast.LENGTH_SHORT).show()
            } else {
                deviceAdapter.clear()
                progress.visibility = View.VISIBLE
                startBleScan(progress)
            }
        }

        dialog.setContentView(view)
        dialog.show()
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan(progressBar: ProgressBar) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                activity?.runOnUiThread {
                    deviceAdapter.addDevice(result.device)
                }
            }
        }

        // Avvio scansione
        scanner?.startScan(scanCallback)

        // Stop automatico dopo 10 secondi
        Handler(Looper.getMainLooper()).postDelayed({
            scanner?.stopScan(scanCallback)
            progressBar.visibility = View.GONE
            if (isAdded) Toast.makeText(context, "Scansione terminata", Toast.LENGTH_SHORT).show()
        }, 10000)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}