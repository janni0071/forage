pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // AndroidX libraries that Compose depends on are published only here.
        google {
            content { includeGroupByRegex("androidx\\..*") }
        }
        // NewPipeExtractor is only published through JitPack.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}

rootProject.name = "forage"

include(":core", ":source-youtube", ":source-pornhub", ":embedding-onnx", ":language-lingua", ":cli", ":desktop")
