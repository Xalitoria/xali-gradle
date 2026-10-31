# xali-gradle

**`mc.xalitoria.minecraft`** is a Gradle plugin that describes a Paper or Velocity plugin and gets its libraries to the
server. It covers what plugin-yml did for the descriptors, adds a loader for the libraries on both platforms and adds
tasks to run the plugin on a dev server. Only Paper and Velocity are supported, and its DSL is plain Kotlin.

## Installation

The plugin is a separate Gradle build in this folder. The main build includes it through
`pluginManagement { includeBuild("xali-gradle") }` in `settings.gradle.kts`, so it is built before any module uses it.

Declare it once in the root build script, so every module shares one copy:

**File** `build.gradle.kts`

```kotlin
plugins {
    id("mc.xalitoria.minecraft") apply false
}
```

Apply it in the module that builds a plugin:

**File** `chat/build.gradle.kts`

```kotlin
plugins {
    id("mc.xalitoria.minecraft")
    alias(libs.plugins.shadow)      // optional: needed for the loader, see below
}
```

## Configuration

### The blocks

`paper { }` and `velocity { }` describe the plugin. A platform is in use once its block has a `main`. The blocks go into
the build script of the module, or of a submodule:

- A plain project with a block is the plugin. Both blocks in one project make one jar for both platforms.
- A module whose submodule is named after the platform (`chat` with `chat/paper` and `chat/velocity`) only describes it.
  The submodule is the plugin and takes the module's block. A block in the submodule's own script wins.
- A Kotlin Multiplatform project uses a JVM target named after each platform (`jvm("paper")`, `jvm("velocity")`), which
  builds one jar each. `vote` is laid out this way.

