package mc.xalitoria.gradle

/** `"text"` for YAML and JSON alike: a double-quoted string, which both read the same way. */
internal fun quote(text: String): String = buildString {
    append('"')
    for (c in text) when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c == '\n' -> append("\\n")
        c == '\r' -> append("\\r")
        c == '\t' -> append("\\t")
        c < ' ' -> append("\\u%04x".format(c.code))
        else -> append(c)
    }
    append('"')
}

/** What `paper-plugin.yml` says for [extension]; [loader] is the loader class to name in it, if there is one. */
internal fun paperDescriptor(extension: PaperExtension, name: String, version: String, description: String?, loader: String?): String =
    buildString {
        fun line(key: String, value: String?) {
            if (value != null) appendLine("$key: $value")
        }

        fun list(key: String, values: List<String>) {
            if (values.isEmpty()) return
            appendLine("$key:")
            values.forEach { appendLine("  - ${quote(it)}") }
        }

        fun dependencies(key: String, dependencies: Dependencies) {
            if (dependencies.all.isEmpty()) return
            appendLine("  $key:")
            dependencies.all.forEach { (name, dependency) ->
                appendLine("    ${quote(name)}:")
                appendLine("      load: ${dependency.load}")
                appendLine("      required: ${dependency.required}")
                appendLine("      join-classpath: ${dependency.joinClasspath}")
            }
        }

        line("api-version", extension.apiVersion?.let(::quote))
        line("name", quote(name))
        line("version", quote(version))
        line("main", quote(extension.main!!))
        line("bootstrapper", extension.bootstrapper?.let(::quote))
        line("loader", loader?.let(::quote))
        line("load", extension.load?.name)
        line("description", description?.let(::quote))
        list("authors", extension.authors)
        list("contributors", extension.contributors)
        line("website", extension.website?.let(::quote))
        line("prefix", extension.prefix?.let(::quote))
        line("folia-supported", extension.foliaSupported?.toString())
        line("has-open-classloader", extension.hasOpenClassloader?.toString())
        list("provides", extension.provides)
        if (extension.serverDeps.all.isNotEmpty() || extension.bootstrapDeps.all.isNotEmpty()) {
            appendLine("dependencies:")
            dependencies("bootstrap", extension.bootstrapDeps)
            dependencies("server", extension.serverDeps)
        }
    }

/** What `velocity-plugin.json` says for [extension]; [main] is the loader if there is one, else the plugin's own main. */
internal fun velocityDescriptor(extension: VelocityExtension, id: String, name: String, version: String, description: String?, main: String): String {
    val fields = buildList {
        add("\"id\": ${quote(id)}")
        add("\"name\": ${quote(name)}")
        add("\"version\": ${quote(version)}")
        description?.let { add("\"description\": ${quote(it)}") }
        extension.url?.let { add("\"url\": ${quote(it)}") }
        add("\"authors\": [${extension.authors.joinToString(", ", transform = ::quote)}]")
        add(
            "\"dependencies\": [${
                extension.deps.all.entries.joinToString(", ") { (dependency, options) ->
                    "{\"id\": ${quote(dependency)}, \"optional\": ${!options.required}}"
                }
            }]"
        )
        add("\"main\": ${quote(main)}")
    }
    return fields.joinToString(",\n  ", prefix = "{\n  ", postfix = "\n}\n")
}
