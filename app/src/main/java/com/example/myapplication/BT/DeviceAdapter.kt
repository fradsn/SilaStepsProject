package com.example.myapplication.BT

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R

class DeviceAdapter(private val onDeviceClick: (BluetoothDevice, Boolean) -> Unit) :
    RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    private val devices = mutableListOf<BluetoothDevice>()
    // Mappa per tenere traccia del tipo di dispositivo: Key = MAC Address, Value = Boolean (true se Shimmer)
    private val deviceTypeMap = HashMap<String, Boolean>()

    @SuppressLint("MissingPermission")
    fun addDevice(device: BluetoothDevice, isShimmer: Boolean) {
        // Verifichiamo se il dispositivo è già presente nella lista tramite il suo indirizzo
        if (!devices.any { it.address == device.address }) {
            devices.add(device)
            // Salviamo l'associazione nella mappa
            deviceTypeMap[device.address] = isShimmer
            notifyItemInserted(devices.size - 1)
        } else {
            // Opzionale: aggiorniamo il valore nella mappa nel caso il dispositivo
            // fosse già in lista ma rilevato nuovamente con flag differente
            deviceTypeMap[device.address] = isShimmer
        }
    }

    /**
     * Funzione di utilità per verificare se un dispositivo in una
     * determinata posizione è uno Shimmer
     */
    fun isDeviceShimmer(position: Int): Boolean {
        val device = devices[position]
        return deviceTypeMap[device.address] ?: false
    }

    fun clear() {
        val size = devices.size
        devices.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // RIFERIMENTO CORRETTO: device_item.xml
        val view = LayoutInflater.from(parent.context).inflate(R.layout.device_item, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.name.text = device.name ?: "Unknown Device"
        holder.address.text = device.address
        val isShimmer = deviceTypeMap[device.address] ?: false
        holder.itemView.setOnClickListener { onDeviceClick(device, isShimmer) }
    }


    override fun getItemCount() = devices.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        // RIFERIMENTI CORRETTI: deviceName e deviceAddress dal tuo XML
        val name: TextView = view.findViewById(R.id.deviceName)
        val address: TextView = view.findViewById(R.id.deviceAddress)
    }
}