**File** `vote/build.gradle.kts`

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
    main = "mcv.xalitoria.vote.XaliVote"
    url = "https://xalitoria.com"
    author = "audizian"
    dependencies { register("nuvotifier") { required = true } }
}
```

Each platform gets its descriptor written into the jar's resources: `paper-plugin.yml` and `velocity-plugin.json` (tasks
`generatePaperPluginDescription` and `generateVelocityPluginDescription`).

`RelativeLoad.BEFORE` makes the dependency load before the plugin, `AFTER` makes it load after the plugin and `OMIT`
(the default) leaves the ordering out. A dependency is required and on the plugin's classpath unless `required` or `joinClasspath` say
otherwise.

Both blocks also take:

| Setting | Meaning |
|---|---|
| `relocateSources` | Also read sources from `kotlin`, `java`, `resources`, `test` and `test-resources` next to the build script (`paper/kotlin` and so on in a multiplatform project, one folder per target). **Off by default**, so a project keeps the standard `src/main/...` layout unless it asks. |
| `loader { }` | Options for the loader, see below. |
| `server { }` | The dev server, see [The dev server](#the-dev-server). |

### Loading libraries

Everything declared with **`api(...)`** is a library: the server downloads it when the plugin loads, and it stays out of
a shaded jar. `implementation(...)` is shaded as usual; the shadow plugin is not otherwise touched. Project dependencies
and files in `api` are not libraries, since there is nowhere to download them from.

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
whole dependency tree is kept out of `shadowJar`. What the platform itself provides (Adventure, Gson, slf4j) is never
downloaded either.

#### loader.json

**File** `loader.json`

```json
{
  "main": "mcv.xalitoria.vote.XaliVote",
  "repositories": { "MavenRepo": "https://maven-central.storage-download.googleapis.com/maven2/" },
  "dependencies": [ "com.charleskorn.kaml:kaml-jvm:0.104.0" ],
  "libraries": [ { "coordinates": "com.charleskorn.kaml:kaml-jvm:0.104.0", "sha256": "..." } ]
}
```

- `repositories`: every Maven repository with a web address that the project, its parents and the projects below it
  declare, without repeats. Maven Central is swapped for a mirror (`loader { centralProxy = false }` keeps it).
- `dependencies`: the `api(...)` libraries as resolved. Paper's own resolver reads this, downloads them with their
  dependencies, and shares them between plugins.
- `libraries`: those and everything they need, with checksums. Velocity cannot resolve anything itself, so its loader
  reads this. It is filled in only for a Velocity plugin.
- `main`: the Velocity plugin's real main class.

#### The loader class

By default the loader sits in the same package as the platform's `main` and is named after it with `Loader` added
(`mc.xalitoria.vote.XaliVote` gets `mc.xalitoria.vote.XaliVoteLoader`). The class is not generated source. It is a
precompiled Java class in this plugin (`xali-gradle/java`) whose name, and every reference to it, is rewritten into the
plugin's own. The jar therefore has its own copy and nothing depends on this plugin at run time.

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
- **Velocity**: the loader is the plugin Velocity sees. Once the proxy initializes, it downloads each of `libraries` into
  the plugin's `libraries` folder (each is checked against its SHA-256 and fetched again if it does not match), adds
  them to the classpath with `PluginManager.addToClasspath` and then creates the real plugin. It has to wait for the
  event, because Velocity accepts classpath changes only for a plugin that has been created. The real plugin is a plain
  class:

  ```kotlin
  class XaliVote(
      server: ProxyServer, logger: Logger, dataDirectory: Path,
      plugin: Any,                          // the loader; register event listeners with it (optional parameter)
  ) : Runnable, AutoCloseable {             // run() once the proxy initialized, close() on shutdown (both optional)
      override fun run() { server.eventManager.register(plugin, this) }
      override fun close() {}
  }
  ```

  Since the plugin is created only after the libraries are in, the Kotlin standard library can be one of them.

The loaders target Java 25, as Paper 26 and Velocity 4 do.

## Usage

### The dev server

Every platform in use also gets the tasks to test the plugin on a server. The jar the project builds (`shadowJar` if the
shadow plugin is applied, else `jar`) is installed into the server's `plugins`, and the server is started.

| Task | Effect |
|---|---|
| `downloadServer` | Fetches the pinned server build into the server's folder. Skipped when it is already there; verified by its SHA-256. |
| `downloadServerPlugins` | Fetches the plugins listed in `server { }` into `plugins`. |
| `installPlugin` | Copies the jar into `plugins`, replacing earlier builds of the same plugin. The jar is written under a temporary name and moved into place, so a server that is still running keeps reading the old file. |
| `runServer` / `runProxy` | Runs all of the above, then starts a Paper or Folia server (`runServer`) or a Velocity proxy (`runProxy`) from that folder. |

A project that hosts several plugins (both blocks in one project, or a multiplatform project) has the platform in the
names: `installPaperPlugin`, `runVelocityProxy`.

**File** `build.gradle.kts`

```kotlin
paper {
    server {
        directory = "run"                   // default: `run` in the project that builds the plugin; anything file(...) accepts
        software = Software.FOLIA           // PAPER (default), FOLIA; Velocity's block is always VELOCITY
        memory = "2G"                       // default 2G, 512M for Velocity
        hotSwap = true                      // JetBrains Runtime with enhanced class redefinition + a debug port; never for Velocity
        debugPort = 5005
        javaVersion = 25
        arguments = listOf("--nogui")
        acceptEula = true                   // writes eula.txt
        plugin("PlaceholderAPI.jar", "https://...")   // fetched into plugins by downloadServerPlugins
    }
}
```

The server build is pinned by checksum in the `Software` enum. To run another one, give all of `version`, `build` and
`sha256` (the download is `fill-data.papermc.io/v1/objects/<sha256>/<software>-<version>-<build>.jar`). Projects that
point `directory` at the same folder share the server: each installs its own jar there, and whatever was installed
before stays. Plugins that cannot be downloaded without an account are put in `plugins` by hand.

> [!NOTE]
> The root build points `chat`, `friends` and `menu` at one shared server, see the
> [root README](../README.md#dev-servers).

## Troubleshooting

### The Velocity plugin fails with "Can't create plugin"

Velocity needs an `@Inject` constructor or a loader. A plugin without any `api(...)` library gets no loader, and a plain
class without `@Inject` cannot be created. Declare at least one library with `api(...)` (the Kotlin standard library
will do) with the shadow plugin applied, so the loader is generated.

### A library is missing at run time

It was declared with `implementation(...)` in a project that does not shade it, or it is a project dependency declared
with `api(...)`, which is never a library. Declare external libraries with `api(...)` and project dependencies with
`implementation(...)`.

### Two projects collide as `mc.xalitoria:api`

Gradle identifies projects by `group:name`. A submodule named `api` needs a group of its own (`chat/api` sets one), or
it collides with `:menu:api`.

## Development

| File | Role |
|---|---|
| `xali-gradle/kotlin/.../MinecraftPlugin.kt` | The plugin: registers the blocks and wires the tasks. |
| `Extensions.kt` | The DSL: `paper { }`, `velocity { }`, dependencies and loader options. |
| `Bundles.kt` | Which projects form a plugin and where their sources, resources and `api` dependencies are (a plain JVM project, or a multiplatform one). |
| `Descriptors.kt` | The text of `paper-plugin.yml` and `velocity-plugin.json`. |
| `Libraries.kt` | Resolving the `api(...)` libraries, with checksums and exclusions. |
| `Tasks.kt` | Writing files into the jar, and renaming the loader classes into the plugin's package (ASM). |
| `Server.kt` | The dev server tasks and the pinned server builds. |
| `xali-gradle/java/.../loader` | The precompiled `PaperLoader` and `VelocityLoader`. |

```console
user $ ./gradlew -p xali-gradle build
```

The plugin is compiled before the main build starts, so a change to it takes effect on the next Gradle invocation. The
Kotlin version is written in `xali-gradle/build.gradle.kts` (twice) as well as in the root files, see the
[root README](../README.md#compiler-options).

## See also

- [Root README](../README.md) — modules, source layout and dev servers
- [Gradle: Writing custom plugins](https://docs.gradle.org/current/userguide/custom_plugins.html)
- [Paper plugin.yml](https://docs.papermc.io/paper/dev/getting-started/paper-plugins/) — the descriptor format
