package com.amneziaclient.simple.di

import com.amneziaclient.simple.vpn.plugin.VpnPlugin
import com.amneziaclient.simple.vpn.plugins.amneziawg.AmneziaWgPlugin
import com.amneziaclient.simple.vpn.plugins.ikev2.IKEv2Plugin
import com.amneziaclient.simple.vpn.plugins.l2tp.L2tpPlugin
import com.amneziaclient.simple.vpn.plugins.openvpn.OpenVpnPlugin
import com.amneziaclient.simple.vpn.plugins.softether.SoftEtherPlugin
import com.amneziaclient.simple.vpn.plugins.sstp.SstpPlugin
import com.amneziaclient.simple.vpn.plugins.vless.VlessPlugin
import com.amneziaclient.simple.vpn.plugins.wireguard.WireGuardPlugin
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Регистрирует все VPN-плагины в Hilt multibinding Set<VpnPlugin>, которым
 * пользуется PluginRegistry. Чтобы добавить новый протокол — создать класс,
 * реализующий VpnPlugin, и добавить сюда ОДНУ @Binds-функцию. Код
 * PluginRegistry, VpnManager и экранов менять не нужно — в этом и есть смысл
 * Plugin Architecture.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PluginModule {

    @Binds
    @IntoSet
    abstract fun bindAmneziaWgPlugin(plugin: AmneziaWgPlugin): VpnPlugin

    @Binds
    @IntoSet
    abstract fun bindWireGuardPlugin(plugin: WireGuardPlugin): VpnPlugin

    @Binds
    @IntoSet
    abstract fun bindIKEv2Plugin(plugin: IKEv2Plugin): VpnPlugin

    @Binds
    @IntoSet
    abstract fun bindL2tpPlugin(plugin: L2tpPlugin): VpnPlugin

    @Binds
    @IntoSet
    abstract fun bindOpenVpnPlugin(plugin: OpenVpnPlugin): VpnPlugin

    @Binds
    @IntoSet
    abstract fun bindSstpPlugin(plugin: SstpPlugin): VpnPlugin

    @Binds
    @IntoSet
    abstract fun bindSoftEtherPlugin(plugin: SoftEtherPlugin): VpnPlugin

    @Binds
    @IntoSet
    abstract fun bindVlessPlugin(plugin: VlessPlugin): VpnPlugin
}
