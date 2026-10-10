// Loads a plugin's libraries when the plugin loads, instead of bundling them. One loader per platform, both plain Java:
//  - mc.xalitoria.loader.paper.LibraryLoader is a Paper PluginLoader, named in paper-plugin.yml
//    (`paper { loader = "mc.xalitoria.loader.paper.LibraryLoader" }`) and fed by `paperLibrary(...)`.
//  - mc.xalitoria.loader.velocity.LibraryLoader downloads what the plugin lists in /velocity-libraries.json (written by
//    `velocityLibraries(...)` from buildSrc) and adds it to the plugin's class loader.
// A plugin shades this module and excludes the other platform's package from its jar, so each jar carries one loader.
// Both APIs are compile-only here; nothing from either reaches a plugin that doesn't run on it.
group = "mc.xalitoria"
version = "0.1.0"

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.velocity.api)
}
