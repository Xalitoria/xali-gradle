# xali-minecraft (Gradle plugin)

`mc.xalitoria.minecraft` describes a Paper or Velocity plugin and gets its libraries to the server. It covers what
plugin-yml did for the descriptors, and adds a loader for the libraries on both platforms. It supports Paper and
Velocity only, and its DSL is plain Kotlin.

It is a separate Gradle build in this folder, included by the main build through `pluginManagement { includeBuild("plugin") }`.
Declare it once in the root build with `id("mc.xalitoria.minecraft") apply false` so every module shares one copy, then
apply it where a plugin is built:

```kotlin
plugins {
    id("mc.xalitoria.minecraft")
    alias(libs.plugins.shadow)      // optional: needed for the loader, see below
}
```

## The blocks

`paper { }` and `velocity { }` describe the plugin. A platform is in use once its block has a `main`. Put the blocks in
the build script of the module, or of a submodule:

- A plain project with a block is the plugin. Both blocks in one project make one jar for both platforms.
- A module whose submodule is named after the platform (`chat` with `chat/paper` and `chat/velocity`) only describes it;
  the submodule is the plugin and takes the module's block. A block in the submodule's own script wins.
- A Kotlin Multiplatform project uses a JVM target named after each platform (`jvm("paper")`, `jvm("velocity")`), which
  builds one jar each. `vote` is laid out this way.

```kotlin
import mc.xalitoria.gradle.Load
import mc.xalitoria.gradle.RelativeLoad

paper {
    name = "XaliVote"                       // default: the project's name (the module's, for a submodule)
    main = "mc.xalitoria.vote.XaliVote"
    version = "1.0"                         // default: the project's version
    description = "..."                     // default: the project's description
    apiVersion = "1.21"
    author = "audizian"                     // or authors += "..."
    website = "https://xalitoria.com"
    prefix = "XaliVote"
    load = Load.POSTWORLD                   // STARTUP | POSTWORLD
    foliaSupported = true
    contributors += "..."; provides += "..."; bootstrapper = "..."; hasOpenClassloader = false
    serverDependencies {                    // and bootstrapDependencies { }
        register("NuVotifier") { load = RelativeLoad.BEFORE; required = true; joinClasspath = true }
    }
}

velocity {
    name = "XaliVote"
    id = "xalivote"                         // default: the name, lowercased
    main = "mcv.xalitoria.vote.XaliVoteVelocity"
    url = "https://xalitoria.com"
    author = "audizian"
    dependencies { register("nuvotifier") { required = true } }
}
```

Each platform gets its descriptor written into the jar's resources: `paper-plugin.yml` and `velocity-plugin.json`
(tasks `generatePaperPluginDescription` and `generateVelocityPluginDescription`).

Both blocks also take:

| Setting | Meaning |
|---|---|
| `relocateSources` | Also read sources from `kotlin`, `java`, `resources`, `test` and `test-resources` next to the build script (`paper/kotlin` and so on in a multiplatform project, one folder per target). **Off by default**, so a project keeps the standard `src/main/...` layout unless it asks. |
| `loader { }` | Options for the loader below. |

## Loading libraries

Everything declared with **`api(...)`** is a library: the server downloads it when the plugin loads, and it stays out of
a shaded jar. `implementation(...)` is shaded as usual; the shadow plugin is not otherwise touched. (Project
dependencies and files in `api` are not libraries, since there is nowhere to download them from.)

```kotlin
dependencies {
    api(libs.kaml)                          // downloaded when the plugin loads
    implementation(project(":common"))      // shaded
}
```

When the **shadow plugin is applied** and `api(...)` declares at least one library, the plugin writes into the jar:

- `loader.json`, listing what to load, and
- a **loader class**, named in the descriptor (`loader:` in `paper-plugin.yml`, `main` in `velocity-plugin.json`).

Without the shadow plugin, or with no libraries, nothing is generated and the plugin loads as written. The libraries'
whole dependency tree is kept out of `shadowJar` (what the platform itself provides, like Adventure, Gson and slf4j,
is never downloaded either).

### `loader.json`

```json
{
  "main": "mcv.xalitoria.vote.XaliVoteVelocity",
  "repositories": { "MavenRepo": "https://maven-central.storage-download.googleapis.com/maven2/" },
  "dependencies": [ "com.charleskorn.kaml:kaml-jvm:0.104.0" ],
  "libraries": [ { "coordinates": "com.charleskorn.kaml:kaml-jvm:0.104.0", "sha256": "..." } ]
}
```

- `repositories`: every Maven repository with a web address that the project, its parents and the projects below it
  declare, without repeats. Maven Central is swapped for a mirror (`loader { centralProxy = false }` to keep it).
- `dependencies`: the `api(...)` libraries as resolved. Paper's own resolver reads this, downloads them with their
  dependencies, and shares them between plugins.
- `libraries`: those and everything they need, with checksums. Velocity can't resolve anything itself, so its loader
  reads this. It is only filled in for a Velocity plugin.
- `main`: the Velocity plugin's real main class.

### The loader class

By default it sits in the same package as the platform's `main` and is named after it with `Loader` added
(`mc.xalitoria.vote.XaliVote` gets `mc.xalitoria.vote.XaliVoteLoader`). The class is not generated source: it is a
precompiled Java class in this plugin whose name (and every reference to it) is rewritten into the plugin's own, so the
jar has its own copy and nothing depends on this plugin at run time.

```kotlin
paper {
    loader {
        enabled = true                      // false: no loader for this platform
        name = "Boot"                       // default: <Main>Loader
        packageName = "demo.boot"           // default: the main class's package
        className = "demo.MyLoader"         // Paper only: use a loader you wrote and generate none
        centralProxy = true
        exclude += "com.example"            // never list: group, group:name, either with a trailing *
    }
}
```

- **Paper**: the loader is a `PluginLoader` that reads `loader.json` and adds `dependencies` to the plugin's classpath.
  The plugin class is unchanged.
- **Velocity**: the loader is the plugin Velocity sees. Once the proxy initializes it downloads each of `libraries` into
  the plugin's `libraries` folder (each is checked against its SHA-256 and re-fetched if it doesn't match), adds them to
  the classpath with `PluginManager.addToClasspath`, and then creates the real plugin. It has to wait for the event,
  because Velocity only accepts classpath changes for a plugin that has been created. The real plugin is a plain class:

  ```kotlin
  class XaliVoteVelocity(
      server: ProxyServer, logger: Logger, dataDirectory: Path,
      plugin: Any,                          // the loader; register event listeners with it (optional parameter)
  ) : Runnable, AutoCloseable {             // run() once the proxy initialized, close() on shutdown (both optional)
      override fun run() { server.eventManager.register(plugin, this) }
      override fun close() {}
  }
  ```

  Since the plugin is only created after the libraries are in, the Kotlin standard library can be one of them.

The loaders target Java 25, as Paper 26 and Velocity 4 do.
