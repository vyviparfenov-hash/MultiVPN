package com.amneziaclient.simple.ui.home

import android.animation.ObjectAnimator
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.amneziaclient.simple.R
import com.amneziaclient.simple.data.VpnConnectionState
import com.amneziaclient.simple.databinding.FragmentHomeBinding
import com.amneziaclient.simple.ui.MainActivity
import com.amneziaclient.simple.ui.main.MainViewModel
import com.amneziaclient.simple.vpn.plugin.ConnectionStats
import com.amneziaclient.simple.vpn.plugin.VpnProtocolType
import com.amneziaclient.simple.vpn.plugins.l2tp.L2tpEngineState
import com.amneziaclient.simple.vpn.plugins.openvpn.OpenVpnEngineState
import com.amneziaclient.simple.vpn.plugins.vless.VlessEngineState
import com.amneziaclient.simple.ui.showTopSnackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private var ringSpinnerAnimator: ObjectAnimator? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonToggleConnection.setOnClickListener { onToggleConnectionClicked() }
        binding.connectCircleContainer.setOnClickListener { onToggleConnectionClicked() }
        binding.cardSplitTunnel.setOnClickListener {
            (requireActivity() as MainActivity).switchToTab(R.id.nav_apps)
        }

        setupStatCells()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state -> render(state.configLoaded, state.connectionState, state.selectedAppsCount) }
                }
                launch {
                    viewModel.stats.collect { stats -> renderStats(stats) }
                }
                launch {
                    viewModel.oneTimeError.collect { message ->
                        message?.let { binding.root.showTopSnackbar(it); viewModel.consumeError() }
                    }
                }
                launch {
                    viewModel.profiles.collect { updateProfileCard() }
                }
                launch {
                    // profiles StateFlow не эмитит заново, если поменялся
                    // только АКТИВНЫЙ профиль (сам список объектов при этом
                    // не меняется структурно) — нужен отдельный источник.
                    viewModel.activeProfileFlow.collect { updateProfileCard() }
                }
                launch {
                    L2tpEngineState.lastDetail.collect { detail ->
                        updateConnectingDetail(VpnProtocolType.L2TP, detail)
                    }
                }
                launch {
                    OpenVpnEngineState.lastDetail.collect { detail ->
                        updateConnectingDetail(VpnProtocolType.OPENVPN, detail)
                    }
                }
                launch {
                    VlessEngineState.lastDetail.collect { detail ->
                        updateConnectingDetail(VpnProtocolType.VLESS, detail)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateProfileCard()
    }

    /** Показывает деталь и во время "Идёт соединение..." (живой прогресс), и
     *  при "Ошибка" (причина падения) — раньше при ошибке текст просто
     *  скрывался, и пользователь не видел вообще никакой причины, только
     *  общее "Ошибка". */
    private fun updateConnectingDetail(protocol: VpnProtocolType, detail: String?) {
        val state = viewModel.uiState.value
        val isRelevant = viewModel.activeProfile()?.protocol == protocol &&
            (state.connectionState == VpnConnectionState.CONNECTING ||
                state.connectionState == VpnConnectionState.ERROR ||
                state.connectionState == VpnConnectionState.CONNECTED)
        if (isRelevant && !detail.isNullOrBlank()) {
            binding.textConnectingDetail.text = detail
            binding.textConnectingDetail.visibility = View.VISIBLE
        } else {
            binding.textConnectingDetail.visibility = View.GONE
        }
    }

    private fun updateProfileCard() {
        val profile = viewModel.activeProfile()
        if (profile != null) {
            binding.textProfileName.text = profile.name
            binding.textProfileProtocol.text = profile.protocol.name
        } else {
            binding.textProfileName.text = getString(R.string.no_active_profile)
            binding.textProfileProtocol.text = ""
        }
        val selectedCount = viewModel.currentSelectedAppsCount()
        binding.textSplitTunnelMode.text = if (selectedCount > 0) {
            getString(R.string.split_tunnel_mode_selected)
        } else {
            getString(R.string.split_tunnel_mode_all)
        }
        binding.textSelectedAppsCount.text = getString(R.string.selected_apps_count_hint, selectedCount)
    }

    private fun render(configLoaded: Boolean, state: VpnConnectionState, selectedAppsCount: Int) {
        val context = requireContext()

        binding.textStatus.text = when (state) {
            VpnConnectionState.CONNECTED -> getString(R.string.status_connected)
            VpnConnectionState.CONNECTING -> getString(R.string.status_connecting)
            VpnConnectionState.DISCONNECTING -> getString(R.string.status_disconnecting)
            VpnConnectionState.ERROR -> getString(R.string.status_error)
            else -> if (configLoaded) getString(R.string.status_config_loaded) else getString(R.string.status_config_not_loaded)
        }

        val isConnected = state == VpnConnectionState.CONNECTED
        val isConnecting = state == VpnConnectionState.CONNECTING
        val isDisconnecting = state == VpnConnectionState.DISCONNECTING

        if (!isConnecting && state != VpnConnectionState.ERROR) {
            binding.textConnectingDetail.visibility = View.GONE
        }

        if (isConnecting) {
            if (ringSpinnerAnimator == null) {
                binding.connectRingSpinner.visibility = View.VISIBLE
                ringSpinnerAnimator = ObjectAnimator.ofFloat(binding.connectRingSpinner, View.ROTATION, 0f, 360f).apply {
                    duration = 1200
                    repeatCount = ObjectAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    start()
                }
            }
        } else {
            ringSpinnerAnimator?.cancel()
            ringSpinnerAnimator = null
            binding.connectRingSpinner.visibility = View.GONE
        }

        binding.connectCircleBg.setBackgroundResource(
            if (isConnected) R.drawable.bg_connect_circle else R.drawable.bg_connect_circle_off
        )
        binding.connectCircleIcon.setColorFilter(
            ContextCompat.getColor(context, if (isConnected) android.R.color.white else R.color.text_secondary)
        )

        // Во время "Отключение..." кнопку временно блокируем — иначе клик
        // выглядит "без ответа", хотя запрос уже в процессе. Раньше это
        // состояние не отличалось визуально от "Подключение...", из-за чего
        // казалось, что нажатие "Отменить" ничего не делает несколько секунд.
        binding.buttonToggleConnection.isEnabled = !isDisconnecting
        binding.buttonToggleConnection.text = when {
            isConnected -> getString(R.string.button_stop)
            isConnecting -> getString(R.string.button_cancel_connecting)
            isDisconnecting -> getString(R.string.button_disconnecting)
            else -> getString(R.string.button_start)
        }

        binding.textSplitTunnelMode.text = if (selectedAppsCount > 0) {
            getString(R.string.split_tunnel_mode_selected)
        } else {
            getString(R.string.split_tunnel_mode_all)
        }
        binding.textSelectedAppsCount.text = getString(R.string.selected_apps_count_hint, selectedAppsCount)
    }

    /** Иконка и подпись у каждой ячейки не меняются — задаём один раз. */
    private fun setupStatCells() {
        binding.statIp.statIcon.setImageResource(R.drawable.ic_pin)
        binding.statIp.statLabel.text = getString(R.string.stats_label_ip)

        binding.statPing.statIcon.setImageResource(R.drawable.ic_ping)
        binding.statPing.statLabel.text = getString(R.string.stats_label_ping)

        binding.statReceived.statIcon.setImageResource(R.drawable.ic_download)
        binding.statReceived.statLabel.text = getString(R.string.stats_label_received)

        binding.statSent.statIcon.setImageResource(R.drawable.ic_upload)
        binding.statSent.statLabel.text = getString(R.string.stats_label_sent)
    }

    /** Показывает карточку статистики, только когда есть что показать
     *  (после реального подключения) — не выводим нули/прочерки как будто
     *  это реальные данные. */
    private fun renderStats(stats: ConnectionStats) {
        val hasTraffic = stats.bytesReceived > 0 || stats.bytesSent > 0
        val hasAnyData = stats.publicIp != null || stats.pingMillis != null || hasTraffic
        binding.cardStats.visibility = if (hasAnyData) View.VISIBLE else View.GONE
        if (!hasAnyData) return

        binding.statIp.statValue.text = stats.publicIp ?: "—"
        binding.statPing.statValue.text = stats.pingMillis?.let { getString(R.string.stats_ping_value_format, it) } ?: "—"

        // Трафик показываем только если реально есть данные (например, для
        // IKEv2 сейчас нет подтверждённого API счётчиков байт — не показываем
        // пустую строку "0 КБ", которая выглядела бы как настоящий ноль
        // трафика, а не как "данных нет").
        binding.rowTraffic.visibility = if (hasTraffic) View.VISIBLE else View.GONE
        if (hasTraffic) {
            binding.statReceived.statValue.text = formatBytes(stats.bytesReceived)
            binding.statSent.statValue.text = formatBytes(stats.bytesSent)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 КБ"
        val kb = bytes / 1024.0
        return if (kb < 1024) {
            "${kb.roundToInt()} КБ"
        } else {
            val mb = kb / 1024.0
            "%.1f МБ".format(mb)
        }
    }

    private fun onToggleConnectionClicked() {
        val currentState = viewModel.uiState.value.connectionState
        val isActiveOrConnecting = currentState == VpnConnectionState.CONNECTED || currentState == VpnConnectionState.CONNECTING
        if (isActiveOrConnecting) {
            // Работает и как обычный "Стоп", и как отмена зависшего "Подключение..."
            viewModel.toggleConnection(true)
            return
        }
        if (!viewModel.uiState.value.configLoaded) {
            binding.root.showTopSnackbar(R.string.error_config_not_loaded)
            return
        }

        if (viewModel.activeProfileUsesOwnVpnServicePermissionFlow()) {
            val prepareIntent = VpnService.prepare(requireContext())
            if (prepareIntent != null) {
                (requireActivity() as MainActivity).requestVpnPermission(prepareIntent) {
                    viewModel.toggleConnection(false)
                }
            } else {
                viewModel.toggleConnection(false)
            }
        } else {
            viewModel.toggleConnection(false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ringSpinnerAnimator?.cancel()
        ringSpinnerAnimator = null
        _binding = null
    }
}
