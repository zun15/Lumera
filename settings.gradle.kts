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
    }
}

rootProject.name = "Lumera"
include(":app")
include(":playbackcore")
include(":assrender")
// The original module (../assrender/assrender) is not part of this repository, so a clean clone
// cannot build with it. Use the no-op stand-in that ships in this repo (see assrender-stub/).
project(":assrender").projectDir = file("assrender-stub")
