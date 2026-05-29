package com.example.myapplication.UI

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.BT.ring.BLE
import com.example.myapplication.BT.ring.Decoder
import com.example.myapplication.BT.ring.DeviceAdapter
import com.example.myapplication.BT.ring.SmartRingBleManager
import com.example.myapplication.Motion.session.MotionSessionManager
import com.example.myapplication.Motion.session.MotionUiState
import com.example.myapplication.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth

class ProfileFragment : Fragment(), MotionSessionManager.Observer {

    private var bleService: BLE? = null
    private var isBound = false
    private lateinit var bleManager: SmartRingBleManager

    private var tvBatteryLevelInSheet: TextView? = null

    private val requestBlePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            @SuppressLint("MissingPermission")
            showDevicePickerSheet()
        } else {
            Toast.makeText(
                requireContext(),
                "Permessi Bluetooth necessari per la scansione",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    private fun hasBlePermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        return perms.all {
            requireContext().checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun requestBlePermissionsIfNeeded() {
        if (hasBlePermissions()) {
            showDevicePickerSheet()
            return
        }

        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        requestBlePermissions.launch(perms)
    }
    private val bleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "BLE_DATA_RX") {
                val raw = intent.getStringExtra("data") ?: return
                if (raw.startsWith("RX: ")) {
                    val hex = raw.removePrefix("RX: ")
                    val decoded = Decoder.decode(hex)
                    if (decoded?.type == "BATTERY") {
                        updateBatteryUI(decoded.battery, decoded.chargingStatus)
                    }
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bleService = (binder as? BLE.LocalBinder)?.getService()
            bleService?.initialize()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            isBound = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }
    @RequiresPermission(
        Manifest.permission.BLUETOOTH_SCAN
    )
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        MotionSessionManager.initialize(requireContext())
        MotionSessionManager.addObserver(this)
        val intent = Intent(requireActivity(), BLE::class.java)
        requireActivity().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        view.findViewById<View>(R.id.menuUserInfo)?.setOnClickListener {
            showUserInfoBottomSheet()
        }

        view.findViewById<View>(R.id.menuFindDevices)?.setOnClickListener  {
            requestBlePermissionsIfNeeded()
        }

        view.findViewById<View>(R.id.menuConnectedDevices)?.setOnClickListener {
            val ringConnected = bleService?.isDeviceConnected() == true
            val shimmerConnected = MotionSessionManager.isShimmerConnected()

            if (ringConnected || shimmerConnected) {
                if (ringConnected) bleService?.requestBatteryLevel()
                showConnectedDeviceBottomSheet()
            } else {
                Toast.makeText(context, "Nessun dispositivo connesso", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<View>(R.id.logout_button)?.setOnClickListener {
            FirebaseAuth.getInstance().signOut()

            if (isBound) {
                requireActivity().unbindService(serviceConnection)
                isBound = false
            }

            MotionSessionManager.removeObserver(this)

            startActivity(Intent(requireActivity(), Login::class.java))
            requireActivity().finish()
        }
    }

    private fun showConnectedDeviceBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.layout_connected_device_sheet, null)

        val cardRing = sheetView.findViewById<View>(R.id.cardSmartRing)
        val tvRingAddress = sheetView.findViewById<TextView>(R.id.tvRingAddress)
        val btnDisconnectRing = sheetView.findViewById<Button>(R.id.btnDisconnectRing)
        tvBatteryLevelInSheet = sheetView.findViewById(R.id.tvBatteryLevel)

        val cardShimmer = sheetView.findViewById<View>(R.id.cardShimmer)
        val tvShimmerAddress = sheetView.findViewById<TextView>(R.id.tvShimmerAddress)
        val btnDisconnectShimmer = sheetView.findViewById<Button>(R.id.btnDisconnectShimmer)

        if (bleService?.isDeviceConnected() == true) {
            cardRing.visibility = View.VISIBLE
            tvRingAddress.text = "Connesso (BLE)"
            btnDisconnectRing.setOnClickListener {
                bleService?.disconnectDevice()
                cardRing.visibility = View.GONE
                if (cardShimmer.visibility == View.GONE) dialog.dismiss()
            }
        } else {
            cardRing.visibility = View.GONE
        }

        if (MotionSessionManager.isShimmerConnected()) {
            cardShimmer.visibility = View.VISIBLE
            tvShimmerAddress.text =
                MotionSessionManager.getShimmerAddress() ?: "Connesso (BT Classic)"

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

    private fun updateBatteryUI(percent: Int, status: Int) {
        activity?.runOnUiThread {
            val chargingIcon = if (status == 0x02) "⚡ " else ""
            tvBatteryLevelInSheet?.text = "Batteria: $chargingIcon$percent%"
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun showDevicePickerSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.layout_find_devices_sheet, null)

        val rv = sheetView.findViewById<RecyclerView>(R.id.rvDevices)
        val btnScan = sheetView.findViewById<Button>(R.id.btnScan)
        val scanProgress = sheetView.findViewById<ProgressBar>(R.id.scanProgress)

        val adapter = DeviceAdapter { device, isShimmer ->
            if (isShimmer) {
                MotionSessionManager.connectToShimmer(requireContext(), device.address)
                Toast.makeText(
                    requireContext(),
                    "Connessione a Shimmer in corso...",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                if (isBound) {
                    bleService?.connect(device.address)
                    Toast.makeText(
                        requireContext(),
                        "Connessione Smart Ring in corso...",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            bleManager.stopScan()
            scanProgress.visibility = View.GONE
            dialog.dismiss()
        }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        bleManager = SmartRingBleManager(requireContext(), adapter)

        btnScan.setOnClickListener {
            adapter.clear()
            scanProgress.visibility = View.VISIBLE
            bleManager.startScan()

            rv.postDelayed({
                scanProgress.visibility = View.GONE
            }, 10000L)
        }

        bleManager.startScan()
        scanProgress.visibility = View.VISIBLE

        rv.postDelayed({
            scanProgress.visibility = View.GONE
        }, 10000L)

        dialog.setOnDismissListener {
            bleManager.stopScan()
            scanProgress.visibility = View.GONE
        }

        dialog.setContentView(sheetView)
        dialog.show()
    }

    private fun showUserInfoBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())

        // 1. Gonfiamo CORRETTAMENTE il file di layout XML (dialog_user_info.xml)
        val sheetView = layoutInflater.inflate(R.layout.dialog_user_info, null)

        // 2. Colleghiamo i componenti usando la vista del dialogo (sheetView) e gli ID esatti del tuo XML
        val spinnerGender = sheetView.findViewById<Spinner>(R.id.spinnerGender)
        val editName = sheetView.findViewById<EditText>(R.id.editName)
        val editSurname = sheetView.findViewById<EditText>(R.id.editSurname)
        val editHeight = sheetView.findViewById<EditText>(R.id.editHeight)
        val editWeight = sheetView.findViewById<EditText>(R.id.editWeight)
        val editAge = sheetView.findViewById<EditText>(R.id.editAge)
        val btnSaveInfo = sheetView.findViewById<Button>(R.id.btnSaveInfo)

        // 3. Popoliamo le opzioni dello Spinner per la scelta del sesso
        val genderOptions = arrayOf("Maschio", "Femmina", "Altro")
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, genderOptions)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerGender?.adapter = spinnerAdapter

        // 4. Otteniamo l'UID dell'utente corrente da Firebase Auth per isolare i dati locali
        val auth = FirebaseAuth.getInstance()
        val userId = auth.currentUser?.uid ?: "user"

        // 5. Apriamo il file SharedPreferences locale con la stessa identica stringa usata da SmartRingProtocol
        val sharedPref = requireContext().getSharedPreferences("UserData_$userId", Context.MODE_PRIVATE)

        // 6. Pre-carichiamo i dati precedentemente salvati (se esistono) per mostrarli nei campi
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

        // 7. Logica di salvataggio al clic sul pulsante "Salva Informazioni"
        btnSaveInfo?.setOnClickListener {
            val ageStr = editAge?.text.toString().trim()
            val heightStr = editHeight?.text.toString().trim()
            val weightStr = editWeight?.text.toString().trim()
            val nameStr = editName?.text.toString().trim()
            val surnameStr = editSurname?.text.toString().trim()
            val genderPos = spinnerGender?.selectedItemPosition ?: 0

            // Validazione dei parametri hardware obbligatori per l'anello
            if (ageStr.isNotEmpty() && heightStr.isNotEmpty() && weightStr.isNotEmpty()) {

                // Scrittura persistente nel file XML locale del dispositivo
                sharedPref.edit().apply {
                    putInt("gender_pos", genderPos)
                    putString("age", ageStr)
                    putString("height", heightStr)
                    putString("weight", weightStr)

                    // Salviamo opzionalmente anche nome e cognome richiesti dal tuo layout
                    putString("name", nameStr)
                    putString("surname", surnameStr)

                    apply() // Applica le modifiche in background
                }

                Toast.makeText(requireContext(), "Informazioni salvate localmente!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(requireContext(), "Compila obbligatoriamente Età, Altezza e Peso", Toast.LENGTH_LONG).show()
            }
        }

        // 8. Assegniamo la vista corretta al foglio di dialogo e lo mostriamo
        dialog.setContentView(sheetView)
        dialog.show()
    }

    override fun onMotionStateChanged(state: MotionUiState) {
        if (!isAdded) return

        val error = state.lastError
        if (!error.isNullOrBlank()) {
            activity?.runOnUiThread {
                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("BLE_DATA_RX")
        requireContext().registerReceiver(
            bleReceiver,
            filter,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Context.RECEIVER_EXPORTED
            } else {
                0
            }
        )
    }

    override fun onStop() {
        super.onStop()
        try {
            requireContext().unregisterReceiver(bleReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        MotionSessionManager.removeObserver(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            requireActivity().unbindService(serviceConnection)
            isBound = false
        }
    }
}