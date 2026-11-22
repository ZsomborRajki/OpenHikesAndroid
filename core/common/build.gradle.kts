plugins {
    id("openhikes.jvm.library")
    id("openhikes.lint")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}
