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
    // Changed to PREFER_SETTINGS to allow PixelWheels subproject repos
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // LibGDX snapshots and releases
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }
        maven { url = uri("https://oss.sonatype.org/content/repositories/releases/") }
    }
}

rootProject.name = "Arlo Reading App"
include(":app")

// PixelWheels game integration (GPL-3.0+)
include(":pixelwheels:core")
project(":pixelwheels:core").projectDir = file("pixelwheels/core")
