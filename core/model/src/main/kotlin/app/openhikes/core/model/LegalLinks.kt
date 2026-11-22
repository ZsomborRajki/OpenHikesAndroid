package app.openhikes.core.model

/**
 * Canonical privacy, terms, and repository URLs for OpenHikes for Android.
 */
object LegalLinks {
    const val GITHUB_REPOSITORY_URL = "https://github.com/ZsomborRajki/OpenHikesAndroid"
    const val PRIVACY_POLICY_URL = "$GITHUB_REPOSITORY_URL/blob/main/docs/privacy-policy.md"
    const val TERMS_URL = "$GITHUB_REPOSITORY_URL/blob/main/docs/terms.md"

    fun privacyPolicyUrl(): String = PRIVACY_POLICY_URL

    fun termsUrl(): String = TERMS_URL
}
