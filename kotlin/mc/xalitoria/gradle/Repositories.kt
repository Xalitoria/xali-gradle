@file:Suppress("unused")

package mc.xalitoria.gradle.repo

import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import java.net.URI

/** `maven("https://...") { name = "..." }`, as the Kotlin DSL writes it. */
private fun RepositoryHandler.maven(url: String, configure: MavenArtifactRepository.() -> Unit = {}): MavenArtifactRepository =
    maven { repository -> repository.setUrl(url); repository.configure() }

private fun Project.property(environment: String, property: String): String? =
    System.getenv(environment) ?: findProperty(property) as? String

/** `kotlinx("coroutines-core")` is `org.jetbrains.kotlinx:kotlinx-coroutines-core:+`. */
fun DependencyHandler.kotlinx(module: String, version: String? = "+"): Any =
    "org.jetbrains.kotlinx:kotlinx-" + module + (version?.let { ":$it" } ?: ":+")

/** A Reposilite (or any Maven) repository that takes `MAVEN_USER` and `MAVEN_TOKEN`, or `maven.user` and `maven.token`. */
fun RepositoryHandler.reposilite(project: Project, url: String) =
    maven(url) {
        credentials { credentials ->
            credentials.username = project.property("MAVEN_USER", "maven.user")
            credentials.password = project.property("MAVEN_TOKEN", "maven.token")
        }
    }

fun RepositoryHandler.mavenXalitoria() {
    maven("https://maven.xali.host/releases") { name = "xalitoria-releases" }
    maven("https://maven.xali.host/snapshots") { name = "xalitoria-snapshots" }
}

fun RepositoryHandler.mavenSonatype() =
    maven("https://oss.sonatype.org/content/repositories/snapshots") { name = "sonatype" }

fun RepositoryHandler.mavenSpigotMC() =
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") { name = "spigotmc" }

fun RepositoryHandler.mavenPaperMC() {
    maven("https://repo.papermc.io/repository/maven-releases") { name = "papermc-releases" }
    maven("https://repo.papermc.io/repository/maven-public") { name = "papermc-public" }
}

fun RepositoryHandler.mavenJitpack() =
    maven("https://jitpack.io") { name = "Jitpack" }

fun RepositoryHandler.mavenCodeMCPublic() =
    maven("https://repo.codemc.io/repository/maven-public/") { name = "CodeMC-public" }

fun RepositoryHandler.mavenCodeMC() =
    maven("https://repo.codemc.io/repository/creatorfromhell/") { name = "CodeMC-creatorfromhell" }

/**
 * NuVotifier is not on any Maven repository (its Jitpack builds all fail), only attached to its GitHub releases as one
 * jar that holds every platform. This serves that jar for any of its modules, by version.
 */
fun RepositoryHandler.githubNuVotifier() =
    ivy { repository ->
        repository.name = "NuVotifier-releases"
        repository.url = URI("https://github.com/NuVotifier/NuVotifier/releases/download")
        repository.patternLayout { it.artifact("v[revision]/nuvotifier-[revision].jar") }
        repository.metadataSources { it.artifact() }
        repository.content { it.includeGroup("com.github.NuVotifier.NuVotifier") }
    }

fun RepositoryHandler.mavenHelpChat() {
    maven("https://repo.extendedclip.com/snapshots") { name = "HelpChat-snapshots" }
    maven("https://repo.extendedclip.com/releases") { name = "HelpChat-releases" }
}

fun RepositoryHandler.mavenNexo() =
    maven("https://repo.nexomc.com/releases") { name = "nexo-releases" }

fun RepositoryHandler.github(project: Project, owner: String, repo: String) =
    maven("https://maven.pkg.github.com/$owner/$repo") {
        name = "GithubPackages"
        credentials { credentials ->
            credentials.username = project.property("GITHUB_USER", "github.user")
            credentials.password = project.property("GITHUB_TOKEN", "github.token")
        }
    }
