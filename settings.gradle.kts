pluginManagement {
    includeBuild("build-logic")
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
    }
}

rootProject.name = "Reader"

include(":app")

include(":core:model")
include(":core:common")
include(":core:database")
include(":core:datastore")
include(":core:storage")
include(":core:data")
include(":core:designsystem")
include(":core:ui")

include(":engine:format-api")
include(":engine:format-txt")
include(":engine:format-markdown")
include(":engine:format-epub")
include(":engine:format-pdf")
include(":engine:layout")
include(":engine:render")

include(":feature:library")
include(":feature:reader")
include(":feature:settings")

include(":benchmark")
