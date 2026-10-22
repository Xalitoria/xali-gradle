package mc.xalitoria.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper

/** Writes [content] to [fileName] in [outputDirectory]: a plugin descriptor or `loader.json`. */
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
