package com.remu.app.data.state

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether this phone has validated internet, for the Owner mode offline banner (TASK-117). */
interface InternetState {
    val online: StateFlow<Boolean>
}

class FixedInternetState(initial: Boolean = true) : InternetState {
    override val online = MutableStateFlow(initial)
}

class SystemInternetState(context: Context) : InternetState {
    private val _online = MutableStateFlow(true)
    override val online: StateFlow<Boolean> = _online

    init {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            _online.value = cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            try {
                cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    }
                    override fun onLost(network: Network) { _online.value = false }
                })
            } catch (_: SecurityException) {
                _online.value = true
            }
        }
    }
}
