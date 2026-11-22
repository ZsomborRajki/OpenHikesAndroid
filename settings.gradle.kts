pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OpenHikesAndroid"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":core:model")
include(":core:common")
include(":core:designsystem")
include(":core:ui")
include(":core:datastore")
include(":core:database")
include(":core:data")
include(":core:map")
include(":core:testing")
include(":core:testing-android")
include(":feature:hikes")
include(":feature:settings")
