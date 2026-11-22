package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LegalLinksTest {
    @Test
    fun resolvesEnglishRootForEnglishAndUnsupportedLocales() {
        assertThat(LegalLinks.privacyPolicyUrl(null))
            .isEqualTo("https://zsomborrajki.github.io/OpenHikes/privacy/")
        assertThat(LegalLinks.privacyPolicyUrl("en-US"))
            .isEqualTo("https://zsomborrajki.github.io/OpenHikes/privacy/")
        assertThat(LegalLinks.termsUrl("ja-JP"))
            .isEqualTo("https://zsomborrajki.github.io/OpenHikes/terms/")
    }

    @Test
    fun resolvesTranslatedPathsForSupportedLanguagesIncludingRegionalVariants() {
        val expectations = mapOf(
            "de" to "de",
            "de-AT" to "de",
            "fr-CA" to "fr",
            "es-MX" to "es",
            "it-CH" to "it",
            "pl-PL" to "pl",
            "ro-MD" to "ro",
            "ru-RU" to "ru",
            "uk-UA" to "uk"
        )

        expectations.forEach { (tag, code) ->
            assertThat(LegalLinks.privacyPolicyUrl(tag))
                .isEqualTo("https://zsomborrajki.github.io/OpenHikes/$code/privacy/")
            assertThat(LegalLinks.termsUrl(tag))
                .isEqualTo("https://zsomborrajki.github.io/OpenHikes/$code/terms/")
        }
    }

    @Test
    fun exposesAndroidGitHubRepositoryUrl() {
        assertThat(LegalLinks.GITHUB_REPOSITORY_URL)
            .isEqualTo("https://github.com/ZsomborRajki/OpenHikesAndroid")
    }
}
