import java.util.Properties

plugins {
    id("openhikes.android.application.compose")
    id("openhikes.android.screenshot")
    id("openhikes.hilt")
    id("openhikes.lint")
    alias(libs.plugins.kotlin.serialization)
}

fun readLocalSecret(key: String): String {
    val candidates = listOf(rootProject.file("secrets.properties"), rootProject.file("local.properties"))
    for (file in candidates) {
        if (!file.isFile) continue
        val props = Properties()
        file.inputStream().use { props.load(it) }
        val value = props.getProperty(key)?.trim()
        if (!value.isNullOrEmpty()) return value
    }
    return ""
}

fun String.asBuildConfigString(): String {
    val escaped = replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

android {
    namespace = "app.openhikes"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "app.openhikes"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "app.openhikes.core.testing.android.OpenHikesTestRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "STADIA_API_KEY", readLocalSecret("STADIA_API_KEY").asBuildConfigString())
            buildConfigField(
                "String",
                "THUNDERFOREST_API_KEY",
                readLocalSecret("THUNDERFOREST_API_KEY").asBuildConfigString()
            )
        }
        release {
            // Distributed APKs never embed private local developer map keys.
            buildConfigField("String", "STADIA_API_KEY", "\"\"")
            buildConfigField("String", "THUNDERFOREST_API_KEY", "\"\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
    implementation(projects.core.data)
    implementation(projects.core.database)
    implementation(projects.core.location)
    implementation(projects.core.map)
    implementation(projects.core.network)
    implementation(projects.feature.hikes)
    implementation(projects.feature.map)
    implementation(projects.feature.offlinemaps)
    implementation(projects.feature.recording)
    implementation(projects.feature.routeedit)
    implementation(projects.feature.settings)
    implementation(projects.feature.trailmaker)
    implementation(projects.feature.walks)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
    implementation(libs.androidx.window.core)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(projects.core.testing)
    testImplementation(projects.core.testingAndroid)
    testImplementation(libs.robolectric)
    androidTestImplementation(projects.core.testingAndroid)
}
