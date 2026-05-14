package com.example.myapplication

import com.example.myapplication.ShimmerClassicManager
import com.example.myapplication.ImuSample
import android.Manifest
import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.annotation.RequiresPermission
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.BLE
import com.example.myapplication.Decoder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth

class ProfileFragment : Fragment() {

    private var bleService: BLE? = null
    private var isBound = false
    private lateinit var bleManager: SmartRingBleManager

    // Rendiamo lo shimmerManager statico o accessibile per controllarne lo stato
    private var shimmerManager: ShimmerClassicManager? = null

    private var tvBatteryLevelInSheet: TextView? = null

    private val shimmerListener = object : ShimmerClassicManager.ShimmerListener {
        override fun onConnected() {
            activity?.runOnUiThread {
                Toast.makeText(context, "Shimmer Connesso!", Toast.LENGTH_SHORT).show()
            }
            shimmerManager?.setupShimmer()
        }

        override fun onDisconnected() {
            activity?.runOnUiThread {
                Toast.makeText(context, "Shimmer Disconnesso", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onSetup() {
            activity?.runOnUiThread {
                Toast.makeText(context, "Shimmer Pronto!", Toast.LENGTH_SHORT).show()
            }
            shimmerManager?.startStreaming()
        }

        override fun onError(msg: String) {
            activity?.runOnUiThread { Log.e("SHIMMER", "Errore: $msg") }
        }

        override fun onSampleReceived(sample: ImuSample) {
            Log.d("SHIMMER DATA", "AccX: ${sample.accX}, AccY: ${sample.accY}, AccZ: ${sample.accZ}; GyroX: ${sample.gyroX}, GyroY: ${sample.gyroY}, GyroZ: ${sample.gyroZ}")
        }
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

        view.findViewById<MaterialCardView>(R.id.menuUserInfo)?.setOnClickListener { showUserInfoBottomSheet() }
        view.findViewById<MaterialCardView>(R.id.menuFindDevices)?.setOnClickListener { showDevicePickerSheet() }

        // MODIFICA: Ora controlliamo entrambi i dispositivi
        view.findViewById<MaterialCardView>(R.id.menuConnectedDevices)?.setOnClickListener {
            val ringConnected = bleService?.isDeviceConnected() == true
            val shimmerConnected = shimmerManager?.isConnected() == true

            if (ringConnected || shimmerConnected) {
                if (ringConnected) bleService?.requestBatteryLevel()
                showConnectedDeviceBottomSheet()
            } else {
                Toast.makeText(context, "Nessun dispositivo connesso", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<ImageButton>(R.id.logout_button)?.setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            if (isBound) {
                requireActivity().unbindService(serviceConnection)
                isBound = false
            }
            startActivity(Intent(requireActivity(), Login::class.java))
            requireActivity().finish()
        }
    }

    private fun showConnectedDeviceBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.layout_connected_device_sheet, null)

        // Riferimenti Smart Ring
        val cardRing = sheetView.findViewById<MaterialCardView>(R.id.cardSmartRing)
        val tvRingAddress = sheetView.findViewById<TextView>(R.id.tvRingAddress)
        val btnDisconnectRing = sheetView.findViewById<Button>(R.id.btnDisconnectRing)
        tvBatteryLevelInSheet = sheetView.findViewById<TextView>(R.id.tvBatteryLevel)

        // Riferimenti Shimmer
        val cardShimmer = sheetView.findViewById<MaterialCardView>(R.id.cardShimmer)
        val tvShimmerAddress = sheetView.findViewById<TextView>(R.id.tvShimmerAddress)
        val btnDisconnectShimmer = sheetView.findViewById<Button>(R.id.btnDisconnectShimmer)

        // Logica Visibilità Smart Ring
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

        // Logica Visibilità Shimmer
        if (shimmerManager?.isConnected() == true) {
            cardShimmer.visibility = View.VISIBLE
            tvShimmerAddress.text = shimmerManager?.getAddress() ?: "Connesso (BT Classic)"
            btnDisconnectShimmer.setOnClickListener {
                shimmerManager?.disconnect()
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

        val adapter = DeviceAdapter { device, isShimmer ->
            if (isShimmer) {
                // Inizializza manager Shimmer
                shimmerManager = ShimmerClassicManager(requireContext(), device.address, shimmerListener)
                shimmerManager?.connect()
            } else {
                // Connetti Smart Ring
                if (isBound) bleService?.connect(device.address)
            }
            bleManager.stopScan()
            dialog.dismiss()
        }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        bleManager = SmartRingBleManager(requireContext(), adapter)

        // ... (resto della logica scan identica)
        bleManager.startScan()
        dialog.setContentView(sheetView)
        dialog.show()
    }

    // ... (UserInfoBottomSheet e metodi Lifecycle onStart/onStop identici)

    private fun showUserInfoBottomSheet() {
        // (Codice esistente per UserInfo...)
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.dialog_user_info, null)
        // ... (setup spinner e salvataggio)
        dialog.setContentView(sheetView)
        dialog.show()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("BLE_DATA_RX")
        requireContext().registerReceiver(bleReceiver, filter, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0)
    }

    override fun onStop() {
        super.onStop()
        try { requireContext().unregisterReceiver(bleReceiver) } catch (e: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            requireActivity().unbindService(serviceConnection)
            isBound = false
        }
    }
}