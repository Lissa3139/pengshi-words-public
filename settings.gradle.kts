pluginManagement {
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

rootProject.name = "PengshiWords"

include(
    ":app",
    ":desktop",
    ":core:model",
    ":core:database",
    ":core:scheduler",
    ":core:import",
    ":core:speech-api",
    ":core:speech",
    ":core:backup",
    ":core:sync",
    ":core:stats",
)
