plugins {
    id("openhikes.android.library.compose")
    id("openhikes.hilt")
    id("openhikes.lint")
}

android {
    namespace = "app.openhikes.core.testing.android"
}

dependencies {
    api(projects.core.testing)
    api(projects.core.ui)
    api(libs.androidx.test.runner)
    api(libs.androidx.test.ext.junit)
    api(libs.androidx.test.espresso.core)
    api(libs.androidx.compose.ui.test.junit4)
    api(libs.androidx.compose.ui.test.junit4.accessibility)
    api(libs.hilt.android.testing)
}
