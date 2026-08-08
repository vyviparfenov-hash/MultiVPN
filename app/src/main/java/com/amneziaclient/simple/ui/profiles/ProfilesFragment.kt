package com.amneziaclient.simple.ui.profiles

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.amneziaclient.simple.R
import com.amneziaclient.simple.ui.BottomSheetOption
import com.amneziaclient.simple.ui.showOptionsBottomSheet
import com.amneziaclient.simple.ui.showTopSnackbar
import com.amneziaclient.simple.data.VpnConnectionState
import com.amneziaclient.simple.databinding.FragmentProfilesBinding
import com.amneziaclient.simple.ui.main.MainViewModel
import com.amneziaclient.simple.vpn.manager.StoredProfile
import com.amneziaclient.simple.vpn.plugin.VpnPlugin
import com.amneziaclient.simple.vpn.plugin.VpnProtocolType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class ProfilesFragment : Fragment() {

    private var _binding: FragmentProfilesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: ProfileListAdapter
    private val searchQuery = MutableStateFlow("")

    private enum class SelectionAction { EXPORT, DELETE }
    private val selectionAction = MutableStateFlow<SelectionAction?>(null)
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())

    private val configPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handlePickedConfig(it) } }

    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val text = result.data?.getStringExtra(
                com.amneziaclient.simple.ui.qrscan.QrScanActivity.EXTRA_RESULT_TEXT
            )
            if (!text.isNullOrBlank()) viewModel.onQrScanned(text)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ProfileListAdapter(
            onClick = { profile -> viewModel.setActiveProfile(profile.id) },
            onEdit = { profile -> showEditProfileDialog(profile) },
            onDelete = { profile -> viewModel.deleteProfile(profile.id) },
            onExport = { profile -> exportSingleProfile(profile) },
            onToggleSelect = { profile ->
                selectedIds.value = if (profile.id in selectedIds.value) {
                    selectedIds.value - profile.id
                } else {
                    selectedIds.value + profile.id
                }
            }
        )
        binding.recyclerProfiles.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerProfiles.adapter = adapter

        binding.fabAddProfile.setOnClickListener {
            when (selectionAction.value) {
                SelectionAction.EXPORT -> exportSelectedProfiles()
                SelectionAction.DELETE -> confirmDeleteSelectedProfiles()
                null -> showAddProfileOptions()
            }
        }

        binding.buttonSearch.setOnClickListener {
            val show = !binding.searchInput.isVisible
            binding.searchInput.isVisible = show
            if (!show) {
                binding.searchInput.text?.clear()
            }
        }
        binding.searchInput.doAfterTextChanged { searchQuery.value = it?.toString().orEmpty() }

        binding.buttonMore.setOnClickListener {
            if (selectionAction.value != null) exitSelectionMode() else showTopOverflowMenu(it)
        }

        // Свайп-назад/кнопка "назад" во время выбора профилей (экспорт или
        // удаление) должен просто завершать выбор, а не сворачивать/закрывать
        // приложение целиком.
        val backCallback = requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, enabled = false) {
            exitSelectionMode()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                selectionAction.collect { backCallback.isEnabled = it != null }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        combine(viewModel.profiles, viewModel.activeProfileFlow, viewModel.uiState, searchQuery) { profiles, active, uiState, query ->
                            profiles.filter { it.name.contains(query, ignoreCase = true) }
                                .map { profile -> Triple(profile, profile.id == active?.id, uiState.connectionState) }
                        },
                        selectionAction,
                        selectedIds
                    ) { baseRows, selAction, selIds ->
                        baseRows.map { (profile, isActive, connectionState) ->
                            val isConnected = isActive && connectionState == VpnConnectionState.CONNECTED
                            ProfileRow(
                                profile = profile,
                                isActive = isActive,
                                isConnected = isConnected,
                                isSelectionMode = selAction != null,
                                isSelected = profile.id in selIds
                            )
                        }
                    }.collect { rows ->
                        adapter.submitList(rows)
                        binding.textEmpty.isVisible = rows.isEmpty()
                        binding.recyclerProfiles.isVisible = rows.isNotEmpty()
                    }
                }
                launch {
                    combine(selectionAction, selectedIds) { selAction, selIds -> selAction to selIds.size }
                        .collect { (selAction, selCount) -> updateTopBarForSelection(selAction, selCount) }
                }
                launch {
                    viewModel.oneTimeError.collect { message ->
                        message?.let { binding.root.showTopSnackbar(it); viewModel.consumeError() }
                    }
                }
            }
        }
    }

    private fun showTopOverflowMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 0, 0, R.string.add_profile_title)
        popup.menu.add(0, 2, 1, R.string.action_delete_profiles)
        popup.menu.add(0, 1, 2, R.string.action_export_profiles)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> showAddProfileOptions()
                1 -> enterSelectionMode(SelectionAction.EXPORT)
                2 -> enterSelectionMode(SelectionAction.DELETE)
            }
            true
        }
        popup.show()
    }

    private fun enterSelectionMode(action: SelectionAction) {
        if (binding.searchInput.isVisible) {
            binding.searchInput.isVisible = false
            binding.searchInput.text?.clear()
        }
        selectedIds.value = emptySet()
        selectionAction.value = action
    }

    private fun exitSelectionMode() {
        selectionAction.value = null
        selectedIds.value = emptySet()
    }

    /** Плавно переключает верхнюю панель между обычным и режимом выбора
     *  профилей (экспорт или удаление) — крестик вместо "троеточия", стрелка
     *  вниз/мусорка вместо "плюсика", заголовок показывает счётчик выбранного. */
    private fun updateTopBarForSelection(action: SelectionAction?, selectedCount: Int) {
        val inSelectionMode = action != null
        binding.textScreenTitle.animate().alpha(0f).setDuration(100).withEndAction {
            binding.textScreenTitle.text = if (inSelectionMode) {
                getString(R.string.selection_count_format, selectedCount)
            } else {
                getString(R.string.nav_profiles)
            }
            binding.textScreenTitle.animate().alpha(1f).setDuration(100).start()
        }.start()

        binding.buttonMore.setImageResource(if (inSelectionMode) R.drawable.ic_close else R.drawable.ic_more_vert)
        binding.buttonSearch.isVisible = !inSelectionMode

        val fabIcon = when (action) {
            SelectionAction.EXPORT -> R.drawable.ic_download
            SelectionAction.DELETE -> R.drawable.ic_delete
            null -> R.drawable.ic_add
        }
        if (binding.fabAddProfile.tag != fabIcon) {
            binding.fabAddProfile.tag = fabIcon
            binding.fabAddProfile.animate().scaleX(0f).scaleY(0f).setDuration(120).withEndAction {
                binding.fabAddProfile.setImageResource(fabIcon)
                binding.fabAddProfile.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
        }
    }

    private fun confirmDeleteSelectedProfiles() {
        val ids = selectedIds.value
        if (ids.isEmpty()) {
            binding.root.showTopSnackbar(R.string.export_profiles_empty)
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.action_delete_profiles)
            .setMessage(getString(R.string.confirm_delete_profiles_message, ids.size))
            .setPositiveButton(R.string.action_delete_active) { dialog, _ ->
                ids.forEach { id -> viewModel.deleteProfile(id) }
                exitSelectionMode()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun exportSingleProfile(profile: StoredProfile) {
        viewLifecycleOwner.lifecycleScope.launch {
            exportProfiles(listOf(profile))
        }
    }

    private fun exportSelectedProfiles() {
        val ids = selectedIds.value
        if (ids.isEmpty()) {
            binding.root.showTopSnackbar(R.string.export_profiles_empty)
            return
        }
        val profiles = viewModel.profiles.value.filter { it.id in ids }
        viewLifecycleOwner.lifecycleScope.launch {
            exportProfiles(profiles)
            exitSelectionMode()
        }
    }

    /** Расширение под "родной" формат протокола — важно для того, чтобы
     *  экспортированный файл потом можно было открыть/переимпортировать. */
    private fun extensionFor(protocol: VpnProtocolType): String = when (protocol) {
        VpnProtocolType.AMNEZIAWG, VpnProtocolType.WIREGUARD -> "conf"
        VpnProtocolType.OPENVPN -> "ovpn"
        VpnProtocolType.VLESS -> "txt"
        VpnProtocolType.IKEV2 -> "sswan"
        VpnProtocolType.L2TP -> "l2tp"
        VpnProtocolType.SSTP -> "sstp"
        VpnProtocolType.SOFTETHER -> "softether"
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().ifBlank { "profile" }

    /** Имя файла = имя профиля. Если такое имя уже занято в этом же экспорте
     *  — добавляем номер, как это обычно делают файловые менеджеры
     *  ("profile (2).conf"), а не мешаем с датой прямо в имени. */
    private fun uniqueFileName(baseName: String, extension: String, usedNames: MutableSet<String>): String {
        var candidate = "$baseName.$extension"
        var suffix = 2
        while (candidate.lowercase() in usedNames) {
            candidate = "$baseName ($suffix).$extension"
            suffix++
        }
        usedNames += candidate.lowercase()
        return candidate
    }

    /** Один профиль — сохраняем как есть, одним "родным" файлом. Несколько —
     *  упаковываем в .zip с отдельным файлом на каждый профиль, чтобы потом
     *  можно было распаковать и заново импортировать любой из них по
     *  отдельности (раньше все профили склеивались в один файл с
     *  заголовками — то, что мы туда добавляли, ломало повторный импорт,
     *  плюс не все протоколы вообще умели читать файл обратно — заодно
     *  починил это для L2TP/SSTP/VLESS). */
    private suspend fun exportProfiles(profiles: List<StoredProfile>) {
        val usedNames = mutableSetOf<String>()
        val files = mutableListOf<Pair<String, ByteArray>>()
        for (profile in profiles) {
            val content = viewModel.exportProfile(profile) ?: continue
            val filename = uniqueFileName(sanitizeFileName(profile.name), extensionFor(profile.protocol), usedNames)
            files += filename to content.toByteArray()
        }
        if (files.isEmpty()) {
            binding.root.showTopSnackbar(R.string.export_profiles_error)
            return
        }

        val success = if (files.size == 1) {
            val (filename, bytes) = files.first()
            saveToDownloads(filename, bytes, mimeTypeFor(filename))
        } else {
            val zipName = "MultiVPN_profiles_${
                java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).format(java.util.Date())
            }.zip"
            val zipBytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                java.io.ByteArrayOutputStream().use { bos ->
                    java.util.zip.ZipOutputStream(bos).use { zos ->
                        files.forEach { (filename, bytes) ->
                            zos.putNextEntry(java.util.zip.ZipEntry(filename))
                            zos.write(bytes)
                            zos.closeEntry()
                        }
                    }
                    bos.toByteArray()
                }
            }
            saveToDownloads(zipName, zipBytes, "application/zip")?.let { zipName }
        }

        if (success != null) {
            binding.root.showTopSnackbar(getString(R.string.export_profiles_success, success))
        } else {
            binding.root.showTopSnackbar(R.string.export_profiles_error)
        }
    }

    private fun mimeTypeFor(filename: String): String = when (filename.substringAfterLast('.', "")) {
        "zip" -> "application/zip"
        "json" -> "application/json"
        else -> "text/plain"
    }

    /** Кладёт файл в публичную папку "Загрузки" через MediaStore (без
     *  разрешений на Android 10+) — тот же приём, что и для экспорта лога
     *  (VpnDebugLog). Возвращает итоговое имя файла (на Android 10+ система
     *  сама переименует при совпадении, например "name (1).conf") либо
     *  null при ошибке. */
    private suspend fun saveToDownloads(filename: String, bytes: ByteArray, mimeType: String): String? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val resolver = requireContext().contentResolver
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return@runCatching null
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    values.clear()
                    values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    filename
                } else {
                    @Suppress("DEPRECATION")
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    downloadsDir.mkdirs()
                    // На старых версиях система сама не переименовывает при
                    // совпадении — делаем это вручную.
                    var target = java.io.File(downloadsDir, filename)
                    var suffix = 2
                    val base = filename.substringBeforeLast('.')
                    val ext = filename.substringAfterLast('.', "")
                    while (target.exists()) {
                        target = java.io.File(downloadsDir, "$base ($suffix).$ext")
                        suffix++
                    }
                    target.writeBytes(bytes)
                    target.name
                }
            }.getOrNull()
        }

    /** Первый шаг добавления профиля: файл или ручной ввод. */
    private fun showAddProfileOptions() {
        showOptionsBottomSheet(
            context = requireContext(),
            title = getString(R.string.add_profile_title),
            options = listOf(
                BottomSheetOption(
                    iconRes = R.drawable.ic_upload,
                    title = getString(R.string.button_load_config),
                    subtitle = getString(R.string.button_load_config_subtitle),
                    onClick = { configPickerLauncher.launch(arrayOf("*/*")) }
                ),
                BottomSheetOption(
                    iconRes = R.drawable.ic_edit,
                    title = getString(R.string.button_manual_entry),
                    subtitle = getString(R.string.button_manual_entry_subtitle),
                    onClick = { showManualEntryProtocolDialog() }
                ),
                BottomSheetOption(
                    iconRes = R.drawable.ic_qr_code,
                    title = getString(R.string.button_scan_qr),
                    subtitle = getString(R.string.button_scan_qr_subtitle),
                    onClick = {
                        qrScanLauncher.launch(
                            Intent(requireContext(), com.amneziaclient.simple.ui.qrscan.QrScanActivity::class.java)
                        )
                    }
                )
            )
        )
    }

    private fun handlePickedConfig(uri: Uri) {
        val displayName = queryDisplayName(uri)
        val extension = displayName?.substringAfterLast(".", "").orEmpty()

        if (extension.equals("zip", ignoreCase = true)) {
            handlePickedZip(uri)
            return
        }

        val text = runCatching {
            requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()

        if (text == null) {
            binding.root.showTopSnackbar(R.string.error_file_read)
            return
        }

        val suggestedName = displayName?.substringBeforeLast(".") ?: "VPN"

        val input = EditText(requireContext()).apply {
            setText(suggestedName)
            setSelection(suggestedName.length)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_profile_name_title)
            .setView(input)
            .setPositiveButton(R.string.action_save) { dialog, _ ->
                val name = input.text.toString().ifBlank { suggestedName }
                viewModel.onConfigFilePicked(name, extension, text)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** .zip — это результат нашего же множественного экспорта: отдельный
     *  "чистый" файл на каждый профиль внутри архива, без общего заголовка.
     *  Импортируем все файлы из архива сразу, без диалога переименования на
     *  каждый (иначе для 10 профилей пришлось бы 10 раз нажимать "Сохранить") —
     *  имя профиля берём из имени файла внутри архива. */
    private fun handlePickedZip(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val bytes = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@runCatching emptyList()
                    val result = mutableListOf<Pair<String, String>>()
                    java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val content = zis.readBytes().toString(Charsets.UTF_8)
                                result += entry.name to content
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                    result
                }.getOrDefault(emptyList())
            }

            if (entries.isEmpty()) {
                binding.root.showTopSnackbar(R.string.error_file_read)
                return@launch
            }

            var successCount = 0
            for ((entryName, content) in entries) {
                val name = entryName.substringBeforeLast(".").substringAfterLast("/")
                val entryExtension = entryName.substringAfterLast(".", "")
                if (viewModel.importConfigFile(name, entryExtension, content)) successCount++
            }

            binding.root.showTopSnackbar(
                getString(R.string.import_zip_result, successCount, entries.size)
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = requireContext().contentResolver.query(uri, null, null, null, null)
            val nameIndex = cursor?.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME) ?: -1
            if (cursor != null && nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        } catch (e: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    /** Открывает форму редактирования уже существующего профиля,
     *  предзаполненную текущими значениями (разобранными из configBlob). */
    private fun showEditProfileDialog(profile: StoredProfile) {
        val plugin = viewModel.availableProtocolsForManualEntry().find { it.protocol == profile.protocol }
        if (plugin == null) {
            binding.root.showTopSnackbar(R.string.error_no_protocols_available)
            return
        }
        val initialFields = viewModel.fieldsForEditing(profile)
        showManualEntryFieldsDialog(plugin, editingProfileId = profile.id, initialFields = initialFields)
    }

    private fun showManualEntryProtocolDialog() {
        val plugins = viewModel.availableProtocolsForManualEntry()
        if (plugins.isEmpty()) {
            binding.root.showTopSnackbar(R.string.error_no_protocols_available)
            return
        }
        showOptionsBottomSheet(
            context = requireContext(),
            title = getString(R.string.dialog_choose_protocol_title),
            options = plugins.map { plugin ->
                BottomSheetOption(
                    iconRes = R.drawable.ic_nav_profiles,
                    title = plugin.displayName,
                    subtitle = getString(R.string.dialog_choose_protocol_subtitle),
                    onClick = { showManualEntryFieldsDialog(plugin) }
                )
            }
        )
    }

    private fun manualFieldsFor(protocol: VpnProtocolType): List<Pair<String, String>> = when (protocol) {
        VpnProtocolType.AMNEZIAWG, VpnProtocolType.WIREGUARD -> listOf(
            "name" to "Название профиля",
            "PrivateKey" to "Private Key",
            "Address" to "Address (например 10.0.0.2/32)",
            "DNS" to "DNS (необязательно)",
            "PublicKey" to "Public Key сервера",
            "PresharedKey" to "Preshared Key (необязательно)",
            "AllowedIPs" to "Allowed IPs (например 0.0.0.0/0)",
            "Endpoint" to "Endpoint (сервер:порт)",
            "PersistentKeepalive" to "Persistent Keepalive (необязательно)",
            // Специфичные для AmneziaWG поля обфускации — для обычного
            // WireGuard-профиля просто оставьте пустыми. Если их потерять
            // при редактировании уже импортированного AmneziaWG-профиля,
            // движок считает такую конфигурацию повреждённой.
            "Jc" to "Jc (AmneziaWG, необязательно)",
            "Jmin" to "Jmin (AmneziaWG, необязательно)",
            "Jmax" to "Jmax (AmneziaWG, необязательно)",
            "S1" to "S1 (AmneziaWG, необязательно)",
            "S2" to "S2 (AmneziaWG, необязательно)",
            "H1" to "H1 (AmneziaWG, необязательно)",
            "H2" to "H2 (AmneziaWG, необязательно)",
            "H3" to "H3 (AmneziaWG, необязательно)",
            "H4" to "H4 (AmneziaWG, необязательно)"
        )
        VpnProtocolType.IKEV2 -> listOf(
            "name" to "Название профиля",
            "server" to "Адрес сервера",
            "username" to "Имя пользователя",
            "password" to "Пароль",
            "cert" to "CA-сертификат сервера (необязательно, для самоподписанных серверов — вставьте текст .pem файла целиком, включая -----BEGIN CERTIFICATE-----)"
        )
        VpnProtocolType.L2TP -> listOf(
            "name" to "Название профиля",
            "server" to "Адрес сервера",
            "username" to "Имя пользователя",
            "password" to "Пароль",
            "psk" to "Pre-shared key (IPsec, необязательно)"
        )
        VpnProtocolType.OPENVPN -> listOf(
            "name" to "Название профиля",
            "ovpnContent" to "Содержимое .ovpn-файла (вставьте текст целиком)",
            "username" to "Имя пользователя (необязательно, если не встроено в конфиг)",
            "password" to "Пароль (необязательно)"
        )
        VpnProtocolType.VLESS -> listOf(
            "name" to "Название профиля",
            "vlessLink" to "Ссылка vless://... (выдаёт панель сервера)"
        )
        VpnProtocolType.SSTP -> listOf(
            "name" to "Название профиля",
            "server" to "Адрес сервера",
            "port" to "Порт (по умолчанию 443)",
            "username" to "Имя пользователя",
            "password" to "Пароль",
            "cert" to "Сертификат сервера (.pem/.crt/.der как текст, необязательно — для самоподписанных серверов)",
            "insecure" to "Не проверять совпадение имени хоста (yes/no, необязательно)"
        )
        else -> listOf("name" to "Название профиля")
    }

    /** [editingProfileId] == null -> добавление нового профиля;
     *  иначе -> редактирование существующего (тот же id, поля предзаполнены). */
    private fun showManualEntryFieldsDialog(
        plugin: VpnPlugin,
        editingProfileId: String? = null,
        initialFields: Map<String, String> = emptyMap()
    ) {
        val fieldKeys = manualFieldsFor(plugin.protocol)
        val editTexts = LinkedHashMap<String, EditText>()

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        fieldKeys.forEach { (key, label) ->
            val editText = EditText(requireContext()).apply {
                hint = label
                gravity = Gravity.START
                if (key == "cert" || key == "ovpnContent") {
                    // Многострочное поле — сюда вставляется целиком текст
                    // .pem-сертификата (IKEv2) или .ovpn-конфига (OpenVPN).
                    minLines = 3
                    maxLines = 8
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                }
                initialFields[key]?.let { setText(it) }
            }
            editTexts[key] = editText
            container.addView(editText)
        }
        val scroll = ScrollView(requireContext()).apply { addView(container) }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (editingProfileId != null) getString(R.string.dialog_edit_profile_title) else plugin.displayName)
            .setView(scroll)
            .setPositiveButton(R.string.action_save) { dialog, _ ->
                val fields = editTexts.mapValues { it.value.text.toString() }
                if (editingProfileId != null) {
                    viewModel.updateProfileFromEdit(editingProfileId, plugin.protocol, fields)
                } else {
                    viewModel.importManualProfile(plugin.protocol, fields)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
