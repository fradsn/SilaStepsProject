package com.example.myapplication.UI

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.example.myapplication.BT.ring.SmartRingManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.firebase.auth.FirebaseAuth
import com.example.myapplication.R
import java.io.File
import java.io.FileOutputStream
import androidx.core.net.toUri

class UserInfoBottomSheet : BottomSheetDialogFragment() {

    private lateinit var pickImageLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private var selectedImageUri: Uri? = null

    private var imgPreview: ImageView? = null
    private var spinnerGender: Spinner? = null
    private var editName: EditText? = null
    private var editSurname: EditText? = null
    private var editHeight: EditText? = null
    private var editWeight: EditText? = null
    private var editAge: EditText? = null
    private var btnSaveInfo: Button? = null
    private var btnPickImage: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Launcher moderno per aprire la galleria
        pickImageLauncher = registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                val savedUri = saveImageToInternalStorage(uri)
                selectedImageUri = savedUri
                imgPreview?.setImageURI(savedUri)
            }
        }

    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.dialog_user_info, container, false)

        // Collegamento view
        imgPreview = view.findViewById(R.id.imgProfilePreview)
        btnPickImage = view.findViewById(R.id.btnPickImage)
        spinnerGender = view.findViewById(R.id.spinnerGender)
        editName = view.findViewById(R.id.editName)
        editSurname = view.findViewById(R.id.editSurname)
        editHeight = view.findViewById(R.id.editHeight)
        editWeight = view.findViewById(R.id.editWeight)
        editAge = view.findViewById(R.id.editAge)
        btnSaveInfo = view.findViewById(R.id.btnSaveInfo)

        setupUI()

        return view
    }

    private fun setupUI() {
        val genderOptions = arrayOf("Maschio", "Femmina", "Altro")
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, genderOptions)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerGender?.adapter = spinnerAdapter

        val auth = FirebaseAuth.getInstance()
        val userId = auth.currentUser?.uid ?: "user"
        val sharedPref = requireContext().getSharedPreferences("UserData_$userId", Context.MODE_PRIVATE)

        // Carica dati salvati
        val savedGenderPos = sharedPref.getInt("gender_pos", 0)
        val savedAge = sharedPref.getString("age", "")
        val savedHeight = sharedPref.getString("height", "")
        val savedWeight = sharedPref.getString("weight", "")
        val savedName = sharedPref.getString("name", "")
        val savedSurname = sharedPref.getString("surname", "")
        val savedImageUri = sharedPref.getString("profile_image_uri", null)

        spinnerGender?.setSelection(savedGenderPos)
        editAge?.setText(savedAge)
        editHeight?.setText(savedHeight)
        editWeight?.setText(savedWeight)
        editName?.setText(savedName)
        editSurname?.setText(savedSurname)

        if (savedImageUri.isNullOrEmpty()) {
            imgPreview?.setImageResource(R.drawable.user_svgrepo_com)
            imgPreview?.setColorFilter(Color.WHITE)
        } else {
            imgPreview?.clearColorFilter()
            imgPreview?.setImageURI(savedImageUri.toUri())
        }

        // Apertura galleria
        btnPickImage?.setOnClickListener {
            pickImageLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }


        // Salvataggio dati
        btnSaveInfo?.setOnClickListener {
            saveUserInfo(sharedPref)
        }
    }

    private fun saveImageToInternalStorage(uri: Uri): Uri? {
        return try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val fileName = "profile_image_${System.currentTimeMillis()}.jpg"
            val file = File(requireContext().filesDir, fileName)

            val outputStream = FileOutputStream(file)
            inputStream?.copyTo(outputStream)

            inputStream?.close()
            outputStream.close()

            Uri.fromFile(file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun saveUserInfo(sharedPref: android.content.SharedPreferences) {
        val ageStr = editAge?.text.toString().trim()
        val heightStr = editHeight?.text.toString().trim()
        val weightStr = editWeight?.text.toString().trim()
        val nameStr = editName?.text.toString().trim()
        val surnameStr = editSurname?.text.toString().trim()
        val genderPos = spinnerGender?.selectedItemPosition ?: 0

        if (ageStr.isEmpty() || heightStr.isEmpty() || weightStr.isEmpty()) {
            Toast.makeText(requireContext(), "Compila obbligatoriamente Età, Altezza e Peso", Toast.LENGTH_LONG).show()
            return
        }

        sharedPref.edit().apply {
            putInt("gender_pos", genderPos)
            putString("age", ageStr)
            putString("height", heightStr)
            putString("weight", weightStr)
            putString("name", nameStr)
            putString("surname", surnameStr)
            putString("profile_image_uri", selectedImageUri?.toString())
            apply()
        }

        SmartRingManager.getActiveInstance()?.syncUserInfo()

        // Notifica il Fragment padre
        parentFragmentManager.setFragmentResult("refresh_profile", Bundle())

        Toast.makeText(requireContext(), "Informazioni salvate!", Toast.LENGTH_SHORT).show()
        dismiss()
    }
}
