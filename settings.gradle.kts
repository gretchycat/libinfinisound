pluginManagement {
    resolutionStrategy.eachPlugin {
        if (requested.id.id == "org.jetbrains.kotlin.jvm") useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20")
        if (requested.id.id == "com.android.library") useModule("com.android.tools.build:gradle:8.9.2")
    }
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "libinfinisound"
include(":audio-api", ":audio-core")
if (!providers.gradleProperty("portableOnly").isPresent) include(":audio-processing")
