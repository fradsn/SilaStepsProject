package com.example.myapplication

import android.content.*
import android.os.Bundle
import android.os.IBinder
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
import com.google.firebase.auth.FirebaseAuth // Importante per il logout reale

class ProfileFragment : Fragment() {

    private var bleService: BLE? = null
    private var isBound = false
    private lateinit var bleManager: SmartRingBleManager

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

        // Tasto Logout: Logica originale ripristinata
        view.findViewById<ImageButton>(R.id.logout_button)?.setOnClickListener {

            // 1. Logout da Firebase
            FirebaseAuth.getInstance().signOut()

            // 2. Pulizia SharedPreferences (per dimenticare l'utente)
            val sharedPreferences = requireActivity().getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
            val editor = sharedPreferences.edit()
            editor.clear()
            editor.apply()

            // 3. Scollegamento BLE (se attivo)
            if (isBound) {
                try {
                    requireActivity().unbindService(serviceConnection)
                } catch (e: Exception) { }
                isBound = false
            }

            // 4. Ritorno alla schermata di Login
            val loginIntent = Intent(requireActivity(), Login::class.java)
            loginIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(loginIntent)
            requireActivity().finish()

            Toast.makeText(context, "Logout effettuato", Toast.LENGTH_SHORT).show()
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
        val btnScan = sheetView.findViewById<View>(R.id.btnScan)

        val adapter = DeviceAdapter { device ->
            if (isBound && bleService != null) {
                bleService?.connect(device.address)
                Toast.makeText(context, "Connessione a: ${device.name ?: "Anello"}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        bleManager = SmartRingBleManager(requireContext(), adapter)

        btnScan?.setOnClickListener {
            adapter.clear()
            bleManager.startScan()
        }

        dialog.setContentView(sheetView)
        dialog.show()
        bleManager.startScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            requireActivity().unbindService(serviceConnection)
            isBound = false
        }
    }
}