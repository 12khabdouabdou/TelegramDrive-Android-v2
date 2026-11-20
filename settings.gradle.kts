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
        maven("https://jitpack.io")
        // TDLight official Maven repository (no auth required)
        maven("https://mvn.mchv.eu/repository/mchv/")
    }
}

rootProject.name = "TelegramDrive"

include(":app")

// Core modules
include(":core:common")
include(":core:telegram")
include(":core:db")
include(":core:datastore")
include(":core:security")
include(":core:download")
include(":core:zip")
include(":core:audio")

// Feature modules
include(":feature:auth")
include(":feature:browser")
include(":feature:upload")
include(":feature:download")
include(":feature:autobackup")
include(":feature:player")
include(":feature:settings")
include(":feature:folders")

// Design system
include(":designsystem")
