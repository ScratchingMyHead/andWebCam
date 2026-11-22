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
        // OpenCV's Android SDK is a 113 MB AAR containing every ABI. The copy
        // in the local Maven repo was fetched once; Gradle can also fall back
        // to Maven Central on a fresh machine.
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "andWebCam"
include(":app")
