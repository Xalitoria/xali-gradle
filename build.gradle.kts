plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.4.20"
}

group = "mc.xalitoria"
version = "0.1.0"

dependencies {
    // Renames the loader classes into the plugin's own package.
    implementation("org.ow2.asm:asm-commons:9.9")

    // Visible at runtime through the build's own plugin classpath, where the modules apply these.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    compileOnly("com.gradleup.shadow:shadow-gradle-plugin:9.3.0")

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
        create("minecraft") {
            id = "mc.xalitoria.minecraft"
            implementationClass = "mc.xalitoria.gradle.MinecraftPlugin"
        }
    }
}
