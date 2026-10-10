package mc.xalitoria.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.Directory
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension

internal enum class Kind(val dir: String) { PAPER("paper"), VELOCITY("velocity") }

/** A platform's block, and the project that wrote it (the project's own name is the default for [PlatformExtension.name]). */
internal class Declared(val extension: PlatformExtension, val declaredIn: Project)

/** Where a jar's sources, resources and `api` dependencies live: the two project layouts a plugin can have. */
internal interface Layout {
    /** Resources for the jar from [dir], which a task writes. */
    fun addResources(dir: Provider<Directory>)

    /** Also read sources from the directories next to the build script; [prefix] is where they are (`paper/` in a multiplatform project). */
    fun relocate(prefix: String)

    /** The configurations `api(...)` writes to. */
    fun apiConfigurations(): List<Configuration>

    /** What the jar needs at run time, whose attributes the libraries are resolved with. */
    fun runtimeClasspath(): Configuration
}

/** Adds [path] (relative to the project) unless the source set reads it already, as it does when the build set it up itself. */
private fun SourceDirectorySet.addDir(project: Project, path: String) {
    val dir = project.file(path)
    if (dir !in srcDirs) srcDir(dir)
}

/** A plain JVM project: one jar with `src/main` and, if both platforms are in use, both of them in it. */
internal class JvmLayout(private val project: Project) : Layout {
    override fun addResources(dir: Provider<Directory>) {
        project.extensions.getByType(SourceSetContainer::class.java).getByName("main").resources.srcDir(dir)
    }

    override fun relocate(prefix: String) {
        val kotlin = project.extensions.getByType(KotlinProjectExtension::class.java)
        kotlin.sourceSets.getByName("main").apply {
            this.kotlin.addDir(project, "${prefix}kotlin")
            resources.addDir(project, "${prefix}resources")
        }
        kotlin.sourceSets.getByName("test").apply {
            this.kotlin.addDir(project, "${prefix}test")
            resources.addDir(project, "${prefix}test-resources")
        }
        project.extensions.getByType(SourceSetContainer::class.java).getByName("main").java.addDir(project, "${prefix}java")
    }

    override fun apiConfigurations() = listOfNotNull(project.configurations.findByName("api"))

    override fun runtimeClasspath() = project.configurations.getByName("runtimeClasspath")
}

/** A Kotlin Multiplatform project with a JVM target named after the platform, such as `jvm("paper")`. */
internal class KmpLayout(private val project: Project, private val target: String) : Layout {
    private val kotlin get() = project.extensions.getByType(KotlinMultiplatformExtension::class.java)

    override fun addResources(dir: Provider<Directory>) {
        kotlin.sourceSets.getByName("${target}Main").resources.srcDir(dir)
    }

    override fun relocate(prefix: String) {
        kotlin.sourceSets.getByName("${target}Main").apply {
            this.kotlin.addDir(project, "${prefix}kotlin")
            this.kotlin.addDir(project, "${prefix}java")
            resources.addDir(project, "${prefix}resources")
        }
        kotlin.sourceSets.findByName("${target}Test")?.apply {
            this.kotlin.addDir(project, "${prefix}test/kotlin")
            resources.addDir(project, "${prefix}test/resources")
        }
    }

    /** Every source set the target is built from has its own `api` configuration. */
    override fun apiConfigurations() = kotlin.targets.getByName(target).compilations.getByName("main").allKotlinSourceSets
        .mapNotNull { project.configurations.findByName(it.apiConfigurationName) }

    override fun runtimeClasspath() = project.configurations.getByName("${target}RuntimeClasspath")
}

/**
 * The platforms that end up in one jar. A plain JVM project is one bundle with every platform it declares; a
 * multiplatform project has one per platform, since each target builds its own jar.
 */
internal class Bundle(val project: Project, val key: String, val kinds: Map<Kind, Declared>, val layout: Layout)

/**
 * The block for [kind] that applies to [project]: its own, or, in a project named after the platform (`paper`,
 * `velocity`), the one its parent module wrote. A platform is in use when its block has a main class.
 */
private fun declared(project: Project, kind: Kind): Declared? {
    // A module with a submodule for the platform only describes it; the submodule is the plugin.
    if (kind.dir in project.childProjects) return null
    fun of(owner: Project?): Declared? {
        val extension = owner?.extensions?.findByName(kind.dir) as? PlatformExtension
        return extension?.takeIf { it.main != null }?.let { Declared(it, owner) }
    }
    return of(project) ?: if (project.name == kind.dir) of(project.parent) else null
}

internal fun bundles(project: Project): List<Bundle> {
    val kinds = Kind.entries.mapNotNull { kind -> declared(project, kind)?.let { kind to it } }.toMap()
    if (kinds.isEmpty()) return emptyList()
    if (!project.pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform")) return listOf(Bundle(project, "", kinds, JvmLayout(project)))
    return kinds.map { (kind, declared) -> Bundle(project, kind.dir, mapOf(kind to declared), KmpLayout(project, kind.dir)) }
}
