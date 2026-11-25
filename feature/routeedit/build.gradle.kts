plugins {
    id("openhikes.android.feature")
    id("openhikes.android.screenshot")
}

android {
    namespace = "app.openhikes.feature.routeedit"
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.location)
    implementation(projects.core.map)
}
