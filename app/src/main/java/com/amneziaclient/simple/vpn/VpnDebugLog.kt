package com.amneziaclient.simple.vpn

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Логирование VPN-подключений в файл — по требованию пользователя
 * (настройки → "Включить логирование подключения"), выключено по умолчанию.
 *
 * Ограничения размера/времени (наше собственное решение, не было явно
 * запрошено, но необходимо, чтобы забытый включённым тумблер не раздувал
 * хранилище и не грузил приложение):
 *  - Ротация 2 файлами по [MAX_FILE_SIZE_BYTES] — суммарно максимум ~4 МБ
 *    независимо от того, сколько реально длится логирование.
 *  - Автоматическое отключение через [AUTO_DISABLE_AFTER_MS] (24 часа) от
 *    момента включения — типичный сценарий "включил для одного теста и
 *    забыл выключить" не должен писать логи бесконечно.
 */
object VpnDebugLog {

    private const val MAX_FILE_SIZE_BYTES = 2L * 1024 * 1024 // 2 МБ на файл, 2 файла = ~4 МБ максимум
    private const val AUTO_DISABLE_AFTER_MS = 24L * 60 * 60 * 1000 // 24 часа

    private val lock = Any()
    private var appContext: Context? = null
    private var enabled = false
    private var enabledAtMillis: Long = 0L
    private var onAutoDisabled: (() -> Unit)? = null

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context, initiallyEnabled: Boolean, enabledAt: Long, onAutoDisabled: () -> Unit) {
        appContext = context.applicationContext
        enabled = initiallyEnabled
        enabledAtMillis = enabledAt
        this.onAutoDisabled = onAutoDisabled
    }

    fun setEnabled(isEnabled: Boolean, enabledAt: Long) {
        synchronized(lock) {
            enabled = isEnabled
            enabledAtMillis = enabledAt
        }
    }

    fun log(tag: String, message: String) {
        val ctx = appContext ?: return
        synchronized(lock) {
            if (!enabled) return
            if (System.currentTimeMillis() - enabledAtMillis > AUTO_DISABLE_AFTER_MS) {
                enabled = false
                appendLine(ctx, "VpnDebugLog", "Логирование автоматически выключено через 24 часа.")
                onAutoDisabled?.invoke()
                return
            }
            appendLine(ctx, tag, message)
        }
    }

    private fun logDir(context: Context): File =
        File(context.filesDir, "vpn_logs").apply { mkdirs() }

    private fun currentFile(context: Context) = File(logDir(context), "current.log")
    private fun previousFile(context: Context) = File(logDir(context), "previous.log")

    private fun appendLine(context: Context, tag: String, message: String) {
        runCatching {
            val file = currentFile(context)
            if (file.exists() && file.length() > MAX_FILE_SIZE_BYTES) {
                previousFile(context).delete()
                file.renameTo(previousFile(context))
            }
            file.appendText("${timeFormat.format(Date())} [$tag] $message\n")
        }
    }

    /** Есть ли что экспортировать. Снимок logcat доступен всегда (не зависит
     *  от тумблера "Включить логирование подключения"), поэтому кнопка
     *  экспорта по факту почти всегда что-то даёт — но проверяем и файлы
     *  тоже, на случай если сам logcat почему-то пуст/недоступен. */
    fun hasLogs(context: Context): Boolean =
        currentFile(context).let { it.exists() && it.length() > 0 } ||
            previousFile(context).exists() ||
            captureLogcatSnapshot().isNotBlank()

    /** Сырой снимок СОБСТВЕННОГО logcat процесса (то, что само приложение
     *  и всё, что работает внутри его процесса, уже успело залогировать) —
     *  без специальных разрешений с Android 4.1 нельзя прочитать чужой
     *  logcat, но свой — можно, этим и пользуемся. Максимально близко к
     *  тому, что раньше снимали вручную через "adb logcat -d", только
     *  ограничено рамками ОДНОГО процесса (системные строки вроде "Vpn:
     *  Established by..." принадлежат process system_server, а не нам,
     *  их так не получить — это ограничение платформы, не наше). Ограничение
     *  по числу строк (-t) — чтобы не раздувать .zip до неразумного размера. */
    private fun captureLogcatSnapshot(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "8000")
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrDefault("")

    /** Собирает current.log + previous.log + свежий снимок logcat в .zip и
     *  кладёт в публичную папку "Загрузки" через MediaStore (без запроса
     *  разрешений на Android 10+; на более старых версиях — через
     *  legacy-доступ к внешнему хранилищу). Возвращает читаемое
     *  пользователем имя файла при успехе. */
    fun exportToDownloads(context: Context): String? {
        val current = currentFile(context)
        val previous = previousFile(context)
        val logcatSnapshot = captureLogcatSnapshot()
        if (!(current.exists() && current.length() > 0) && !previous.exists() && logcatSnapshot.isBlank()) {
            return null
        }

        val filename = "MultiVPN_log_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.zip"

        return runCatching {
            val zipBytes = java.io.ByteArrayOutputStream().use { bos ->
                ZipOutputStream(bos).use { zos ->
                    if (previous.exists()) {
                        zos.putNextEntry(ZipEntry("connection_log_previous.log"))
                        previous.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    if (current.exists()) {
                        zos.putNextEntry(ZipEntry("connection_log_current.log"))
                        current.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    if (logcatSnapshot.isNotBlank()) {
                        zos.putNextEntry(ZipEntry("full_app_logcat_snapshot.txt"))
                        zos.write(logcatSnapshot.toByteArray())
                        zos.closeEntry()
                    }
                }
                bos.toByteArray()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null
                resolver.openOutputStream(uri)?.use { it.write(zipBytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                File(downloadsDir, filename).writeBytes(zipBytes)
            }
            filename
        }.getOrNull()
    }
}
