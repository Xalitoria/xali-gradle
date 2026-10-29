package mc.xalitoria.gradle

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec

/** A server build the plugin knows by its checksum, so the one a developer runs is always the one that was pinned. */
enum class Software(val id: String, val version: String, val build: Int, val sha256: String, val proxy: Boolean = false) {
    PAPER("paper", "26.2", 129, "b1d8f6bfa1b6101fa8e947b53041cb3bdf5540e7b83b6547ca19ba7edefeb083"),
    FOLIA("folia", "26.2", 7, "128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3"),
    VELOCITY("velocity", "4.2.0", 30, "35a5596a5468a035d8a32c8de5ebb0dc6b8d8f0cc3ff5169d514aca762af8aa8", proxy = true),
}

/**
 * The dev server a platform's plugin is tested on, in `paper { server { } }` and `velocity { server { } }`. Every
 * setting has a default, so the block is only there to change something. Per platform the plugin adds:
 *
 * - `downloadServer`: fetches the pinned server build into [directory] (skipped when it is already there),
 * - `downloadServerPlugins`: fetches the [plugins] into the server's `plugins`,
 * - `installPlugin`: puts the jar of this project there, replacing earlier builds of it,
 * - `runServer` (`runProxy` for Velocity): starts the server with the plugin installed.
 *
 * A project that hosts several plugins (both platforms, or a multiplatform project) gets the platform in the names:
 * `installPaperPlugin`, `runVelocityProxy`.
 */
open class ServerOptions(internal val defaultSoftware: Software) {
    /**
     * The server's folder, anything `file(...)` accepts (relative to the project). By default `run` in the project that
     * builds the plugin. Projects that share a directory share a server, with every plugin installed there.
     */
    var directory: Any? = null

    var software: Software = defaultSoftware

    /**
     * Another build of [software]. The checksum is how the download is verified, so [version], [build] and [sha256]
     * are all given or none of them are.
     */
    var version: String? = null
    var build: Int? = null
    var sha256: String? = null

    /** The JVM's maximum heap; 2G for Paper and Folia, 512M for Velocity. */
    var memory: String? = null

    /** The JVM to run on. */
    var javaVersion: Int = 25

    /**
     * Run Paper and Folia on the JetBrains Runtime, whose DCEVM lets a rebuilt plugin be hot-swapped with far fewer
     * limits, and open [debugPort] for IntelliJ (Remote JVM Debug) to attach to. Never for Velocity.
     */
    var hotSwap: Boolean = true
    var debugPort: Int = 5005

    /** Write `eula.txt` with `eula=true` in the directory, so a Paper or Folia server starts. */
    var acceptEula: Boolean = true

    /** The program arguments; `--nogui` for Paper and Folia, none for Velocity. */
    var arguments: List<String>? = null

    /** Plugins to fetch into the server's `plugins`, as file name to URL. Ones that need an account are put there by hand. */
    val plugins: MutableMap<String, String> = linkedMapOf()

    /** Shorthand for adding to [plugins]. */
    fun plugin(fileName: String, url: String) {
        plugins[fileName] = url
    }
}

private class Pinned(val software: Software, val version: String, val build: Int, val sha256: String) {
    val fileName get() = "${software.id}-$version-$build.jar"
}

private fun ServerOptions.pinned(): Pinned {
    val custom = listOf(version, build, sha256)
    require(custom.all { it == null } || custom.none { it == null }) {
        "server { }: version, build and sha256 go together; set all three or none"
    }
    return if (version == null) Pinned(software, software.version, software.build, software.sha256)
    else Pinned(software, version!!, build!!, sha256!!)
}

