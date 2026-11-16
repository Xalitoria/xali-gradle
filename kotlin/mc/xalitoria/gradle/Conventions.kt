package mc.xalitoria.gradle

import mc.xalitoria.gradle.repo.mavenCodeMC
import mc.xalitoria.gradle.repo.mavenCodeMCPublic
import mc.xalitoria.gradle.repo.mavenHelpChat
import mc.xalitoria.gradle.repo.mavenJitpack
import mc.xalitoria.gradle.repo.mavenPaperMC
import mc.xalitoria.gradle.repo.mavenSonatype
import mc.xalitoria.gradle.repo.mavenXalitoria
import mc.xalitoria.gradle.repo.LOCAL_GROUPS
import mc.xalitoria.gradle.repo.XALI_GROUPS
import mc.xalitoria.gradle.repo.mavenDmulloy2
import mc.xalitoria.gradle.repo.githubNuVotifier
import mc.xalitoria.gradle.repo.property
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.concurrent.Callable
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * The build setup every Xali module shares. Applied to a module's build script, it applies itself to the module and every
 * project below it, so the same script works inside the workspace (where the modules are nested in one build) and in a
 * module's own repository:
 *
 * - repositories;
 * - Kotlin on Java 25 (modules that need less set their own toolchain afterwards), the opt-ins and language previews
 *   every module is written against, and the source layout `kotlin/`, `java/`, `resources/`, `test/` and `test-resources/`;
 * - the name of every jar, see [name], and a check that no two jars of one build get the same name.
 *
 * A Kotlin Multiplatform project (the plugin is applied before this one) keeps its own targets and source sets.
 *
 * With `xali.workspace=true` in `gradle.properties`, the modules are the top-level projects of one build, and their jars
 * go to the root's `build/libs/<module>`. Otherwise the root project is the module, and its jars go to its own `build/libs`.
 */
class Conventions : Plugin<Project> {
    override fun apply(project: Project) {
        project.allprojects.forEach(::configure)
        check(project.rootProject)
    }

