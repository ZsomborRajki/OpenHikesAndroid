plugins {
    id("openhikes.android.library.compose")
    id("openhikes.hilt")
    id("openhikes.lint")
}

android {
    namespace = "app.openhikes.core.map"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.maplibre.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(projects.core.testing)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
