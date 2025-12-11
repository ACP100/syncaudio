pluginManagement {
    repositories {
        google {
            content {
                // Keep existing Android and Google groups
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")

                // --- FIX: Explicitly include the Kotlin group for its plugins ---
                includeGroupByRegex("org\\.jetbrains\\.kotlin.*")
            }
        }

        // These repositories are correctly included and necessary
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

rootProject.name = "syncaudio"
include(":app")