package com.example.myapplication

import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.BLE
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth

class ProfileFragment : Fragment() {

    private var bleService: BLE? = null
    private var isBound = false
    private lateinit var bleManager: SmartRingBleManager

    // Riferimento dinamico alla TextView della batteria nel BottomSheet
    private var tvBatteryLevelInSheet: TextView? = null

    // Ricevitore per i dati provenienti dal servizio BLE
    private val bleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "BLE_DATA_RX") {
                val raw = intent.getStringExtra("data") ?: return
                // Cerchiamo solo i messaggi che iniziano con "RX: " (dati ricevuti dall'anello)
                if (raw.startsWith("RX: ")) {
                    val hex = raw.removePrefix("RX: ")
                    val decoded = Decoder.decode(hex)

                    // Se il decoder identifica un pacchetto BATTERY, aggiorniamo la UI
                    if (decoded?.type == "BATTERY") {
                        updateBatteryUI(decoded.battery, decoded.chargingStatus)
                    }
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val binderLocal = binder as? BLE.LocalBinder
            bleService = binderLocal?.getService()
            isBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            isBound = false
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val intent = Intent(requireActivity(), BLE::class.java)
        requireActivity().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        view.findViewById<MaterialCardView>(R.id.menuUserInfo)?.setOnClickListener {
            showUserInfoBottomSheet()
        }

        view.findViewById<MaterialCardView>(R.id.menuFindDevices)?.setOnClickListener {
            showDevicePickerSheet()
        }

        view.findViewById<MaterialCardView>(R.id.menuConnectedDevices)?.setOnClickListener {
            if (isBound && bleService != null && bleService!!.isDeviceConnected()) {
                // Richiediamo l'aggiornamento della batteria all'anello appena clicchiamo
                bleService?.requestBatteryLevel()
                showConnectedDeviceBottomSheet()
            } else {
                Toast.makeText(context, "Nessun dispositivo connesso", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<ImageButton>(R.id.logout_button)?.setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            val sharedPreferences = requireActivity().getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
            val editor = sharedPreferences.edit()
            editor.clear()
            editor.apply()

            if (isBound) {
                try {
                    requireActivity().unbindService(serviceConnection)
                } catch (e: Exception) { }
                isBound = false
            }

            val loginIntent = Intent(requireActivity(), Login::class.java)
            loginIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(loginIntent)
            requireActivity().finish()

            Toast.makeText(context, "Logout effettuato", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showConnectedDeviceBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.layout_connected_device_sheet, null)

        val tvName = sheetView.findViewById<TextView>(R.id.tvDeviceName)
        val tvAddress = sheetView.findViewById<TextView>(R.id.tvDeviceAddress)
        val btnDisconnect = sheetView.findViewById<Button>(R.id.btnDisconnect)

        // Colleghiamo il riferimento della batteria per aggiornarlo quando arriva il segnale BT
        tvBatteryLevelInSheet = sheetView.findViewById<TextView>(R.id.tvBatteryLevel)

        tvName.text = "Smart Ring"
        tvAddress.text = "Connesso" // Rimosso il MAC fisso per coerenza con le tue modifiche

        btnDisconnect?.setOnClickListener {
            bleService?.disconnectDevice()
            Toast.makeText(context, "Dispositivo disconnesso", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.setContentView(sheetView)
        dialog.show()

        // Puliamo il riferimento quando il dialog viene chiuso
        dialog.setOnDismissListener {
            tvBatteryLevelInSheet = null
        }
    }

    private fun updateBatteryUI(percent: Int, status: Int) {
        activity?.runOnUiThread {
            // Determina l'icona o il testo in base allo stato di ricarica
            val chargingIcon = if (status == 0x02) "⚡ " else ""
            tvBatteryLevelInSheet?.text = "Batteria: $chargingIcon$percent%"
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
        val btnSave = sheetView.findViewById<Button>(R.id.btnSaveInfo)

        val genders = arrayOf("Maschio", "Femmina")
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, genders)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerGender?.adapter = spinnerAdapter

        val sharedPrefs = requireContext().getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        editName?.setText(sharedPrefs.getString("name", ""))
        editSurname?.setText(sharedPrefs.getString("surname", ""))
        editHeight?.setText(sharedPrefs.getString("height", ""))
        editWeight?.setText(sharedPrefs.getString("weight", ""))
        editAge?.setText(sharedPrefs.getString("age", ""))
        val savedGender = sharedPrefs.getString("gender", "Maschio")
        spinnerGender?.setSelection(if (savedGender == "Maschio") 0 else 1)

        btnSave?.setOnClickListener {
            val editor = sharedPrefs.edit()
            editor.putString("name", editName?.text.toString())
            editor.putString("surname", editSurname?.text.toString())
            editor.putString("height", editHeight?.text.toString())
            editor.putString("weight", editWeight?.text.toString())
            editor.putString("age", editAge?.text.toString())
            editor.putString("gender", spinnerGender?.selectedItem.toString())
            editor.apply()

            Toast.makeText(context, "Dati salvati!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.setContentView(sheetView)
        dialog.show()
    }

    private fun showDevicePickerSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.layout_find_devices_sheet, null)
        val rv = sheetView.findViewById<RecyclerView>(R.id.rvDevices)
        val btnScan = sheetView.findViewById<Button>(R.id.btnScan)
        val progress = sheetView.findViewById<ProgressBar>(R.id.scanProgress)

        val adapter = DeviceAdapter { device ->
            if (isBound && bleService != null) {
                bleService?.connect(device.address)
                Toast.makeText(context, "Connessione a: ${device.name ?: "Anello"}", Toast.LENGTH_SHORT).show()
                bleManager.stopScan()
                dialog.dismiss()
            }
        }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        bleManager = SmartRingBleManager(requireContext(), adapter)

        fun updateScanState(isScanning: Boolean) {
            if (isScanning) {
                btnScan?.text = "RICERCA IN CORSO..."
                btnScan?.isEnabled = false
                progress?.visibility = View.VISIBLE
            } else {
                btnScan?.text = "AVVIA RICERCA"
                btnScan?.isEnabled = true
                progress?.visibility = View.GONE
            }
        }

        btnScan?.setOnClickListener {
            adapter.clear()
            bleManager.startScan()
            updateScanState(true)

            Handler(Looper.getMainLooper()).postDelayed({
                bleManager.stopScan()
                updateScanState(false)
            }, 10000)
        }

        dialog.setContentView(sheetView)
        dialog.show()

        bleManager.startScan()
        updateScanState(true)

        Handler(Looper.getMainLooper()).postDelayed({
            bleManager.stopScan()
            updateScanState(false)
        }, 10000)
    }

    override fun onStart() {
        super.onStart()
        // Registriamo il ricevitore per ascoltare i dati in arrivo
        val filter = IntentFilter("BLE_DATA_RX")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(bleReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            requireContext().registerReceiver(bleReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        // Disregistriamo il ricevitore per evitare memory leak
        try {
            requireContext().unregisterReceiver(bleReceiver)
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            requireActivity().unbindService(serviceConnection)
            isBound = false
        }
    }
}