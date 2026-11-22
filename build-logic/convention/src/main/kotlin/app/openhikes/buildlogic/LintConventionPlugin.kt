package app.openhikes.buildlogic

import com.android.build.api.dsl.CommonExtension
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import dev.detekt.gradle.extensions.FailOnSeverity
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jlleitschuh.gradle.ktlint.KtlintExtension

class LintConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("org.jlleitschuh.gradle.ktlint")
                apply("dev.detekt")
            }

            extensions.configure<KtlintExtension> {
                ignoreFailures.set(false)
                filter {
                    exclude { element ->
                        val normalized = element.file.path.replace('\\', '/')
                        normalized.contains("/build/")
                    }
                }
            }

            extensions.configure<DetektExtension> {
                buildUponDefaultConfig.set(true)
                parallel.set(true)
                failOnSeverity.set(FailOnSeverity.Warning)
                config.setFrom(rootProject.files("config/detekt/detekt.yml"))
            }

            tasks.withType<Detekt>().configureEach {
                exclude("**/build/**")
            }

            val detektAll = tasks.register("detektAll") {
                group = "verification"
                description = "Runs type-resolved Detekt analysis across main and test sources."
            }

            pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                detektAll.configure {
                    dependsOn("detektMain", "detektTest")
                }
            }

            pluginManager.withPlugin("com.android.base") {
                extensions.configure<KtlintExtension> {
                    android.set(true)
                }

                detektAll.configure {
                    dependsOn(
                        tasks.matching { task ->
                            task.name == "detektDebug" ||
                                task.name == "detektDebugUnitTest" ||
                                task.name == "detektDebugAndroidTest"
                        },
                    )
                }

                tasks.withType<Detekt>().configureEach {
                    if (name == "detektDebug" || name == "detektDebugUnitTest" || name == "detektDebugAndroidTest") {
                        val suffix = name.removePrefix("detekt")
                        val kotlinCompile = tasks.withType<KotlinJvmCompile>().matching {
                            it.name == "compile${suffix}Kotlin"
                        }
                        val javaCompile = tasks.withType<JavaCompile>().matching {
                            it.name == "compile${suffix}JavaWithJavac"
                        }
                        classpath.from(
                            kotlinCompile.map { it.destinationDirectory },
                            kotlinCompile.map { it.libraries },
                            javaCompile.map { it.destinationDirectory },
                        )
                        if (suffix != "Debug") {
                            val debugKotlinCompile = tasks.withType<KotlinJvmCompile>().matching {
                                it.name == "compileDebugKotlin"
                            }
                            val debugJavaCompile = tasks.withType<JavaCompile>().matching {
                                it.name == "compileDebugJavaWithJavac"
                            }
                            classpath.from(
                                debugKotlinCompile.map { it.destinationDirectory },
                                debugJavaCompile.map { it.destinationDirectory },
                            )
                        }
                    }
                }

                dependencies {
                    add("lintChecks", libs.findLibrary("compose-lint-checks").get())
                }

                extensions.configure<CommonExtension> {
                    lint.apply {
                        warningsAsErrors = true
                        abortOnError = true
                        checkReleaseBuilds = false
                        disable += setOf(
                            "GradleDependency",
                            "AndroidGradlePluginVersion",
                            "NewerVersionAvailable",
                        )
                    }
                }
            }
        }
    }
}
