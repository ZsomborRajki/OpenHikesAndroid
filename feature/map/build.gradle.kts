plugins {
    id("openhikes.android.feature")
    id("openhikes.android.screenshot")
}

android {
    namespace = "app.openhikes.feature.map"
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.map)
}
