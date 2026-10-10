package mc.xalitoria.loader.paper;

import com.google.gson.Gson;
import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Puts the libraries a plugin declares with {@code paperLibrary(...)} on its classpath when it loads. The plugin yml
 * Gradle plugin lists them in {@code /paper-libraries.json} ({@code generateLibrariesJson = true}); Paper's own resolver
 * downloads them, with their dependencies, into the server's {@code libraries} folder and shares them between plugins.
 */
@SuppressWarnings("UnstableApiUsage")
public class LibraryLoader implements PluginLoader {

    private static final Gson GSON = new Gson();

    @Override
    public void classloader(final @NotNull PluginClasspathBuilder classpathBuilder) {
        final MavenLibraryResolver resolver = new MavenLibraryResolver();
        final PluginLibraries libraries = load();

        for (final Dependency dependency : libraries.asDependencies()) {
            resolver.addDependency(dependency);
        }

        for (final RemoteRepository repository : libraries.asRepositories()) {
            resolver.addRepository(repository);
        }

        classpathBuilder.addLibrary(resolver);
    }

    private PluginLibraries load() {
        try (final InputStream inputStream = getClass().getResourceAsStream("/paper-libraries.json")) {
            if (inputStream == null) {
                throw new RuntimeException("Resource /paper-libraries.json not found inside JAR");
            }

            try (final InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                final PluginLibraries libraries = GSON.fromJson(reader, PluginLibraries.class);
                if (libraries == null) {
                    throw new RuntimeException("Failed to deserialize /paper-libraries.json (file is empty)");
                }
                return libraries;
            }
        } catch (final Exception e) {
            throw new RuntimeException("Failed to load plugin libraries from paper-libraries.json", e);
        }
    }

    public record PluginLibraries(Map<String, String> repositories, List<String> dependencies) {
        public List<Dependency> asDependencies() {
            if (dependencies == null) return List.of();
            final List<Dependency> list = new ArrayList<>(dependencies.size());
            for (final String d : dependencies) {
                list.add(new Dependency(new DefaultArtifact(d), null));
            }
            return list;
        }

        public List<RemoteRepository> asRepositories() {
            if (repositories == null) return List.of();
            final List<RemoteRepository> list = new ArrayList<>(repositories.size());
            for (final Map.Entry<String, String> entry : repositories.entrySet()) {
                // Paper's resolver only speaks HTTP; the generated mavenLocal() entry is a file: URL and would fail resolution.
                if (entry.getValue().startsWith("file:")) continue;
                list.add(new RemoteRepository.Builder(entry.getKey(), "default", entry.getValue()).build());
            }
            return list;
        }
    }
}
