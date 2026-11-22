package app.openhikes.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

class AndroidScreenshotTestConventionPlugin : Plugin<Project> {
    @Suppress("UnstableApiUsage")
    override fun apply(target: Project) {
        with(target) {
            val composeBom = dependencies.platform(libs.findLibrary("androidx-compose-bom").get())
            val uiTooling = libs.findLibrary("androidx-compose-ui-tooling").get()
            val screenshotValidationApi = libs.findLibrary("screenshot-validation-api").get()
            val engine = libs.findVersion("screenshotValidation").get().requiredVersion

            extensions.configure<CommonExtension> {
                testOptions.screenshotTests.create("screenshotTest") {
                    engineVersion = engine
                    imageDifferenceThreshold = 0.001f
                    targetVariants.add("debug")
                    dependencies {
                        implementation.add(composeBom)
                        implementation.add(uiTooling)
                        implementation.add(screenshotValidationApi)
                    }
                }
            }

            tasks.withType<Test>().configureEach {
                if (name.contains("ScreenshotTest")) {
                    jvmArgs("-Xss4m")
                }
            }
        }
    }
}
