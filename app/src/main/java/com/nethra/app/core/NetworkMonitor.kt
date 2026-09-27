package com.nethra.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Tracks whether any internet-capable network is up (false in airplane mode). */
class NetworkMonitor(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(isOnlineNow())
    val online: StateFlow<Boolean> = _online

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _online.value = isOnlineNow() }
            override fun onLost(network: Network) { _online.value = isOnlineNow() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _online.value = isOnlineNow()
            }
        })
    }

    fun isOnlineNow(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
