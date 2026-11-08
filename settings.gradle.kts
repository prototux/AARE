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
        // libsu (su-backed car audio) is only published on JitPack; restricted to it so JitPack
        // can't serve or shadow any other dependency.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.topjohnwu.libsu") }
        }
    }
}
rootProject.name = "AARE"
include(":aap")
include(":app")
include(":headunit")
