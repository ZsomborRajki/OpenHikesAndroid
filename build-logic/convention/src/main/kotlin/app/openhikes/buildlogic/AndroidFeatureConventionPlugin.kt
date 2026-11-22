package app.openhikes.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("openhikes.android.library.compose")
                apply("openhikes.hilt")
                apply("openhikes.lint")
                apply("org.jetbrains.kotlin.plugin.serialization")
            }

            dependencies {
                add("implementation", project(":core:designsystem"))
                add("implementation", project(":core:ui"))
                add("implementation", project(":core:model"))

                add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
                add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
                add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-navigation3").get())
                add("implementation", libs.findLibrary("androidx-navigation3-runtime").get())
                add("implementation", libs.findLibrary("androidx-navigation3-ui").get())
                add("implementation", libs.findLibrary("androidx-compose-material3-adaptive").get())
                add("implementation", libs.findLibrary("androidx-compose-material3-adaptive-navigation3").get())
                add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
                add("implementation", libs.findLibrary("kotlinx-serialization-json").get())

                add("testImplementation", project(":core:testing"))
                add("testImplementation", project(":core:testing-android"))
                add("testImplementation", libs.findLibrary("robolectric").get())
                add("androidTestImplementation", project(":core:testing"))
                add("androidTestImplementation", project(":core:testing-android"))
            }
        }
    }
}
