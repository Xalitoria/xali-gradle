// A separate build, included by the main one through pluginManagement, so the plugin is built before any module uses it.
dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
    // The platform versions the loaders are compiled against are the main build's.
    versionCatalogs {
        create("libs") {
            from(files("../libs.versions.toml"))
        }
    }
}

rootProject.name = "xali-minecraft"
