plugins {
    id("openhikes.android.feature")
    id("openhikes.android.screenshot")
}

android {
    namespace = "app.openhikes.feature.settings"
}

dependencies {
    implementation(projects.core.data)
}
