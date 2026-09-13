pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // Treat the downloaded Xray AAR as a regular local module. AGP 9 can
        // otherwise expose it as a `local file` artifact with no extracted
        // folder during IDE sync.
        flatDir { dirs("$rootDir/app/libs") }
    }
}

rootProject.name = "PingNG"
include(":app")
