package com.amneziaclient.simple.vpn.plugins.l2tp

import com.amneziaclient.simple.vpn.plugin.ConnectionStats
import com.amneziaclient.simple.vpn.plugin.PluginConnectionState
import kotlinx.coroutines.flow.MutableStateFlow

/** Пишет [L2tpVpnService] (только он владеет живым android.net.VpnService и
 *  может дергать builder/establish/protect); читает [L2tpPlugin], который
 *  просто транслирует это наружу через VpnPlugin.connectionState/stats. */
object L2tpEngineState {
    val state = MutableStateFlow(PluginConnectionState.DISCONNECTED)
    val stats = MutableStateFlow(ConnectionStats())
    val lastDetail = MutableStateFlow<String?>(null)
}
