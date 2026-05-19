package com.example.myapplication

import android.Manifest
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
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth

class ProfileFragment : Fragment(), MotionSessionManager.Observer {

    private var bleService: BLE? = null
    private var isBound = false
    private lateinit var bleManager: SmartRingBleManager

    private var tvBatteryLevelInSheet: TextView? = null

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
    @androidx.annotation.RequiresPermission(
        android.Manifest.permission.BLUETOOTH_SCAN
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
            showDevicePickerSheet()
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
        val sheetView = layoutInflater.inflate(R.layout.dialog_user_info, null)
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