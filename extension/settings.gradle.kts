pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // karoo-ext served anonymously via jitpack (no GitHub token needed)
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Trip Companion"
include(":app")
