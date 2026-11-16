@file:Suppress("unused")

package mc.xalitoria.gradle.repo

import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.plugins.ExtensionAware
import java.net.URI

/** `maven("https://...") { name = "..." }`, as the Kotlin DSL writes it. */
private fun RepositoryHandler.maven(url: String, configure: MavenArtifactRepository.() -> Unit = {}): MavenArtifactRepository =
    maven { repository -> repository.setUrl(url); repository.configure() }

internal fun Project.property(environment: String, property: String): String? =
    System.getenv(environment) ?: findProperty(property) as? String

/** The groups of the Xali libraries, plugins and catalog: what the Xali and local Maven repositories are asked for. */
internal val XALI_GROUPS = listOf("com.xalitoria", "net.xalitoria", "mc.xalitoria")

/** Built from source and installed in the local Maven repository: no public repository has it, see the catalog. */
internal val LOCAL_GROUPS = listOf("com.discordsrv")

/**
 * `kotlinx("coroutines-core")` is `org.jetbrains.kotlinx:kotlinx-coroutines-core:+`: whatever is newest the day it is
 * built, so the build changes without a commit and the catalog's pin is not what it gets.
 */
@Deprecated("Declare the library from the catalog: libs.kotlinx.coroutines, libs.kotlinx.serialization.core, ...")
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

/**
 * What a repository is asked for. Gradle applies it (`mavenContent`) but a build cannot read it back, so each repository of
 * this file records it too, and the loader's list of repositories (see `usedRepositories`) leaves out a repository for a
 * library that Gradle would not have asked it for. [groups] include their subgroups, [regex] has to match the whole group,
 * and a repository that names neither serves every group.
 */
internal class Serves(
    val groups: List<String> = emptyList(),
    val regex: String? = null,
    val releases: Boolean = true,
    val snapshots: Boolean = true,
) {
    fun accepts(group: String, version: String): Boolean {
        if (version.endsWith("-SNAPSHOT")) { if (!snapshots) return false } else if (!releases) return false
        if (groups.isEmpty() && regex == null) return true
        return groups.any { group == it || group.startsWith("$it.") } || (regex != null && Regex(regex).matches(group))
    }
}

private const val SERVES = "xali.serves"

/** The [Serves] that [declare]d this repository, or `null` for one that is not from this file and serves everything. */
internal fun MavenArtifactRepository.serves(): Serves? =
    (this as? ExtensionAware)?.extensions?.extraProperties?.takeIf { it.has(SERVES) }?.get(SERVES) as? Serves

/** Restricts the repository to [content], for Gradle and for the loader's list. */
private fun MavenArtifactRepository.declare(content: Serves) {
    (this as ExtensionAware).extensions.extraProperties.set(SERVES, content)
    mavenContent { descriptor ->
        if (!content.releases) descriptor.snapshotsOnly()
        if (!content.snapshots) descriptor.releasesOnly()
        content.groups.forEach(descriptor::includeGroupAndSubgroups)
        content.regex?.let(descriptor::includeGroupByRegex)
    }
}

// Every repository below is asked only for what it serves (`mavenContent`). Gradle asks the repositories in order for each
// module and stops at the first that has it, so a repository without a filter is asked for every library of every module
// that comes after it, and answers for a name it should never be trusted with (a dependency-confusion door). Central and
// PaperMC (which proxies Central and holds the Mojang libraries) are the two that serve anything.

fun RepositoryHandler.mavenXalitoria() {
    maven("https://maven.xali.host/releases") {
        name = "xalitoria-releases"
        declare(Serves(XALI_GROUPS, snapshots = false))
    }
    maven("https://maven.xali.host/snapshots") {
        name = "xalitoria-snapshots"
        declare(Serves(XALI_GROUPS, releases = false))
    }
}

fun RepositoryHandler.mavenSonatype() =
    maven("https://oss.sonatype.org/content/repositories/snapshots") {
        name = "sonatype"
        declare(Serves(releases = false))
    }

fun RepositoryHandler.mavenSpigotMC() =
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") { name = "spigotmc" }

fun RepositoryHandler.mavenPaperMC() {
    maven("https://repo.papermc.io/repository/maven-releases") { name = "papermc-releases" }
    maven("https://repo.papermc.io/repository/maven-public") { name = "papermc-public" }
}

/** JitPack builds GitHub repositories as `com.github.<owner>`; NuVotifier's builds all fail there, see [githubNuVotifier]. */
fun RepositoryHandler.mavenJitpack() =
    maven("https://jitpack.io") {
        name = "Jitpack"
        declare(Serves(regex = """com\.github\.(?!NuVotifier\b).*"""))
    }

fun RepositoryHandler.mavenCodeMCPublic() =
    maven("https://repo.codemc.io/repository/maven-public/") {
        name = "CodeMC-public"
        declare(Serves(listOf("de.tr7zw", "net.skinsrestorer")))
    }

fun RepositoryHandler.mavenCodeMC() =
    maven("https://repo.codemc.io/repository/creatorfromhell/") {
        name = "CodeMC-creatorfromhell"
        declare(Serves(listOf("net.milkbowl.vault")))
    }

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

/** ProtocolLib's releases and its development builds (`5.5.0-SNAPSHOT`). */
fun RepositoryHandler.mavenDmulloy2() =
    maven("https://repo.dmulloy2.net/repository/public/") {
        name = "dmulloy2"
        declare(Serves(listOf("net.dmulloy2", "com.comphenix.protocol")))
    }

fun RepositoryHandler.mavenHelpChat() {
    maven("https://repo.extendedclip.com/snapshots") {
        name = "HelpChat-snapshots"
        declare(Serves(listOf("me.clip"), releases = false))
    }
    maven("https://repo.extendedclip.com/releases") {
        name = "HelpChat-releases"
        declare(Serves(listOf("me.clip"), snapshots = false))
    }
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
