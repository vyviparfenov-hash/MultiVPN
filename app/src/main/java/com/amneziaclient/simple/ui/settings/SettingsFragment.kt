package com.amneziaclient.simple.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.amneziaclient.simple.R
import com.amneziaclient.simple.data.AppSettingsRepository
import com.amneziaclient.simple.databinding.FragmentSettingsBinding
import com.amneziaclient.simple.ui.showTopSnackbar
import com.amneziaclient.simple.vpn.VpnDebugLog
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var appSettingsRepository: AppSettingsRepository

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val versionName = runCatching {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrNull() ?: "—"
        binding.textAppVersion.text = getString(R.string.settings_version_format, versionName)

        binding.switchAutoConnectOnOpen.isChecked = appSettingsRepository.isAutoConnectOnOpenEnabled()
        binding.switchAutoConnectOnOpen.setOnCheckedChangeListener { _, isChecked ->
            appSettingsRepository.setAutoConnectOnOpenEnabled(isChecked)
        }

        binding.switchConnectionLogging.isChecked = appSettingsRepository.isConnectionLoggingEnabled()
        // Начальное состояние — без анимации (иначе панель "выезжает" при
        // самом первом открытии экрана, что выглядит как лишний глюк).
        binding.panelConnectionLoggingExport.visibility =
            if (binding.switchConnectionLogging.isChecked) View.VISIBLE else View.GONE
        binding.switchConnectionLogging.setOnCheckedChangeListener { _, isChecked ->
            appSettingsRepository.setConnectionLoggingEnabled(isChecked)
            VpnDebugLog.setEnabled(isChecked, appSettingsRepository.connectionLoggingEnabledAt())
            // TransitionManager сам анимирует любое изменение размеров/
            // видимости внутри root между "было" и "стало" — плавное
            // раскрытие/схлопывание панели вместо мгновенного show/hide.
            TransitionManager.beginDelayedTransition(binding.root, AutoTransition())
            binding.panelConnectionLoggingExport.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        binding.buttonExportLog.setOnClickListener {
            val ctx = requireContext()
            if (!VpnDebugLog.hasLogs(ctx)) {
                binding.root.showTopSnackbar(R.string.export_log_empty, Snackbar.LENGTH_SHORT)
                return@setOnClickListener
            }
            val filename = VpnDebugLog.exportToDownloads(ctx)
            val message = if (filename != null) {
                getString(R.string.export_log_success, filename)
            } else {
                getString(R.string.export_log_error)
            }
            binding.root.showTopSnackbar(message)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
