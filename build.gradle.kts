plugins {
    `java-gradle-plugin`
    `version-catalog`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
}

group = "mc.xalitoria"
version = "0.1.0"

dependencies {
    // Renames the loader classes into the plugin's own package.
    implementation(libs.asm.commons)

    // Visible at runtime through the build's own plugin classpath, where the modules apply these.
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.shadow.gradle.plugin)

    // The loaders are plain Java compiled here and copied into plugins; they only need the platform APIs to compile.
    compileOnly(libs.paper.api)
    compileOnly(libs.velocity.api)
}

kotlin {
    jvmToolchain(25)
    sourceSets.main {
        kotlin.srcDirs("kotlin")
        resources.srcDirs("resources")
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    sourceSets.main {
        java.srcDirs("java")
    }
}

gradlePlugin {
    plugins {
        create("conventions") {
            id = "mc.xalitoria.conventions"
            implementationClass = "mc.xalitoria.gradle.Conventions"
        }
        create("minecraft") {
            id = "mc.xalitoria.minecraft"
            implementationClass = "mc.xalitoria.gradle.MinecraftPlugin"
        }
    }
}

// The version catalog every Xali repository reads, published next to the plugin: `libs` in the workspace is the file itself.
catalog {
    versionCatalog {
        from(files("libs.versions.toml"))
    }
}

publishing {
    publications {
        create<MavenPublication>("catalog") {
            artifactId = "xali-catalog"
            from(components["versionCatalog"])
        }
    }
}
