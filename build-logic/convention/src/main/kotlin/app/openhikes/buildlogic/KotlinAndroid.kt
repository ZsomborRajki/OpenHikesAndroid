package app.openhikes.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension) {
    val targetCompileSdk = libs.findVersion("compileSdk").get().requiredVersion.toInt()
    val targetMinSdk = libs.findVersion("minSdk").get().requiredVersion.toInt()

    commonExtension.apply {
        compileSdk {
            version = release(targetCompileSdk)
        }

        defaultConfig.minSdk = targetMinSdk

        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }

        testOptions.unitTests.isIncludeAndroidResources = true
    }

    configureKotlinCompilerOptions()
}

internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    configureKotlinCompilerOptions()
}

private fun Project.configureKotlinCompilerOptions() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            allWarningsAsErrors.set(true)
        }
    }
}
