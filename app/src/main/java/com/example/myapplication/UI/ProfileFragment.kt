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

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            rinfrescaDatiInRealtime()
            pollHandler.postDelayed(this, 2000)
        }
    }

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
            Toast.makeText(context, "Bluetooth and location permissions are required to scan for devices", Toast.LENGTH_LONG).show()
        }
    }

    override fun onConnected() {}
    override fun onDisconnected() {}
    override fun onDataReceived(result: Decoder.DecodedResult) {}
    override fun onError(msg: String) {
        activity?.runOnUiThread { Log.e("SMART_RING", "Hardware Error: $msg") }
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val sheetView = inflater.inflate(R.layout.fragment_profile, container, false)
        aggiornaUI(sheetView)
        return sheetView
    }

    private fun aggiornaUI(sheetView: View) {
        val userId = auth.currentUser?.uid ?: "user"
        val sharedPref = sheetView.context.getSharedPreferences("UserData_$userId", Context.MODE_PRIVATE)
        val savedName = sharedPref.getString("name", "Welcome")
        val savedImageUri = sharedPref.getString("profile_image_uri", null)

        sheetView.findViewById<TextView>(R.id.profile_email).text = savedName
        val profileImage = sheetView.findViewById<ImageView>(R.id.profile_image)
        if (savedImageUri.isNullOrEmpty()) {
            profileImage.setImageResource(R.drawable.user_svgrepo_com)
            profileImage.setColorFilter(resources.getColor(R.color.text_secondary))
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

        MotionSessionManager.initialize(requireContext())
        MotionSessionManager.addObserver(this)

        view.findViewById<MaterialCardView>(R.id.menuUserInfo)?.setOnClickListener { showUserInfoBottomSheet() }

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
                ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED
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
                SmartRingManager.getActiveInstance()?.requestBatteryLevel()
                showConnectedDeviceBottomSheet()
            } else {
                Toast.makeText(context, "No hardware devices currently paired", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<ImageButton>(R.id.logout_button)?.setOnClickListener {
            auth.signOut()
            context?.stopService(Intent(context, HealthMonitoringService::class.java))

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

        // Logica Smart Ring con protezioni anti-crash
        if (activeRing?.isConnected() == true) {
            cardRing?.visibility = View.VISIBLE
            tvRingName?.text = "Smart Ring"
            tvRingAddress?.text = activeRing.getAddress()

            val sharedPref = requireContext().getSharedPreferences("RingPrefs", Context.MODE_PRIVATE)
            tvBatteryLevelInSheet?.text = sharedPref.getString("last_battery_level", "Battery: --")

            btnDisconnectRing?.setOnClickListener {
                try {
                    activeRing.disconnect()
                    context?.stopService(Intent(context, HealthMonitoringService::class.java))
                    cardRing?.visibility = View.GONE

                    // Chiudiamo il dialog in modo pulito se non ci sono altri sensori attivi
                    if (cardShimmer == null || cardShimmer.isGone || cardShimmer.visibility == View.GONE) {
                        dialog.dismiss()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    dialog.dismiss()
                }
            }
        } else {
            cardRing?.visibility = View.GONE
        }

        // Logica Shimmer con protezioni anti-crash
        if (MotionSessionManager.isShimmerConnected()) {
            cardShimmer?.visibility = View.VISIBLE
            tvShimmerName?.text = "Shimmer3 Node"
            tvShimmerAddress?.text = MotionSessionManager.getShimmerAddress() ?: "Connected"

            btnDisconnectShimmer?.setOnClickListener {
                try {
                    MotionSessionManager.disconnectShimmer()
                    cardShimmer?.visibility = View.GONE

                    if (cardRing == null || cardRing.isGone || cardRing.visibility == View.GONE) {
                        dialog.dismiss()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    dialog.dismiss()
                }
            }
        } else {
            cardShimmer?.visibility = View.GONE
        }

        dialog.setContentView(sheetView)
        dialog.show()
        dialog.setOnDismissListener { tvBatteryLevelInSheet = null }
    }

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
                MotionSessionManager.connectToShimmer(requireContext(), device.address)
                Toast.makeText(requireContext(), "Connecting to Shimmer node...", Toast.LENGTH_SHORT).show()
            } else {
                val serviceIntent = Intent(context, HealthMonitoringService::class.java).apply {
                    putExtra("MAC_ADDRESS", device.address)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context?.startForegroundService(serviceIntent)
                } else {
                    context?.startService(serviceIntent)
                }
                Toast.makeText(requireContext(), "Initializing hardware connection via Service...", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = deviceAdapter

        BleScannerManager.init(deviceAdapter!!, object : BleScannerManager.ScanStateListener {
            override fun onScanStarted() {
                btnScan.text = "STOP SEARCH"
                scanProgress.visibility = View.VISIBLE
            }

            override fun onScanStopped() {
                btnScan.text = "START SEARCH"
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
        dialog.setOnDismissListener { BleScannerManager.stopScan() }
    }

    private fun showUserInfoBottomSheet() {
        val sheet = UserInfoBottomSheet()
        sheet.show(parentFragmentManager, "UserInfoBottomSheet")
    }

    private fun rinfrescaDatiInRealtime() {
        if (!isAdded) return
        if (tvBatteryLevelInSheet != null) {
            val sharedPref = requireContext().getSharedPreferences("RingPrefs", Context.MODE_PRIVATE)
            val livelloBatteria = sharedPref.getString("last_battery_level", "Battery: --")

            activity?.runOnUiThread {
                tvBatteryLevelInSheet?.text = livelloBatteria
            }
        }
    }

    override fun onResume() {
        super.onResume()
        pollHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        MotionSessionManager.removeObserver(this)
        pollHandler.removeCallbacks(pollRunnable)
    }
}