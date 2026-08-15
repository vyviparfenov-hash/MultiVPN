package com.amneziaclient.simple.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.amneziaclient.simple.R
import com.amneziaclient.simple.data.AppSettingsRepository
import com.amneziaclient.simple.data.LocaleHelper
import com.amneziaclient.simple.databinding.FragmentSettingsBinding
import com.amneziaclient.simple.ui.BottomSheetOption
import com.amneziaclient.simple.ui.showOptionsBottomSheet
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

        refreshCurrentLanguageLabel()
        binding.rowLanguage.setOnClickListener { showLanguagePicker() }

        binding.buttonDisableBatteryOptimization.setOnClickListener { requestDisableBatteryOptimization() }
        refreshBatteryOptimizationState()

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

    override fun onResume() {
        super.onResume()
        // Пользователь мог только что вернуться из системных настроек после
        // нажатия "Отключить" — статус мог измениться, пока экран был не
        // виден (единственный надёжный момент это перепроверить, системного
        // колбэка на такое изменение нет).
        refreshBatteryOptimizationState()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = requireContext().getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(requireContext().packageName)
    }

    private fun refreshBatteryOptimizationState() {
        val exempted = isIgnoringBatteryOptimizations()
        binding.buttonDisableBatteryOptimization.text = if (exempted) {
            getString(R.string.settings_battery_status_granted)
        } else {
            getString(R.string.settings_battery_action_disable)
        }
        // Когда уже отключена — кнопка нажимать нечего, оставляем просто как
        // статус-текст (нет смысла второй раз просить у системы то же самое).
        binding.buttonDisableBatteryOptimization.isClickable = !exempted
    }

    private fun requestDisableBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${requireContext().packageName}")
            }
            startActivity(intent)
        }.onFailure {
            // На части прошивок (особенно с сильно урезанным MIUI/EMUI-style
            // энергосбережением) этого системного диалога может не быть —
            // тогда открываем общий экран настроек батареи приложения как
            // разумный fallback, а не показываем пользователю голую ошибку.
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                })
            }
        }
    }

    private fun refreshCurrentLanguageLabel() {
        // Явного выбора может не быть (currentTag() == null) — тогда
        // приложение следует системному языку; показываем ТО, что реально
        // сейчас отображается (берём из фактически применённой конфигурации
        // ресурсов), а не абстрактную надпись "Системный".
        val effectiveTag = LocaleHelper.currentTag()
            ?: resources.configuration.locales[0]?.language
        val matched = LocaleHelper.SUPPORTED_LOCALES.firstOrNull { it.tag == effectiveTag }
            ?: LocaleHelper.SUPPORTED_LOCALES.first() // английский — язык по умолчанию
        binding.textCurrentLanguage.text = matched.nativeName
    }

    private fun showLanguagePicker() {
        val options = LocaleHelper.SUPPORTED_LOCALES.map { locale ->
            BottomSheetOption(
                iconRes = R.drawable.ic_language,
                title = locale.nativeName,
                subtitle = locale.englishName.takeIf { it != locale.nativeName },
                onClick = {
                    LocaleHelper.applyLocale(locale.tag)
                    // setApplicationLocales() пересоздаёт активити само —
                    // явный recreate() тут не нужен и требовать его было бы
                    // избыточно.
                }
            )
        }
        showOptionsBottomSheet(requireContext(), getString(R.string.settings_language_picker_title), options)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
