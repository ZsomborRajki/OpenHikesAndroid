plugins {
    id("openhikes.android.library.compose")
    id("openhikes.lint")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.openhikes.core.ui"
}

dependencies {
    api(projects.core.designsystem)
    api(projects.core.model)
    api(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
