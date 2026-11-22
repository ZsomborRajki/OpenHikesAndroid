plugins {
    id("openhikes.android.application.compose")
    id("openhikes.hilt")
    id("openhikes.lint")
}

android {
    namespace = "app.openhikes"

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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(projects.core.testing)
    testImplementation(projects.core.testingAndroid)
    testImplementation(libs.robolectric)
    androidTestImplementation(projects.core.testingAndroid)
}
