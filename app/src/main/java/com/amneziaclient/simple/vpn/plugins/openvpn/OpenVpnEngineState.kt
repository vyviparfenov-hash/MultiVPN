package com.amneziaclient.simple.vpn.plugins.openvpn

import com.amneziaclient.simple.vpn.plugin.ConnectionStats
import com.amneziaclient.simple.vpn.plugin.PluginConnectionState
import kotlinx.coroutines.flow.MutableStateFlow

/** Пишет [OpenVpnVpnService] (только он владеет живым android.net.VpnService
 *  и может дёргать builder/establish/protect); читает [OpenVpnPlugin]. */
object OpenVpnEngineState {
    val state = MutableStateFlow(PluginConnectionState.DISCONNECTED)
    val stats = MutableStateFlow(ConnectionStats())
    val lastDetail = MutableStateFlow<String?>(null)
}
