package com.amneziaclient.simple.data

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

data class VpnUiState(
    val configLoaded: Boolean = false,
    val connectionState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val selectedAppsCount: Int = 0,
    val errorMessageRes: Int? = null,
    val connectedSinceMillis: Long? = null
)
