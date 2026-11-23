plugins {
    id("openhikes.android.library")
    id("openhikes.hilt")
    id("openhikes.lint")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.openhikes.core.network"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    api(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(projects.core.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
