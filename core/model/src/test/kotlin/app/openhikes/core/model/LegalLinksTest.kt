package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LegalLinksTest {
    @Test
    fun pointsToAndroidPrivacyPolicyTermsAndRepository() {
        assertThat(LegalLinks.privacyPolicyUrl())
            .isEqualTo("https://github.com/ZsomborRajki/OpenHikesAndroid/blob/main/docs/privacy-policy.md")
        assertThat(LegalLinks.termsUrl())
            .isEqualTo("https://github.com/ZsomborRajki/OpenHikesAndroid/blob/main/docs/terms.md")
        assertThat(LegalLinks.GITHUB_REPOSITORY_URL)
            .isEqualTo("https://github.com/ZsomborRajki/OpenHikesAndroid")
    }
}
