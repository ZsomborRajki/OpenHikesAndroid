plugins {
    id("openhikes.android.feature")
    id("openhikes.android.screenshot")
}

android {
    namespace = "app.openhikes.feature.walks"
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.map)
}
