package com.amneziaclient.simple.data

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Список поддерживаемых языков интерфейса и переключение между ними.
 *
 * Механизм — AppCompatDelegate.setApplicationLocales() (AppCompat 1.6.0+):
 * персистентность выбора между запусками обеспечивается самим AppCompat
 * (через android.app.LocaleManager на Android 13+, и через собственное
 * хранилище AppCompat на более старых версиях) — своё SharedPreferences для
 * этого заводить не нужно.
 *
 * Пока пользователь ничего не выбрал явно (AppCompatDelegate.getApplicationLocales()
 * пустой), приложение следует системному языку телефона — обычное
 * Android-разрешение ресурсов само подхватит нужную values-XX/ папку, а если
 * системный язык не входит в число поддерживаемых — тихо откатится на
 * английский (это теперь язык по умолчанию в values/strings.xml без
 * квалификатора). Никакой дополнительной логики "иначе английский" в коде не
 * нужно — это следствие того, как устроены ресурсы.
 */
object LocaleHelper {

    data class SupportedLocale(
        val tag: String,
        val nativeName: String,
        val englishName: String
    )

    /** Английский — первый в списке (язык по умолчанию), дальше в порядке,
     *  как попросили. */
    val SUPPORTED_LOCALES: List<SupportedLocale> = listOf(
        SupportedLocale("en", "English", "English"),
        SupportedLocale("zh", "中文", "Chinese"),
        SupportedLocale("hi", "हिन्दी", "Hindi"),
        SupportedLocale("es", "Español", "Spanish"),
        SupportedLocale("ar", "العربية", "Arabic"),
        SupportedLocale("fr", "Français", "French"),
        SupportedLocale("bn", "বাংলা", "Bengali"),
        SupportedLocale("pt", "Português", "Portuguese"),
        SupportedLocale("ru", "Русский", "Russian"),
        SupportedLocale("ur", "اردو", "Urdu")
    )

    /** null — явного выбора нет, приложение следует системному языку. */
    fun currentTag(): String? {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return null
        return locales[0]?.language
    }

    fun applyLocale(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
}
