plugins {
    id("openhikes.android.library.compose")
    id("openhikes.lint")
}

android {
    namespace = "app.openhikes.core.ui"
}

dependencies {
    api(projects.core.designsystem)
    api(projects.core.model)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
