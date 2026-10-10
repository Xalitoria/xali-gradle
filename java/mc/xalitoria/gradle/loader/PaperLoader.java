package mc.xalitoria.gradle.loader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Template for the loader the Gradle plugin writes into a Paper plugin: the plugin's copy is renamed (see
 * {@code GenerateLoaderClasses}) and named in paper-plugin.yml. It puts the libraries in {@code /loader.json} on the
 * plugin's classpath when the server loads it; Paper's resolver downloads them, with their own dependencies, into the
 * server's {@code libraries} folder and shares them between plugins.
 *
 * <p>Kept to one class with no nested types or Kotlin, since it is copied as a single file and runs before anything
 * the plugin ships is on the classpath.
 */
@SuppressWarnings("UnstableApiUsage")
public final class PaperLoader implements PluginLoader {

    @Override
    public void classloader(final @NotNull PluginClasspathBuilder classpathBuilder) {
        final JsonObject manifest;
        try (final InputStream in = getClass().getResourceAsStream("/loader.json")) {
            if (in == null) throw new IllegalStateException("/loader.json is missing from the plugin jar");
            manifest = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }

        final MavenLibraryResolver resolver = new MavenLibraryResolver();
        for (final var dependency : manifest.getAsJsonArray("dependencies")) {
            resolver.addDependency(new Dependency(new DefaultArtifact(dependency.getAsString()), null));
        }
        for (final Map.Entry<String, com.google.gson.JsonElement> repository : manifest.getAsJsonObject("repositories").entrySet()) {
            resolver.addRepository(new RemoteRepository.Builder(repository.getKey(), "default", repository.getValue().getAsString()).build());
        }
        classpathBuilder.addLibrary(resolver);
    }
}
