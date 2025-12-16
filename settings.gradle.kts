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
    }
}

rootProject.name = "BlueBubbles"

// Native Compose app module
include(":app")

// Core modules (Phase 6 modularization)
include(":core:model")
include(":core:network")
include(":core:data")

// Navigation module (Phase 15 - route contracts)
include(":navigation")

// Feature modules (Phase 15)
include(":feature:settings")
include(":feature:setup")
include(":feature:conversations")
include(":feature:chat")
