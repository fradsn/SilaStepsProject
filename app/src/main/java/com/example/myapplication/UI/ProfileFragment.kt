package com.example.myapplication.UI

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isGone
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
import com.example.myapplication.services.HealthMonitoringService
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth

class ProfileFragment : Fragment(), SmartRingManager.SmartRingListener, MotionSessionManager.Observer {

    private var tvBatteryLevelInSheet: TextView? = null
    private val auth = FirebaseAuth.getInstance()
    private var deviceAdapter: DeviceAdapter? = null

    // TIMER CICLICO (UI Polling) per rinfrescare lo stato della batteria nel Bottom Sheet
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            rinfrescaDatiInRealtime()
            pollHandler.postDelayed(this, 2000) // Interroga le SharedPreferences ogni 2 secondi
        }
    }

    // Launcher asincrono per la richiesta dei permessi nativi
    @RequiresApi(Build.VERSION_CODES.S)
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
    // GESTIONE SMART RING LISTENER CALLBACKS (Formale: l'ascolto primario è delegato al Service)
    // =====================================================================================
    override fun onConnected() {}
    override fun onDisconnected() {}
    override fun onDataReceived(result: Decoder.DecodedResult) {}
    override fun onError(msg: String) {
        activity?.runOnUiThread { Log.e("SMART_RING", "Errore: $msg") }
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
        val sheetView = inflater.inflate(R.layout.fragment_profile, container, false)

        aggiornaUI(sheetView)

        return sheetView
    }

    private fun aggiornaUI(sheetView: View) {
        val userId = auth.currentUser?.uid ?: "user"

        val sharedPref = sheetView.context.getSharedPreferences("UserData_$userId", Context.MODE_PRIVATE)
        val savedName = sharedPref.getString("name", "")
        val savedImageUri = sharedPref.getString("profile_image_uri", null)

        sheetView.findViewById<TextView>(R.id.profile_email).text = "Benvenuto $savedName"
        val profileImage = sheetView.findViewById<ImageView>(R.id.profile_image)
        if (savedImageUri.isNullOrEmpty()) {
            profileImage.setImageResource(R.drawable.user_svgrepo_com)
            profileImage.setColorFilter(Color.WHITE)
        } else {
            profileImage.clearColorFilter()
            profileImage.setImageURI(savedImageUri.toUri())
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        parentFragmentManager.setFragmentResultListener("refresh_profile", this) { _, _ ->
            aggiornaUI(view)
        }

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
            val ringConnected = SmartRingManager.getActiveInstance()?.isConnected() == true
            val shimmerConnected = MotionSessionManager.isShimmerConnected()

            if (ringConnected || shimmerConnected) {
                // Chiediamo all'hardware di aggiornare la batteria: la risposta verrà catturata dal Service e scritta nelle Prefs
                SmartRingManager.getActiveInstance()?.requestBatteryLevel()
                showConnectedDeviceBottomSheet()
            } else {
                Toast.makeText(context, "Nessun dispositivo connesso", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<ImageButton>(R.id.logout_button)?.setOnClickListener {
            auth.signOut()

            // Interrompiamo definitivamente il servizio di monitoraggio in background al logout
            context?.stopService(Intent(context, HealthMonitoringService::class.java))

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

            // Prima lettura immediata del valore memorizzato nelle preferenze prima che parta il ciclo del timer
            val sharedPref = requireContext().getSharedPreferences("RingPrefs", Context.MODE_PRIVATE)
            tvBatteryLevelInSheet?.text = sharedPref.getString("last_battery_level", "Batteria: --")

            btnDisconnectRing.setOnClickListener {
                activeRing.disconnect()
                // Se scolleghiamo l'anello, interrompiamo anche il Service
                context?.stopService(Intent(context, HealthMonitoringService::class.java))
                cardRing.visibility = View.GONE
                if (cardShimmer.isGone) dialog.dismiss()
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
                if (cardRing.isGone) dialog.dismiss()
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
                // FIX CRITICO: Invece di istanziare e connettere l'anello dal Fragment rubando il Listener,
                // passiamo il testimone al Service tramite Intent. Sarà il Service ad attivare l'anello e a fare da ascoltatore fisso.
                val serviceIntent = Intent(context, HealthMonitoringService::class.java).apply {
                    putExtra("MAC_ADDRESS", device.address)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context?.startForegroundService(serviceIntent)
                } else {
                    context?.startService(serviceIntent)
                }
                Toast.makeText(requireContext(), "Inizializzazione connessione tramite Service...", Toast.LENGTH_SHORT).show()
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
        val sheet = UserInfoBottomSheet()
        sheet.show(parentFragmentManager, "UserInfoBottomSheet")
    }

    /**
     * Metodo di Polling attivato ciclicamente dall'Handler
     */
    private fun rinfrescaDatiInRealtime() {
        if (!isAdded) return
        // Se l'utente ha aperto il Bottom Sheet della connessione e la TextView della batteria esiste...
        if (tvBatteryLevelInSheet != null) {
            val sharedPref = requireContext().getSharedPreferences("RingPrefs", Context.MODE_PRIVATE)
            val livelloBatteria = sharedPref.getString("last_battery_level", "Batteria: --")

            activity?.runOnUiThread {
                tvBatteryLevelInSheet?.text = livelloBatteria
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Avviamo il timer di polling grafico ogni 2 secondi
        pollHandler.post(pollRunnable)

        // FIX LOGICA: Rimosso completamente ringManager?.updateListener(this).
        // Lasciamo che il Service continui a fare da ascoltatore fisso hardware in totale autonomia.
    }

    override fun onPause() {
        super.onPause()

        // Interrompiamo il timer di polling grafico all'uscita dal fragment
        pollHandler.removeCallbacks(pollRunnable)

        // FIX LOGICA: Rimosso lo scollegamento (updateListener(object...)) che andava ad accecare il Service.
    }

    override fun onDestroyView() {
        super.onDestroyView()
        MotionSessionManager.removeObserver(this)
        pollHandler.removeCallbacks(pollRunnable)
    }
}