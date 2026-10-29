package mc.xalitoria.gradle

/** When a Paper plugin loads relative to the worlds. */
enum class Load { STARTUP, POSTWORLD }

/** Where a dependency loads relative to the plugin that needs it. */
enum class RelativeLoad { BEFORE, AFTER, OMIT }

/** A plugin this one needs or can use, in `paper { serverDependencies { ... } }` and `velocity { dependencies { ... } }`. */
open class Dependency {
    /** Whether the server refuses to start the plugin without it. */
    var required: Boolean = true

    /** Paper only: whether it loads before or after this plugin. */
    var load: RelativeLoad = RelativeLoad.OMIT

    /** Paper only: whether this plugin sees its classes. */
    var joinClasspath: Boolean = true
}

open class Dependencies {
    internal val all = linkedMapOf<String, Dependency>()

    /** Adds the plugin named [name], or changes it if it is already there. */
    fun register(name: String, block: Dependency.() -> Unit = {}): Dependency =
        all.getOrPut(name, ::Dependency).apply(block)
}

/**
 * How a plugin's libraries reach the server. Everything declared with `api(...)` is a library: it stays out of a
 * shaded jar, and a small loader class downloads it when the plugin loads, with `loader.json` listing what to get
 * and from where. `implementation(...)` is shaded as usual and the shadow plugin is not touched.
 *
 * The loader is only written when the shadow plugin is applied (so there is a jar for it to go in) and there is at
 * least one library; otherwise nothing is generated and the plugin loads as written.
 */
open class LoaderOptions {
    /** Switch the generated loader off altogether. */
    var enabled: Boolean = true

    /** The loader's class name; by default the main class's name followed by `Loader`. */
    var name: String? = null

    /** The loader's package; by default the main class's. */
    var packageName: String? = null

    /** Paper only: a loader you wrote yourself, named in paper-plugin.yml instead of a generated one. */
    var className: String? = null

    /**
     * Download from a mirror of Maven Central rather than Central itself. Paper asks plugins to, since its servers
     * are the ones doing the downloading.
     */
    var centralProxy: Boolean = true

    /**
     * Libraries the platform already has, which are never listed: `group`, `group:name`, or either with a trailing
     * `*`. Adventure, Gson, slf4j and the annotations are always left out.
     */
    val exclude: MutableList<String> = mutableListOf()
}

/** What `paper { }` and `velocity { }` share. A platform is in use when its [main] is set. */
abstract class PlatformExtension {
    /** The plugin's main class. */
    var main: String? = null

    /** Defaults to the project's name. */
    var name: String? = null

    /** Defaults to the project's version. */
    var version: String? = null

    /** Defaults to the project's description. */
    var description: String? = null

    var website: String? = null

    val authors: MutableList<String> = mutableListOf()

    /** Shorthand for adding to [authors]. */
    var author: String?
        get() = authors.firstOrNull()
        set(value) {
            if (value != null) authors += value
        }

    /**
     * Also read sources from `kotlin`, `java`, `resources`, `test` and `test-resources` next to the build script
     * (`<target>/kotlin` and so on in a multiplatform project), instead of only `src/main/...`. Off unless asked for.
     */
    var relocateSources: Boolean = false

    val loader: LoaderOptions = LoaderOptions()

    fun loader(block: LoaderOptions.() -> Unit) {
        loader.apply(block)
    }

    /** The dev server the plugin is installed on and run with. */
    abstract val server: ServerOptions

    fun server(block: ServerOptions.() -> Unit) {
        server.apply(block)
    }
}

open class PaperExtension : PlatformExtension() {
    override val server = ServerOptions(Software.PAPER)

    /** The Minecraft version it was written for. */
    var apiVersion: String? = null
    var prefix: String? = null
    var load: Load? = null
    var foliaSupported: Boolean? = null
    var hasOpenClassloader: Boolean? = null
    var bootstrapper: String? = null
    val contributors: MutableList<String> = mutableListOf()
    val provides: MutableList<String> = mutableListOf()
    internal val serverDeps = Dependencies()
    internal val bootstrapDeps = Dependencies()

    fun serverDependencies(block: Dependencies.() -> Unit) {
        serverDeps.apply(block)
    }

    fun bootstrapDependencies(block: Dependencies.() -> Unit) {
        bootstrapDeps.apply(block)
    }
}

open class VelocityExtension : PlatformExtension() {
    override val server = ServerOptions(Software.VELOCITY)

    /** Lowercase letters, digits, `-` and `_`; by default the name in that form. */
    var id: String? = null
    var url: String? = null
    internal val deps = Dependencies()

    fun dependencies(block: Dependencies.() -> Unit) {
        deps.apply(block)
    }
}
