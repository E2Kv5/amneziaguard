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
        maven("https://jitpack.io")
    }
}

rootProject.name = "AmneziaGuard"

include(
    ":app",
    ":core-tunnel",
    ":core-firewall",
    ":core-netstack",
    ":core-data",
    ":core-ui",
    ":feature-connect",
    ":feature-firewall",
    ":feature-settings",
    ":tile",
    ":background",
)
