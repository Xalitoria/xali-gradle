package mc.xalitoria.gradle

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.specs.Spec

/**
 * Describes a Paper or Velocity plugin and gets its libraries to the server. It adds two blocks, `paper { }` and
 * `velocity { }`, which can sit in the build script of the module or of a submodule named after the platform (a
 * `paper` project under `chat` uses the `paper { }` block of `chat`). A platform is in use once its block has a main
 * class; a project can use both, and a Kotlin Multiplatform project uses a JVM target named after each platform.
 *
 * For each platform it writes the descriptor (`paper-plugin.yml`, `velocity-plugin.json`) into the jar's resources.
 * And when the shadow plugin is applied and `api(...)` declares libraries, it writes `loader.json` and a loader class
 * that downloads them when the plugin loads (see [LoaderOptions]), and keeps them out of the shaded jar.
 */
class MinecraftPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("paper", PaperExtension::class.java)
        project.extensions.create("velocity", VelocityExtension::class.java)

        // A submodule named after a platform takes its block from the module, so it needs the plugin too.
        project.childProjects.values.filter { child -> Kind.entries.any { it.dir == child.name } }
            .forEach { it.pluginManager.apply(MinecraftPlugin::class.java) }

        project.afterEvaluate { bundles(project).forEach { configure(it) } }
    }

    private fun configure(bundle: Bundle) {
        val project = bundle.project
        val prefix = bundle.key.replaceFirstChar(Char::uppercase)

        // Sources
        bundle.kinds.forEach { (kind, declared) ->
            if (declared.extension.relocateSources) bundle.layout.relocate(if (bundle.key.isEmpty()) "" else "${kind.dir}/")
        }

        // The loader: written for the platforms that want one, when there is a shaded jar and something to load.
        val libraries = libraries(bundle).takeIf { project.pluginManager.hasPlugin("com.gradleup.shadow") }
        val generated = bundle.kinds.filter { (kind, declared) ->
            declared.extension.loader.enabled && !(kind == Kind.PAPER && declared.extension.loader.className != null)
        }.mapValues { (_, declared) ->
            val main = declared.extension.main!!
            val loader = declared.extension.loader
            val pkg = loader.packageName ?: main.substringBeforeLast('.', "")
            val name = loader.name ?: (main.substringAfterLast('.') + "Loader")
            if (pkg.isEmpty()) name else "$pkg.$name"
        }.takeIf { libraries != null }.orEmpty()

        if (libraries != null && generated.isNotEmpty()) {
            val velocity = bundle.kinds[Kind.VELOCITY]?.extension
            val manifest = project.tasks.register("generate${prefix}LoaderManifest", WriteFile::class.java) { task ->
                task.fileName.set("loader.json")
                task.content.set(
                    if (velocity == null) libraries.direct.map { manifest("", libraries.repositories, it, emptyList()) }
                    else libraries.direct.zip(libraries.resolved) { direct, resolved -> manifest(velocity.main!!, libraries.repositories, direct, resolved) }
                )
                task.outputDirectory.set(project.layout.buildDirectory.dir("generated/xali/${bundle.key.ifEmpty { "main" }}/loader-manifest"))
            }
            val classes = project.tasks.register("generate${prefix}LoaderClasses", GenerateLoaderClasses::class.java) { task ->
                task.classes.set(generated.mapKeys { (kind, _) -> if (kind == Kind.PAPER) "PaperLoader" else "VelocityLoader" })
                task.outputDirectory.set(project.layout.buildDirectory.dir("generated/xali/${bundle.key.ifEmpty { "main" }}/loader-classes"))
            }
            bundle.layout.addResources(manifest.flatMap { it.outputDirectory })
            bundle.layout.addResources(classes.flatMap { it.outputDirectory })

            // Libraries the loader fetches have no place in the shaded jar.
            val closure = libraries.closure
            project.tasks.withType(ShadowJar::class.java).configureEach { shadow ->
                shadow.dependencies { filter ->
                    filter.exclude(Spec<ResolvedDependency> { "${it.moduleGroup}:${it.moduleName}" in closure.get() })
                }
            }
        }

        // Descriptors
        bundle.kinds.forEach { (kind, declared) ->
            val extension = declared.extension
            val name = extension.name ?: declared.declaredIn.name
            val version = extension.version ?: project.version.toString()
            val description = extension.description ?: project.description ?: declared.declaredIn.description
            val (file, text) = when (kind) {
                Kind.PAPER -> "paper-plugin.yml" to paperDescriptor(
                    extension as PaperExtension, name, version, description, generated[kind] ?: extension.loader.className,
                )
                Kind.VELOCITY -> "velocity-plugin.json" to velocityDescriptor(
                    extension as VelocityExtension,
                    extension.id ?: name.lowercase().replace(Regex("[^a-z0-9_-]"), ""),
                    name, version, description, generated[kind] ?: extension.main!!,
                )
            }
            val task = project.tasks.register("generate${kind.dir.replaceFirstChar(Char::uppercase)}PluginDescription", WriteFile::class.java) {
                it.fileName.set(file)
                it.content.set(text)
                it.outputDirectory.set(project.layout.buildDirectory.dir("generated/xali/${kind.dir}-descriptor"))
            }
            bundle.layout.addResources(task.flatMap { it.outputDirectory })
        }
    }

    /** `loader.json`: what the loaders read. [libraries] is only filled for Velocity, whose loader downloads the jars itself. */
    private fun manifest(main: String, repositories: Map<String, String>, direct: List<String>, libraries: List<String>): String {
        fun entries(values: List<String>, entry: (String) -> String) =
            if (values.isEmpty()) "" else values.joinToString(",\n", prefix = "\n", postfix = "\n  ") { "    ${entry(it)}" }
        val repos = entries(repositories.map { (name, url) -> "$name\t$url" }) { it.split("\t").let { (name, url) -> "${quote(name)}: ${quote(url)}" } }
        val deps = entries(direct) { quote(it) }
        val libs = entries(libraries) {
            val (group, name, version, sha256) = it.split(":")
            "{\"coordinates\": ${quote("$group:$name:$version")}, \"sha256\": ${quote(sha256)}}"
        }
        return "{\n  \"main\": ${quote(main)},\n  \"repositories\": {$repos},\n  \"dependencies\": [$deps],\n  \"libraries\": [$libs]\n}\n"
    }
}
