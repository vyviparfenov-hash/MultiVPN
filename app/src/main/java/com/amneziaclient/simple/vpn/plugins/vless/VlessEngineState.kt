package com.amneziaclient.simple.vpn.plugins.vless

import com.amneziaclient.simple.vpn.plugin.ConnectionStats
import com.amneziaclient.simple.vpn.plugin.PluginConnectionState
import kotlinx.coroutines.flow.MutableStateFlow

object VlessEngineState {
    val state = MutableStateFlow(PluginConnectionState.DISCONNECTED)
    val stats = MutableStateFlow(ConnectionStats())
    val lastDetail = MutableStateFlow<String?>(null)
}
