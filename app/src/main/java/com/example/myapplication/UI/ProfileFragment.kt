package com.example.myapplication.UI

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.BT.BleScannerManager
import com.example.myapplication.BT.DeviceAdapter
import com.example.myapplication.BT.ring.Decoder
import com.example.myapplication.BT.ring.SmartRingManager
import com.example.myapplication.Motion.session.MotionSessionManager
import com.example.myapplication.Motion.session.MotionUiState
import com.example.myapplication.R
import com.example.myapplication.UI.Login
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth

class ProfileFragment : Fragment(), SmartRingManager.SmartRingListener, MotionSessionManager.Observer {

    private var ringManager: SmartRingManager? = null

    private var tvBatteryLevelInSheet: TextView? = null
    private var deviceAdapter: DeviceAdapter? = null

    // Launcher asincrono per la richiesta dei permessi nativi
    private val requestBlePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val scanGranted = permissions[Manifest.permission.BLUETOOTH_SCAN] ?: true
        val connectGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: true
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: true

        if (scanGranted && connectGranted && locationGranted) {
            showDevicePickerSheet()
        } else {
            Toast.makeText(context, "È necessario concedere i permessi per cercare i dispositivi", Toast.LENGTH_LONG).show()
        }
    }

    // =====================================================================================
    // GESTIONE SMART RING LISTENER CALLBACKS
    // =====================================================================================
    override fun onConnected() {
        activity?.runOnUiThread {
            Toast.makeText(context, "Smart Ring Connesso!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDisconnected() {
        activity?.runOnUiThread {
            Toast.makeText(context, "Smart Ring Disconnesso", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDataReceived(result: Decoder.DecodedResult) {
        if (result.type == "BATTERY") {
            updateBatteryUI(result.battery, result.chargingStatus)
        }
    }

    override fun onError(msg: String) {
        activity?.runOnUiThread { Log.e("SMART_RING", "Errore: $msg") }
    }

    private fun updateBatteryUI(percent: Int, status: Int) {
        activity?.runOnUiThread {
            if (tvBatteryLevelInSheet != null) {
                val chargingIcon = if (status == 0x02) "⚡ " else ""
                tvBatteryLevelInSheet?.text = "Batteria: $chargingIcon$percent%"
            }
        }
    }

    // =====================================================================================
    // GESTIONE MACHINE LEARNING / MOTION OBSERVER CALLBACKS
    // =====================================================================================
    override fun onMotionStateChanged(state: MotionUiState) {
        if (!isAdded) return

        val error = state.lastError
        if (!error.isNullOrBlank()) {
            activity?.runOnUiThread {
                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Inizializzazione del core di Machine Learning dell'applicazione
        MotionSessionManager.initialize(requireContext())
        MotionSessionManager.addObserver(this)

        view.findViewById<MaterialCardView>(R.id.menuUserInfo)?.setOnClickListener { showUserInfoBottomSheet() }

        // Controllo dinamico a Runtime dei permessi pericolosi prima di avviare la ricerca
        view.findViewById<MaterialCardView>(R.id.menuFindDevices)?.setOnClickListener {
            val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            } else {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            }

            val allPermissionsGranted = requiredPermissions.all { permission ->
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    permission
                ) == PackageManager.PERMISSION_GRANTED
            }

            if (allPermissionsGranted) {
                showDevicePickerSheet()
            } else {
                requestBlePermissionsLauncher.launch(requiredPermissions)
            }
        }

        view.findViewById<MaterialCardView>(R.id.menuConnectedDevices)?.setOnClickListener {
            val ringConnected = ringManager?.isConnected() == true || SmartRingManager.getActiveInstance()?.isConnected() == true
            val shimmerConnected = MotionSessionManager.isShimmerConnected()

            if (ringConnected || shimmerConnected) {
                SmartRingManager.getActiveInstance()?.requestBatteryLevel()
                showConnectedDeviceBottomSheet()
            } else {
                Toast.makeText(context, "Nessun dispositivo connesso", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<ImageButton>(R.id.logout_button)?.setOnClickListener {
            FirebaseAuth.getInstance().signOut()

            // Smantellamento dei Singleton hardware e rimozione dei listener ML
            SmartRingManager.getActiveInstance()?.disconnect()
            MotionSessionManager.disconnectShimmer()
            MotionSessionManager.removeObserver(this)

            startActivity(Intent(requireActivity(), Login::class.java))
            requireActivity().finish()
        }
    }

    private fun showConnectedDeviceBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.layout_connected_device_sheet, null)

        val cardRing = sheetView.findViewById<MaterialCardView>(R.id.cardSmartRing)
        val tvRingName = sheetView.findViewById<TextView>(R.id.tvRingName)
        val tvRingAddress = sheetView.findViewById<TextView>(R.id.tvRingAddress)
        val btnDisconnectRing = sheetView.findViewById<Button>(R.id.btnDisconnectRing)
        tvBatteryLevelInSheet = sheetView.findViewById<TextView>(R.id.tvBatteryLevel)

        val cardShimmer = sheetView.findViewById<MaterialCardView>(R.id.cardShimmer)
        val tvShimmerName = sheetView.findViewById<TextView>(R.id.tvShimmerName)
        val tvShimmerAddress = sheetView.findViewById<TextView>(R.id.tvShimmerAddress)
        val btnDisconnectShimmer = sheetView.findViewById<Button>(R.id.btnDisconnectShimmer)

        val activeRing = SmartRingManager.getActiveInstance()

        // Logica Anello Unificato
        if (activeRing?.isConnected() == true) {
            cardRing.visibility = View.VISIBLE
            tvRingName.text = "Smart Ring"
            tvRingAddress.text = activeRing.getAddress()
            btnDisconnectRing.setOnClickListener {
                activeRing.disconnect()
                cardRing.visibility = View.GONE
                if (cardShimmer.visibility == View.GONE) dialog.dismiss()
            }
        } else {
            cardRing.visibility = View.GONE
        }

        // Logica Shimmer delegata a MotionSessionManager (Machine Learning branch)
        if (MotionSessionManager.isShimmerConnected()) {
            cardShimmer.visibility = View.VISIBLE
            tvShimmerName.text = "Shimmer3"
            tvShimmerAddress.text = MotionSessionManager.getShimmerAddress() ?: "Connesso"
            btnDisconnectShimmer.setOnClickListener {
                MotionSessionManager.disconnectShimmer()
                cardShimmer.visibility = View.GONE
                if (cardRing.visibility == View.GONE) dialog.dismiss()
            }
        } else {
            cardShimmer.visibility = View.GONE
        }

        dialog.setContentView(sheetView)
        dialog.show()
        dialog.setOnDismissListener { tvBatteryLevelInSheet = null }
    }

    // =====================================================================================
    // COORDINA IL PICKER SHEET TRAMITE BLE_SCANNER_MANAGER
    // =====================================================================================
    @SuppressLint("MissingPermission")
    private fun showDevicePickerSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.layout_find_devices_sheet, null)

        val rv = sheetView.findViewById<RecyclerView>(R.id.rvDevices)
        val btnScan = sheetView.findViewById<Button>(R.id.btnScan)
        val scanProgress = sheetView.findViewById<ProgressBar>(R.id.scanProgress)

        deviceAdapter = DeviceAdapter { device, isShimmer ->
            BleScannerManager.stopScan()

            if (isShimmer) {
                // Nel branch ML passiamo l'indirizzo direttamente a MotionSessionManager
                MotionSessionManager.connectToShimmer(requireContext(), device.address)
                Toast.makeText(requireContext(), "Connessione a Shimmer in corso...", Toast.LENGTH_SHORT).show()
            } else {
                ringManager = SmartRingManager.getInstance(
                    requireContext(),
                    device.address,
                    this
                )
                ringManager?.connect(requireContext())
                Toast.makeText(requireContext(), "Connessione Smart Ring in corso...", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = deviceAdapter

        BleScannerManager.init(deviceAdapter!!, object : BleScannerManager.ScanStateListener {
            override fun onScanStarted() {
                btnScan.text = "FERMA RICERCA"
                scanProgress.visibility = View.VISIBLE
            }

            override fun onScanStopped() {
                btnScan.text = "AVVIA RICERCA"
                scanProgress.visibility = View.GONE
            }
        })

        BleScannerManager.startScan(requireContext())

        btnScan.setOnClickListener {
            if (BleScannerManager.isScanning()) {
                BleScannerManager.stopScan()
            } else {
                BleScannerManager.startScan(requireContext())
            }
        }

        dialog.setContentView(sheetView)
        dialog.show()

        dialog.setOnDismissListener {
            BleScannerManager.stopScan()
        }
    }

    private fun showUserInfoBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.dialog_user_info, null)

        val spinnerGender = sheetView.findViewById<Spinner>(R.id.spinnerGender)
        val editName = sheetView.findViewById<EditText>(R.id.editName)
        val editSurname = sheetView.findViewById<EditText>(R.id.editSurname)
        val editHeight = sheetView.findViewById<EditText>(R.id.editHeight)
        val editWeight = sheetView.findViewById<EditText>(R.id.editWeight)
        val editAge = sheetView.findViewById<EditText>(R.id.editAge)
        val btnSaveInfo = sheetView.findViewById<Button>(R.id.btnSaveInfo)

        val genderOptions = arrayOf("Maschio", "Femmina", "Altro")
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, genderOptions)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerGender?.adapter = spinnerAdapter

        val auth = FirebaseAuth.getInstance()
        val userId = auth.currentUser?.uid ?: "user"
        val sharedPref = requireContext().getSharedPreferences("UserData_$userId", Context.MODE_PRIVATE)

        val savedGenderPos = sharedPref.getInt("gender_pos", 0)
        val savedAge = sharedPref.getString("age", "")
        val savedHeight = sharedPref.getString("height", "")
        val savedWeight = sharedPref.getString("weight", "")
        val savedName = sharedPref.getString("name", "")
        val savedSurname = sharedPref.getString("surname", "")

        spinnerGender?.setSelection(savedGenderPos)
        editAge?.setText(savedAge)
        editHeight?.setText(savedHeight)
        editWeight?.setText(savedWeight)
        editName?.setText(savedName)
        editSurname?.setText(savedSurname)

        btnSaveInfo?.setOnClickListener {
            val ageStr = editAge?.text.toString().trim()
            val heightStr = editHeight?.text.toString().trim()
            val weightStr = editWeight?.text.toString().trim()
            val nameStr = editName?.text.toString().trim()
            val surnameStr = editSurname?.text.toString().trim()
            val genderPos = spinnerGender?.selectedItemPosition ?: 0

            if (ageStr.isNotEmpty() && heightStr.isNotEmpty() && weightStr.isNotEmpty()) {
                sharedPref.edit().apply {
                    putInt("gender_pos", genderPos)
                    putString("age", ageStr)
                    putString("height", heightStr)
                    putString("weight", weightStr)
                    putString("name", nameStr)
                    putString("surname", surnameStr)
                    apply()
                }

                SmartRingManager.getActiveInstance()?.syncUserInfo()

                Toast.makeText(requireContext(), "Informazioni salvate localmente!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(requireContext(), "Compila obbligatoriamente Età, Altezza e Peso", Toast.LENGTH_LONG).show()
            }
        }

        dialog.setContentView(sheetView)
        dialog.show()
    }

    override fun onResume() {
        super.onResume()

        val activeRing = SmartRingManager.getActiveInstance()
        if (activeRing != null && activeRing.isConnected()) {
            ringManager = activeRing
            ringManager?.updateListener(this)
        }
    }

    override fun onPause() {
        super.onPause()
        ringManager?.updateListener(object : SmartRingManager.SmartRingListener {
            override fun onConnected() {}
            override fun onDisconnected() {}
            override fun onDataReceived(result: Decoder.DecodedResult) {}
            override fun onError(msg: String) {}
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Rimozione dell'Observer ad ogni distruzione del frammento per evitare perdite di memoria
        MotionSessionManager.removeObserver(this)
    }
}