/** The tasks that install a bundle's plugin on its dev server and run it. */
internal fun registerServerTasks(bundle: Bundle, kind: Kind, options: ServerOptions, plain: Boolean) {
    val project = bundle.project
    val software = options.software
    val pinned = options.pinned()
    val directory = project.file(options.directory ?: "run")
    val shown = project.rootDir.toPath().relativize(directory.toPath()).toString()
    val infix = if (plain) "" else kind.dir.replaceFirstChar(Char::uppercase)
    val run = if (software.proxy) "Proxy" else "Server"

    val serverJar = File(directory, pinned.fileName)
    val download = project.tasks.register("download${infix}Server") {
        it.group = "run"
        it.description = "Fetches ${software.id} ${pinned.version} build ${pinned.build} into $shown."
        // Another project may share the directory; the file is only ever written once, whoever gets there first.
        it.doNotTrackState("The server folder is shared and written by the server itself")
        val url = "https://fill-data.papermc.io/v1/objects/${pinned.sha256}/${pinned.fileName}"
        val expected = pinned.sha256
        val eula = File(directory, "eula.txt").takeIf { options.acceptEula && !software.proxy }
        it.doLast {
            serverJar.parentFile.mkdirs()
            eula?.writeText("eula=true\n")
            if (serverJar.exists()) return@doLast
            download(url, serverJar)
            val actual = MessageDigest.getInstance("SHA-256").digest(serverJar.readBytes()).joinToString("") { "%02x".format(it) }
            if (actual != expected) {
                serverJar.delete()
                error("${software.id} checksum mismatch: expected $expected, got $actual")
            }
        }
    }

    val pluginsDir = File(directory, "plugins")
    val downloads = options.plugins.toMap()
    val downloadPlugins = project.tasks.register("download${infix}ServerPlugins") {
        it.group = "run"
        it.description = "Fetches the plugins the dev server needs into $shown/plugins."
        it.doNotTrackState("The server folder is shared and written by the server itself")
        it.doLast {
            pluginsDir.mkdirs()
            downloads.forEach { (name, url) ->
                val target = pluginsDir.resolve(name)
                if (!target.exists()) download(url, target)
            }
        }
    }

    val jar: Provider<RegularFile> =
        if (bundle.key.isNotEmpty()) project.tasks.named("${bundle.key}Jar", AbstractArchiveTask::class.java).flatMap { it.archiveFile }
        else if (project.pluginManager.hasPlugin("com.gradleup.shadow")) project.tasks.named("shadowJar", ShadowJar::class.java).flatMap { it.archiveFile }
        else project.tasks.named("jar", AbstractArchiveTask::class.java).flatMap { it.archiveFile }

    val install = project.tasks.register("install${infix}Plugin") {
        it.group = "run"
        it.description = "Installs this plugin's jar into $shown/plugins."
        it.inputs.file(jar)
        // A previous run may still be going (and holding its jar open) when this reruns, so the file is written under a
        // temp name and moved into place atomically: an open ZipFile keeps reading the old (unlinked) inode's bytes
        // rather than a half-old-half-new mix, avoiding a NoClassDefFoundError for a class the old process hadn't
        // lazily loaded yet (e.g. a suspend fun's continuation) once it does. Jars of earlier versions are removed so
        // the server never sees two of the same plugin.
        it.doNotTrackState("The server folder is shared and written by the server itself")
        it.doLast {
            val source = jar.get().asFile
            val target = pluginsDir.apply { mkdirs() }
            val build = VERSIONED.matchEntire(source.name)
            if (build != null) {
                target.listFiles { f -> f != source && VERSIONED.matchEntire(f.name)?.groupValues?.drop(1) == build.groupValues.drop(1) }
                    ?.forEach { old -> old.delete() }
            }
            val temp = File.createTempFile("${source.name}.", ".tmp", target)
            source.copyTo(temp, overwrite = true)
            Files.move(temp.toPath(), target.resolve(source.name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    val launcher = project.extensions.getByType(JavaToolchainService::class.java)
    val hotSwap = options.hotSwap && !software.proxy
    project.tasks.register("run$infix$run", JavaExec::class.java) {
        it.group = "run"
        it.description = "Runs ${software.id} ${pinned.version} in $shown with this plugin installed."
        it.dependsOn(download, downloadPlugins, install)
        it.javaLauncher.set(launcher.launcherFor { spec ->
            spec.languageVersion.set(JavaLanguageVersion.of(options.javaVersion))
            if (hotSwap) spec.vendor.set(JvmVendorSpec.JETBRAINS)
        })
        it.workingDir = directory
        it.classpath = project.files(serverJar)
        it.jvmArgs("-Xmx${options.memory ?: if (software.proxy) "512M" else "2G"}")
        if (hotSwap) {
            it.jvmArgs(
                "-XX:+AllowEnhancedClassRedefinition", "-Dcom.mojang.eula.agree=true",
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:${options.debugPort}",
            )
        }
        it.args(options.arguments ?: if (software.proxy) emptyList() else listOf("--nogui"))
        it.standardInput = System.`in`
    }
}

/** `xali-chat-0.7.0-paper.jar` is the `xali-chat` and `-paper` of some version: the jars of one plugin on one platform. */
private val VERSIONED = Regex("^(.+?)-\\d[\\d.]*(?:-SNAPSHOT)?(.*)\\.jar$")

private fun download(url: String, target: File) {
    URI.create(url).toURL().openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
}
