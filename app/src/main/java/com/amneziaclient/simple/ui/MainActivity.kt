package com.amneziaclient.simple.ui

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.amneziaclient.simple.R
import com.amneziaclient.simple.data.AppSettingsRepository
import com.amneziaclient.simple.data.VpnConnectionState
import com.amneziaclient.simple.databinding.ActivityMainBinding
import com.amneziaclient.simple.ui.main.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Хост навигации: 4 экрана снизу (Главная/Профили/Приложения/Настройки),
 * переключаются и по нажатию на нижнюю навигацию, и свайпом влево/вправо —
 * через ViewPager2, синхронизированный с BottomNavigationView в обе стороны.
 * Здесь же живёт единственный на всё приложение launcher для системного
 * запроса разрешения VpnService.prepare() (AmneziaWG/WireGuard) — Fragment'ы
 * вызывают [requestVpnPermission], т.к. регистрировать launcher можно только
 * в Activity.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingVpnPermissionCallback: (() -> Unit)? = null

    private val viewModel: MainViewModel by viewModels()
    @Inject lateinit var appSettingsRepository: AppSettingsRepository

    /** Порядок ОБЯЗАН совпадать и с MainPagerAdapter, и с порядком пунктов
     *  в bottom_nav_menu.xml. */
    private val tabPositions = listOf(R.id.nav_home, R.id.nav_profiles, R.id.nav_apps, R.id.nav_settings)

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingVpnPermissionCallback?.invoke()
        } else {
            binding.root.showTopSnackbar(R.string.error_no_vpn_access)
        }
        pendingVpnPermissionCallback = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* если отклонено — сервис всё равно работает, просто без баннера разрешения */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setupPagerWithBottomNav()
        maybeAutoConnectOnOpen()
    }

    private fun setupPagerWithBottomNav() {
        binding.viewPager.adapter = MainPagerAdapter(this)
        // Каждая вкладка держит собственный Fragment "тёплым" при свайпе —
        // без этого ViewPager2 по умолчанию оставляет только 1 соседний экран,
        // чего достаточно, но для 4 лёгких вкладок разумнее держать все сразу.
        binding.viewPager.offscreenPageLimit = 3

        binding.bottomNav.setOnItemSelectedListener { item ->
            val position = tabPositions.indexOf(item.itemId)
            if (position >= 0 && binding.viewPager.currentItem != position) {
                binding.viewPager.setCurrentItem(position, true)
            }
            true
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val itemId = tabPositions.getOrNull(position) ?: return
                if (binding.bottomNav.selectedItemId != itemId) {
                    binding.bottomNav.selectedItemId = itemId
                }
            }
        })
    }

    /**
     * "Автозапуск" — подключать VPN автоматически при каждом открытии
     * приложения. Срабатывает при каждом создании MainActivity (запуск
     * приложения из лаунчера), если есть активный профиль и он ещё не
     * подключён.
     */
    private fun maybeAutoConnectOnOpen() {
        if (!appSettingsRepository.isAutoConnectOnOpenEnabled()) return
        if (viewModel.activeProfile() == null) return
        if (viewModel.uiState.value.connectionState == VpnConnectionState.CONNECTED) return

        if (viewModel.activeProfileUsesOwnVpnServicePermissionFlow()) {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                requestVpnPermission(prepareIntent) { viewModel.toggleConnection(false) }
            } else {
                viewModel.toggleConnection(false)
            }
        } else {
            viewModel.toggleConnection(false)
        }
    }

    /** Запускает системный диалог VpnService.prepare() и вызывает [onGranted]
     *  после согласия пользователя. */
    fun requestVpnPermission(intent: Intent, onGranted: () -> Unit) {
        pendingVpnPermissionCallback = onGranted
        vpnPermissionLauncher.launch(intent)
    }

    /** Переключает вкладку (нижняя навигация сама переключит ViewPager2
     *  через слушатель, настроенный в [setupPagerWithBottomNav]). */
    fun switchToTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
    }
}
