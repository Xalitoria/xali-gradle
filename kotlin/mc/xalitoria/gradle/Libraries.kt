package mc.xalitoria.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Provider
import java.security.MessageDigest

/** Libraries the platforms already have, so a plugin never loads a second copy. */
private val provided = listOf(
    "net.kyori", "com.google.code.gson", "org.slf4j", "com.google.inject", "org.jetbrains:annotations",
    "org.jetbrains.kotlin:kotlin-stdlib-jdk7", "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
)

private const val CENTRAL_PROXY = "https://maven-central.storage-download.googleapis.com/maven2/"
private val central = listOf("https://repo.maven.apache.org/maven2/", "https://repo1.maven.org/maven2/", "https://repo.maven.apache.org/maven2")

/**
 * What a bundle loads at run time: everything declared with `api(...)` that comes from a repository (project
 * dependencies and files are shaded like `implementation`), resolved on its own so the versions are the ones the jar
 * will really get. All of it is lazy, so nothing resolves until a task needs it.
 */
internal class Libraries(
    /** `name` to URL, for every repository the project, its parents and its modules use. */
    val repositories: Map<String, String>,
    /** The `group:name:version` of each library declared, as Paper's resolver wants them (it adds their dependencies). */
    val direct: Provider<List<String>>,
    /** The `group:name:version:sha256` of those and everything they need, as Velocity's loader wants them. */
    val resolved: Provider<List<String>>,
    /** The `group:name` of all of those, including what the platform provides: none of it goes in a shaded jar. */
    val closure: Provider<Set<String>>,
)

/** [pattern] is `group`, `group:name`, or either ending in `*`. */
private fun matches(pattern: String, group: String, name: String): Boolean = when {
    pattern.endsWith("*") -> "$group:$name".startsWith(pattern.dropLast(1))
    else -> pattern == group || pattern == "$group:$name"
}

internal fun libraries(bundle: Bundle): Libraries? {
    val project = bundle.project
    val declared = bundle.layout.apiConfigurations().flatMap { it.dependencies }.filterIsInstance<ExternalModuleDependency>()
    if (declared.isEmpty()) return null

    val exclude = provided + bundle.kinds.values.flatMap { it.extension.loader.exclude }
    val centralProxy = bundle.kinds.values.all { it.extension.loader.centralProxy }

    // Resolved by itself, with the attributes of the jar's run-time classpath, so it picks the same variants.
    val runtime = bundle.layout.runtimeClasspath()
    val configuration = project.configurations.detachedConfiguration(*declared.map { it.copy() }.toTypedArray())
    runtime.attributes.keySet().forEach { key ->
        @Suppress("UNCHECKED_CAST")
        configuration.attributes.attribute(key as org.gradle.api.attributes.Attribute<Any>, runtime.attributes.getAttribute(key)!!)
    }

    val artifacts = configuration.incoming.artifacts.resolvedArtifacts
    return Libraries(
        repositories = repositories(project, centralProxy),
        direct = configuration.incoming.resolutionResult.rootComponent.map { root ->
            root.dependencies.asSequence().filterIsInstance<ResolvedDependencyResult>()
                .mapNotNull { it.selected.moduleVersion }
                .filter { module -> exclude.none { matches(it, module.group, module.name) } }
                .map { "${it.group}:${it.name}:${it.version}" }
                .distinct().sorted().toList()
        },
        resolved = artifacts.map { set ->
            set.mapNotNull { artifact ->
                (artifact.id.componentIdentifier as? ModuleComponentIdentifier)
                    ?.takeIf { module -> exclude.none { matches(it, module.group, module.module) } }
                    ?.let { "${it.group}:${it.module}:${it.version}:${sha256(artifact.file.readBytes())}" }
            }.distinct().sorted()
        },
        closure = artifacts.map { set ->
            set.mapNotNull { (it.id.componentIdentifier as? ModuleComponentIdentifier)?.let { module -> "${module.group}:${module.module}" } }.toSet()
        },
    )
}

/**
 * Every Maven repository with a web address that [project], its parents and the projects below it declare, in that
 * order and without repeats. Local ones are no use to a server, and Central is swapped for a mirror if asked.
 */
private fun repositories(project: Project, centralProxy: Boolean): Map<String, String> {
    val owners = generateSequence(project) { it.parent }.toList() + project.allprojects
    val result = linkedMapOf<String, String>()
    owners.flatMap { it.repositories.filterIsInstance<MavenArtifactRepository>() }.forEach { repository ->
        var url = repository.url.toString()
        if (!url.startsWith("http")) return@forEach
        if (centralProxy && url in central) url = CENTRAL_PROXY
        if (url.trimEnd('/') !in result.values.map { it.trimEnd('/') }) {
            var name = repository.name
            while (name in result) name += "_"
            result[name] = url
        }
    }
    return result
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