    private fun configure(project: Project) {
        if (project.extensions.extraProperties.has(APPLIED)) return
        project.extensions.extraProperties[APPLIED] = true

        project.repositories.apply {
            // The local Maven repository is whatever is on one machine, so only what has to come from there does: the
            // Xali libraries of a module built on its own (the workspace builds them from their sources, see the root
            // build script), and the libraries that no public repository has.
            mavenLocal { it.content { content ->
                (if (project.rootProject.isWorkspace()) LOCAL_GROUPS else XALI_GROUPS + LOCAL_GROUPS).forEach(content::includeGroupAndSubgroups)
            } }
            mavenCentral()
            mavenSonatype()
            mavenCodeMC()
            mavenCodeMCPublic()
            mavenPaperMC()
            mavenJitpack()
            githubNuVotifier()
            mavenHelpChat()
            mavenDmulloy2()
            mavenXalitoria()
        }

        if (!project.pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform")) kotlin(project)

        reproducible(project)
        aggregate(project)

        project.afterEvaluate {
            name(project)
            publish(project)
            kotlinVersion(project)
        }
    }

    /** The same sources give the same jar, so the build cache is hit across machines and a rebuilt release differs in nothing. */
    private fun reproducible(project: Project) {
        project.tasks.withType(AbstractArchiveTask::class.java).configureEach {
            it.isPreserveFileTimestamps = false
            it.isReproducibleFileOrder = true
        }
    }

    /**
     * A module that holds others (`chat` with `chat:paper`) has no sources, so its `build`, `check` and `test` do nothing.
     * Make them run the parts' tasks: `./gradlew :chat:build` builds, and `:chat:test` tests, the whole of chat. A part
     * that has no such task is left out, and a Kotlin Multiplatform part, which has `allTests` where a JVM one has `test`, is tested through that.
     */
    private fun aggregate(project: Project) {
        if (project.childProjects.isEmpty()) return
        for (name in listOf("build", "check", "test")) {
            project.tasks.matching { it.name == name }.configureEach { task ->
                // Asked when the task graph is built, after every part has applied its plugins.
                task.dependsOn(Callable { project.childProjects.values.mapNotNull { lifecycleTask(it, name) } })
            }
        }
    }

    private fun lifecycleTask(child: Project, name: String): Task? =
        child.tasks.findByName(name) ?: if (name == "test") child.tasks.findByName("allTests") else null

    /**
     * Every repository names the Kotlin plugin version in `kotlin.version` of its `gradle.properties` (settings cannot read
     * the catalog), and the catalog names the one the libraries are built with. A difference is a mistake: say so.
     */
    private fun kotlinVersion(project: Project) {
        val declared = project.findProperty("kotlin.version") as? String ?: return
        val catalog = project.extensions.findByType(VersionCatalogsExtension::class.java)?.find("libs")
            ?.flatMap { it.findVersion("kotlin") }?.map { it.requiredVersion }?.orElse(null) ?: return
        if (declared != catalog) {
            throw GradleException("kotlin.version is $declared in gradle.properties but the catalog's kotlin is $catalog; make them equal.")
        }
    }

    private fun kotlin(project: Project) {
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")
        project.dependencies.add("testImplementation", "org.jetbrains.kotlin:kotlin-test")

        project.extensions.configure(JavaPluginExtension::class.java) { java ->
            java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
            java.sourceCompatibility = JavaVersion.VERSION_25
            java.targetCompatibility = JavaVersion.VERSION_25
            project.extensions.getByType(SourceSetContainer::class.java).getByName("main").java.srcDirs("java")
        }

        project.extensions.configure(KotlinJvmProjectExtension::class.java) { ext ->
            ext.jvmToolchain(25)
            ext.compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
            ext.compilerOptions.freeCompilerArgs.addAll(
                "-opt-in=kotlin.time.ExperimentalTime",
                "-opt-in=kotlin.uuid.ExperimentalUuidApi",
                "-opt-in=kotlin.concurrent.atomics.ExperimentalAtomicApi",
                "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
                "-opt-in=kotlinx.serialization.InternalSerializationApi",
                // Language previews that every module is written against. They change how ordinary Kotlin reads:
                //  - collection literals: `[a, b]` is a List and `[]` an empty one;
                //  - name-based destructuring: `val [a, b] = pair` for positions (Pair, Triple), `val (x, y) = point` by property name;
                //  - context-sensitive resolution: an enum entry or sealed member needs no qualifier where the expected type is known.
                "-Xcollection-literals",
                "-Xname-based-destructuring=complete",
                "-Xcontext-sensitive-resolution",
            )
            ext.sourceSets.getByName("main").let { set ->
                set.kotlin.srcDirs("kotlin")
                set.resources.srcDirs("resources")
            }
            ext.sourceSets.getByName("test").let { set ->
                set.kotlin.srcDirs("test")
                set.resources.srcDirs("test-resources")
            }
            // The catalog is not there yet while a settings-level plugin request is applied, so it is read afterwards.
            project.afterEvaluate {
                project.extensions.findByType(VersionCatalogsExtension::class.java)?.find("libs")
                    ?.flatMap { it.findVersion("kotlin") }
                    ?.ifPresent { ext.coreLibrariesVersion = it.requiredVersion }
            }
        }

        project.tasks.withType(JavaCompile::class.java).configureEach { it.options.encoding = "UTF-8" }
        project.tasks.named("processResources") { (it as org.gradle.language.jvm.tasks.ProcessResources).duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    }

    /**
     * Names every jar `xali-<module>[-<part>]-<version>[-<classifier>].jar` in `build/libs` (of the root, per module, in the
     * workspace). The classifier says which platform a plugin's jar is for and is otherwise left empty, so a library
     * publishes as an ordinary Maven artifact:
     *
     * | Project | `jar` or `shadowJar` |
     * |---|---|
     * | the module itself (`util`) | `xali-util-0.1.0.jar` |
     * | a platform of a plugin (`chat:paper`) | `xali-chat-0.7.1-paper.jar` |
     * | any other part (`chat:api`, `chat:common`) | `xali-chat-api-0.7.1.jar` |
     *
     * Any other jar task names its own kind: `sourcesJar` is `-sources`, a multiplatform target's `paperJar` is `-paper`.
     */
    private fun name(project: Project) {
        val workspace = project.rootProject.isWorkspace()
        val module = if (!workspace) project.rootProject else if (project.parent == project.rootProject) project else project.parent!!
        val holdsOthers = project.childProjects.isNotEmpty()
        val platform = project.name in PLATFORMS
        val libs = if (workspace) project.rootProject.layout.buildDirectory.dir("libs/${module.name}") else module.layout.buildDirectory.dir("libs")
        val part = if (project == module || platform) module.name else "${module.name}-${project.name}"

        project.tasks.withType(Jar::class.java).configureEach { jar ->
            jar.enabled = jar.enabled && !holdsOthers
            jar.destinationDirectory.set(libs)
            jar.archiveBaseName.set(PREFIX)
            jar.archiveAppendix.set(part)
            jar.archiveVersion.set(project.provider { project.version.toString() })
            jar.archiveClassifier.set(
                when {
                    jar.name == "jar" || jar.name == "shadowJar" -> if (platform) project.name else ""
                    else -> jar.name.removeSuffix("Jar").replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()
                }
            )
        }
    }

    /**
     * A library that applies the Maven publish plugin publishes its main jar, with no classifier and with its sources, unless
     * it set up its own, to the `xali` repository: `maven.xali.host/releases`, or `/snapshots` for a `-SNAPSHOT` version.
     * Credentials come from `MAVEN_USER` and `MAVEN_TOKEN`, or the Gradle properties `maven.user` and `maven.token`.
     *
     * The releases repository accepts overwrites, and servers keep what they downloaded, so publishing a version that is
     * already there is refused (`checkNotPublished`) unless `-Pxali.overwrite=true` says it is meant.
     */
    private fun publish(project: Project) {
        if (!project.pluginManager.hasPlugin("maven-publish")) return
        project.extensions.configure(PublishingExtension::class.java) { publishing ->
            if (publishing.publications.isEmpty()) {
                if (project.extensions.findByType(JavaPluginExtension::class.java) != null) {
                    project.extensions.configure(JavaPluginExtension::class.java) { it.withSourcesJar() }
                }
                publishing.publications.create("mavenJava", MavenPublication::class.java) { it.from(project.components.getByName("java")) }
            }
            if (publishing.repositories.findByName(REPOSITORY) != null) return@configure
            val snapshot = project.version.toString().endsWith("-SNAPSHOT")
            val root = "https://maven.xali.host/" + if (snapshot) "snapshots" else "releases"
            publishing.repositories.maven { repository ->
                repository.name = REPOSITORY
                repository.setUrl(root)
                repository.credentials { credentials ->
                    credentials.username = project.property("MAVEN_USER", "maven.user")
                    credentials.password = project.property("MAVEN_TOKEN", "maven.token")
                }
            }
            if (snapshot) return@configure
            val check = project.tasks.register("checkNotPublished", CheckNotPublished::class.java) { task ->
                task.group = "publishing"
                task.description = "Fails when this version is already on $root."
                task.repository.set(root)
                task.coordinates.set(project.provider { "${project.group}:${project.name}:${project.version}" })
                task.overwrite.set(project.providers.gradleProperty("xali.overwrite").map { it.toBoolean() }.orElse(false))
            }
            project.tasks.withType(PublishToMavenRepository::class.java).configureEach { task ->
                if (task.repository.name == REPOSITORY) task.dependsOn(check)
            }
        }
    }

    /** Two jars of one build with the same file name would overwrite each other; fail before either is written. */
    private fun check(root: Project) {
        if (root.gradle.extensions.extraProperties.has(CHECKED)) return
        root.gradle.extensions.extraProperties[CHECKED] = true
        root.gradle.taskGraph.whenReady { graph ->
            val clashes = graph.allTasks.filterIsInstance<Jar>().filter { it.enabled }
                .groupBy { it.archiveFile.get().asFile }.filterValues { it.size > 1 }
            if (clashes.isNotEmpty()) {
                throw GradleException(
                    "Jar tasks write the same file:\n" + clashes.entries.joinToString("\n") { (file, tasks) ->
                        "  ${file.name}: ${tasks.joinToString { it.path }}"
                    },
                )
            }
        }
    }

    private companion object {
        const val APPLIED = "xali.conventions.applied"
        const val CHECKED = "xali.conventions.checked"
        const val PREFIX = "xali"
        const val REPOSITORY = "xali"
        val PLATFORMS = Kind.entries.map { it.dir }.toSet()
    }
}

/** Whether the modules are nested in one build (`xali.workspace=true` in the root's `gradle.properties`). */
internal fun Project.isWorkspace(): Boolean = (findProperty("xali.workspace") as? String)?.toBoolean() == true

/**
 * The project next to this one with the given name, `sibling("common")` in `chat:paper` being `chat:common` in the
 * workspace and `:common` in the chat repository on its own.
 */
fun Project.sibling(name: String): Project = parent!!.project(name)
