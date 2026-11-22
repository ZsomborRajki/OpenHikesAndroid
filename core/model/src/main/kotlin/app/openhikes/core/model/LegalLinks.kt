package app.openhikes.core.model

import java.util.Locale

/**
 * Canonical privacy, terms, and repository URLs aligned with OpenHikes legal pages.
 */
object LegalLinks {
    private const val BASE_URL = "https://zsomborrajki.github.io/OpenHikes"
    private val supportedTranslatedLanguages = setOf("de", "es", "fr", "it", "pl", "ro", "ru", "uk")

    const val GITHUB_REPOSITORY_URL = "https://github.com/ZsomborRajki/OpenHikesAndroid"

    fun privacyPolicyUrl(languageTag: String?): String = "${localizedBasePath(languageTag)}/privacy/"

    fun termsUrl(languageTag: String?): String = "${localizedBasePath(languageTag)}/terms/"

    private fun localizedBasePath(languageTag: String?): String {
        val normalized = languageTag?.trim().orEmpty()
        if (normalized.isEmpty()) return BASE_URL
        val code = Locale.forLanguageTag(normalized).language.lowercase(Locale.ROOT)
        return if (code in supportedTranslatedLanguages) {
            "$BASE_URL/$code"
        } else {
            BASE_URL
        }
    }
}
