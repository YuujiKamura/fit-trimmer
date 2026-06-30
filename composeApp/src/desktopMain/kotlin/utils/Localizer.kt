package utils

import java.util.Locale
import java.util.ResourceBundle
import java.util.PropertyResourceBundle

object Localizer {
    private const val BUNDLE_BASE_NAME = "strings"

    // Supported language definitions
    data class SupportedLanguage(
        val code: String,       // IETF Language Tag (e.g. "ja", "en", "ar-AE")
        val englishName: String,
        val localName: String,
        val locale: Locale
    )

    val supportedLanguages = listOf(
        SupportedLanguage("en", "English", "English", Locale.ENGLISH),
        SupportedLanguage("ja", "Japanese", "日本語", Locale.JAPANESE),
        SupportedLanguage("es", "Spanish", "Español", Locale("es")),
        SupportedLanguage("it", "Italian", "Italiano", Locale.ITALIAN),
        SupportedLanguage("zh", "Chinese", "中文", Locale.CHINESE),
        SupportedLanguage("ko", "Korean", "한국어", Locale.KOREAN),
        SupportedLanguage("el", "Greek", "Ελληνικά", Locale("el")),
        SupportedLanguage("ar-AE", "Arabic (UAE)", "العربية (الإمارات)", Locale("ar", "AE")),
        SupportedLanguage("fr", "French", "Français", Locale.FRENCH),
        SupportedLanguage("de", "German", "Deutsch", Locale.GERMAN),
        SupportedLanguage("pt", "Portuguese", "Português", Locale("pt")),
        SupportedLanguage("nl", "Dutch", "Nederlands", Locale("nl")),
        SupportedLanguage("ru", "Russian", "Русский", Locale("ru")),
        SupportedLanguage("pl", "Polish", "Polski", Locale("pl")),
        SupportedLanguage("th", "Thai", "ภาษาไทย", Locale("th")),
        SupportedLanguage("vi", "Vietnamese", "Tiếng Việt", Locale("vi"))
    )

    fun getLocaleFromCode(code: String): Locale {
        val matched = supportedLanguages.find { it.code.equals(code, ignoreCase = true) }
        if (matched != null) return matched.locale
        
        val parts = code.split("-", "_")
        return when (parts.size) {
            2 -> Locale(parts[0], parts[1])
            1 -> Locale(parts[0])
            else -> Locale.getDefault()
        }
    }

    /**
     * Get string for key using given locale, with fallback to default bundle.
     */
    fun get(key: String, locale: Locale): String {
        return try {
            val bundle = ResourceBundle.getBundle(BUNDLE_BASE_NAME, locale, UTF8Control())
            if (bundle.containsKey(key)) {
                bundle.getString(key)
            } else {
                val defaultBundle = ResourceBundle.getBundle(BUNDLE_BASE_NAME, Locale.ENGLISH, UTF8Control())
                if (defaultBundle.containsKey(key)) defaultBundle.getString(key) else key
            }
        } catch (e: Exception) {
            // If resource bundle loading fails, try to fallback directly to default English
            try {
                val defaultBundle = ResourceBundle.getBundle(BUNDLE_BASE_NAME, Locale.ENGLISH, UTF8Control())
                if (defaultBundle.containsKey(key)) defaultBundle.getString(key) else key
            } catch (ex: Exception) {
                key
            }
        }
    }

    /**
     * Get string for key using current settings.
     */
    fun get(key: String, languageCode: String): String {
        val locale = if (languageCode.isEmpty()) Locale.getDefault() else getLocaleFromCode(languageCode)
        return get(key, locale)
    }

    private class UTF8Control : ResourceBundle.Control() {
        override fun newBundle(
            baseName: String,
            locale: Locale,
            format: String,
            loader: ClassLoader,
            reload: Boolean
        ): ResourceBundle? {
            val bundleName = toBundleName(baseName, locale)
            val resourceName = toResourceName(bundleName, "properties")
            val stream = loader.getResourceAsStream(resourceName) ?: return null
            return stream.use {
                PropertyResourceBundle(java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))
            }
        }
    }
}
