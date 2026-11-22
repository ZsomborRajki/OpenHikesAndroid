plugins {
    id("openhikes.android.library.compose")
    id("openhikes.android.screenshot")
    id("openhikes.lint")
}

android {
    namespace = "app.openhikes.core.designsystem"
}

dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
