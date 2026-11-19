pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // The Android Gradle plugin.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx\\..*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // AndroidX libraries (Compose depends on them) and Android's build tools are published only here.
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.testing.*")
            }
        }
        // NewPipeExtractor is only published through JitPack.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}

rootProject.name = "forage"

include(":core", ":source-youtube", ":source-pornhub", ":embedding-onnx", ":language-lingua", ":cli", ":desktop", ":android")
