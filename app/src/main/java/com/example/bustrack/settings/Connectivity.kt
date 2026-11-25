package com.example.bustrack.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stato della connessione a Internet (rete con accesso verificato), aggiornato da Android:
 * serve per avvisare quando si è offline e per ricaricare i dati live appena la rete torna.
 */
object Connectivity {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
            _online.value = cm.getNetworkCapabilities(cm.activeNetwork).isUsable()
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _online.value = caps.isUsable()
                }

                override fun onLost(network: Network) {
                    _online.value = false
                }
            })
        }
    }

    private fun NetworkCapabilities?.isUsable(): Boolean =
        this != null && hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
