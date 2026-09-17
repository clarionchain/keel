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
        maven { url = uri("https://gitlab.com/api/v4/projects/78057981/packages/maven") }
    }
}

rootProject.name = "keel"
include(":core")
include(":app")
