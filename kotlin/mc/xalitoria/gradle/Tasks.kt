package mc.xalitoria.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper

/** Writes [content] to [fileName] in [outputDirectory]: a plugin descriptor or `loader.json`. */
@DisableCachingByDefault(because = "Writes one small file, which is quicker than a cache lookup")
abstract class WriteFile : DefaultTask() {
    @get:Input
    abstract val fileName: Property<String>

    @get:Input
    abstract val content: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        outputDirectory.get().file(fileName.get()).asFile.apply { parentFile.mkdirs() }.writeText(content.get())
    }
}

/**
 * Writes the loader classes into the plugin under their own names. The loaders are compiled once, in this plugin, and
 * what this does is rename each class (and every reference to it) to the name the plugin chose, so each plugin gets
 * its own copy in its own package, and nothing it ships depends on this plugin.
 */
@DisableCachingByDefault(because = "Copies two small precompiled classes under new names, which is quicker than a cache lookup")
abstract class GenerateLoaderClasses : DefaultTask() {
    /** Template class (a name in `mc.xalitoria.gradle.loader`) to the fully qualified name its copy gets. */
    @get:Input
    abstract val classes: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile.apply { deleteRecursively(); mkdirs() }
        classes.get().forEach { (template, target) ->
            val from = "mc/xalitoria/gradle/loader/$template"
            val to = target.replace('.', '/')
            val bytes = GenerateLoaderClasses::class.java.getResourceAsStream("/$from.class")?.use { it.readBytes() }
                ?: error("The loader $template is missing from the Gradle plugin")
            val writer = ClassWriter(0)
            ClassReader(bytes).accept(ClassRemapper(writer, SimpleRemapper(mapOf(from to to))), 0)
            output.resolve("$to.class").apply { parentFile.mkdirs() }.writeBytes(writer.toByteArray())
        }
    }
}

/**
 * Fails when [coordinates] (`group:name:version`) already has a pom on [repository]. A release can be overwritten on
 * `maven.xali.host`, but servers cache what they downloaded, so the second upload of a version reaches some servers and
 * not others. [overwrite] (`-Pxali.overwrite=true`) says it is meant.
 */
@DisableCachingByDefault(because = "Asks a server")
abstract class CheckNotPublished : DefaultTask() {
    @get:Input
    abstract val repository: Property<String>

    @get:Input
    abstract val coordinates: Property<String>

    @get:Input
    abstract val overwrite: Property<Boolean>

    @TaskAction
    fun check() {
        val parts = coordinates.get().split(":")
        val (group, name, version) = parts
        val pom = "${repository.get().trimEnd('/')}/${group.replace('.', '/')}/$name/$version/$name-$version.pom"
        when (val status = headStatus(pom)) {
            404 -> {}
            200 -> if (overwrite.get()) logger.warn("Overwriting ${coordinates.get()}: servers that downloaded it keep their copy.") else {
                throw GradleException("${coordinates.get()} is already on ${repository.get()}. Raise the version, or pass -Pxali.overwrite=true to replace it (servers keep what they downloaded).")
            }
            else -> throw GradleException("Could not tell whether ${coordinates.get()} is on ${repository.get()} (${status ?: "no answer"} for $pom).")
        }
    }
}
