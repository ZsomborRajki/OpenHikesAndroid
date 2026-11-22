import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
}

group = "app.openhikes.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.android.gradleApiPlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.ktlint.gradlePlugin)
}

tasks {
    validatePlugins {
        enableStricterValidation = true
        failOnWarning = true
    }
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "openhikes.android.application"
            implementationClass = "app.openhikes.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidApplicationCompose") {
            id = "openhikes.android.application.compose"
            implementationClass = "app.openhikes.buildlogic.AndroidApplicationComposeConventionPlugin"
        }
        register("androidLibrary") {
            id = "openhikes.android.library"
            implementationClass = "app.openhikes.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "openhikes.android.library.compose"
            implementationClass = "app.openhikes.buildlogic.AndroidLibraryComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "openhikes.android.feature"
            implementationClass = "app.openhikes.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("androidScreenshotTest") {
            id = "openhikes.android.screenshot"
            implementationClass = "app.openhikes.buildlogic.AndroidScreenshotTestConventionPlugin"
        }
        register("jvmLibrary") {
            id = "openhikes.jvm.library"
            implementationClass = "app.openhikes.buildlogic.JvmLibraryConventionPlugin"
        }
        register("hilt") {
            id = "openhikes.hilt"
            implementationClass = "app.openhikes.buildlogic.HiltConventionPlugin"
        }
        register("lint") {
            id = "openhikes.lint"
            implementationClass = "app.openhikes.buildlogic.LintConventionPlugin"
        }
    }
}
