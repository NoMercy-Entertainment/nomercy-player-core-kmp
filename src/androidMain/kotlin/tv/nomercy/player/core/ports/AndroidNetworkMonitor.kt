// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.ports

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import tv.nomercy.player.core.events.Subscription

// Reads connectivity from ConnectivityManager rather than assuming it, because
// Android is where the answer changes most: a phone moves between wifi and
// cellular mid-playback and ABR should know.
public class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val connectivity: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun capabilities(): NetworkCapabilities? =
        connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)

    override fun isOnline(): Boolean =
        capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    override fun type(): NetworkType {
        val current: NetworkCapabilities = capabilities() ?: return NetworkType.NONE
        return when {
            current.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            current.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            current.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.UNKNOWN
        }
    }

    override fun downlinkMbps(): Double? = null
    override fun rttMs(): Double? = null

    private fun snapshot(): NetworkSnapshot = NetworkSnapshot(isOnline(), type())

    override fun subscribe(fn: (NetworkSnapshot) -> Unit): Subscription {
        val relay = NetworkChangeRelay(read = ::snapshot, fn = fn)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network): Unit = relay.onAvailable()
            override fun onLost(network: Network): Unit = relay.onLost()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities): Unit =
                relay.onCapabilitiesChanged()
        }
        connectivity.registerDefaultNetworkCallback(callback)
        return Subscription { connectivity.unregisterNetworkCallback(callback) }
    }
}

// onLost fires while activeNetwork can still answer with the network that just
// went, so a read there would report online; the loss is stated directly.
internal class NetworkChangeRelay(
    private val read: () -> NetworkSnapshot,
    private val fn: (NetworkSnapshot) -> Unit,
) {
    fun onAvailable(): Unit = fn(read())
    fun onCapabilitiesChanged(): Unit = fn(read())
    fun onLost(): Unit = fn(NetworkSnapshot(online = false, type = NetworkType.NONE))
}
