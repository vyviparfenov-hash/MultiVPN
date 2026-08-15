package com.amneziaclient.simple.ui

import com.amneziaclient.simple.R
import com.amneziaclient.simple.vpn.plugin.VpnProtocolType

/**
 * Значок и цвет для каждого протокола — единое место, используется и
 * списком профилей (ProfileListAdapter), и диалогом создания/редактирования
 * профиля (ProfilesFragment). Раньше это дублировалось в обоих местах по
 * отдельности — вынесено сюда при чистке кода.
 *
 * Не копируем чужие товарные знаки — простые обобщённые значки по смыслу
 * протокола (ключ, замок, молния и т.д.), а не логотипы реальных продуктов.
 */
object ProtocolUi {

    fun colorRes(protocol: VpnProtocolType): Int = when (protocol) {
        VpnProtocolType.AMNEZIAWG -> R.color.protocol_amneziawg
        VpnProtocolType.WIREGUARD -> R.color.protocol_wireguard
        VpnProtocolType.OPENVPN -> R.color.protocol_openvpn
        VpnProtocolType.IKEV2 -> R.color.protocol_ikev2
        VpnProtocolType.L2TP -> R.color.protocol_l2tp
        VpnProtocolType.SSTP -> R.color.protocol_sstp
        VpnProtocolType.SOFTETHER -> R.color.protocol_softether
        VpnProtocolType.VLESS -> R.color.protocol_vless
    }

    fun iconRes(protocol: VpnProtocolType): Int = when (protocol) {
        VpnProtocolType.AMNEZIAWG -> R.drawable.ic_protocol_amneziawg
        VpnProtocolType.WIREGUARD -> R.drawable.ic_shield
        VpnProtocolType.OPENVPN -> R.drawable.ic_protocol_openvpn
        VpnProtocolType.IKEV2 -> R.drawable.ic_protocol_ikev2
        VpnProtocolType.L2TP -> R.drawable.ic_protocol_l2tp
        VpnProtocolType.SSTP -> R.drawable.ic_protocol_sstp
        VpnProtocolType.SOFTETHER -> R.drawable.ic_protocol_softether
        VpnProtocolType.VLESS -> R.drawable.ic_protocol_vless
    }
}
