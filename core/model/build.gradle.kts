plugins {
    id("openhikes.jvm.library")
    id("openhikes.lint")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
