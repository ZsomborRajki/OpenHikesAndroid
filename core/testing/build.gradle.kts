plugins {
    id("openhikes.jvm.library")
    id("openhikes.lint")
}

dependencies {
    api(projects.core.common)
    api(projects.core.model)
    api(libs.junit4)
    api(libs.truth)
    api(libs.turbine)
    api(libs.kotlinx.coroutines.test)
}
