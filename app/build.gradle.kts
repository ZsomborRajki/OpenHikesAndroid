plugins {
    id("openhikes.android.application.compose")
    id("openhikes.android.screenshot")
    id("openhikes.hilt")
    id("openhikes.lint")
    alias(libs.plugins.kotlin.serialization)
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
        }
        release {
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
    implementation(projects.core.map)
    implementation(projects.feature.hikes)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
    implementation(libs.androidx.window.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(projects.core.testing)
    testImplementation(projects.core.testingAndroid)
    testImplementation(libs.robolectric)
    androidTestImplementation(projects.core.testingAndroid)
}
