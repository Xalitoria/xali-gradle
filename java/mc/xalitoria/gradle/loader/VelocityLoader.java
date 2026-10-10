package mc.xalitoria.gradle.loader;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Template for the entry point the Gradle plugin writes into a Velocity plugin (see {@code GenerateLoaderClasses}).
 * Velocity can't download libraries, so this class does: once the proxy initializes it fetches the ones in
 * {@code /loader.json} into the plugin's {@code libraries} folder, checks each against its SHA-256, adds them to the
 * plugin's classpath, and only then creates the real plugin, named by {@code main} in the same file. It has to wait
 * for the event: Velocity only lets a plugin extend its classpath once the plugin has been created.
 *
 * <p>The real plugin is a plain class with a public constructor taking the {@link ProxyServer}, the {@link Logger},
 * the data directory and this loader (the object to register event listeners with; the last two may be left out).
 * If it is a {@link Runnable} it is run once the proxy has initialized, and if it is an {@link AutoCloseable} it is
 * closed on shutdown.
 *
 * <p>One class with no nested types or Kotlin, since it is copied as a single file and runs before anything the
 * plugin ships is on the classpath.
 */
public final class VelocityLoader {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private Object implementation;

    @Inject
    public VelocityLoader(final ProxyServer server, final Logger logger, final @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    private void loadLibraries(final JsonObject manifest) throws IOException {
        final Path folder = dataDirectory.resolve("libraries");
        Files.createDirectories(folder);
        for (final JsonElement element : manifest.getAsJsonArray("libraries")) {
            final JsonObject library = element.getAsJsonObject();
            final String[] gav = library.get("coordinates").getAsString().split(":");
            final String sha256 = library.get("sha256").getAsString();
            final Path jar = folder.resolve(gav[0] + "." + gav[1] + "-" + gav[2] + ".jar");
            if (!Files.isRegularFile(jar) || !sha256(jar).equals(sha256)) {
                logger.info("Downloading {}", library.get("coordinates").getAsString());
                download(manifest, gav, sha256, jar);
            }
            server.getPluginManager().addToClasspath(this, jar);
        }
    }

    /** Tries each repository in turn; the jar is only put in place once its checksum is right. */
    private void download(final JsonObject manifest, final String[] gav, final String sha256, final Path target) throws IOException {
        final String path = gav[0].replace('.', '/') + "/" + gav[1] + "/" + gav[2] + "/" + gav[1] + "-" + gav[2] + ".jar";
        final Path partial = target.resolveSibling(target.getFileName() + ".part");
        final IOException failure = new IOException("Could not download " + String.join(":", gav) + " from any repository");
        try {
            for (final Map.Entry<String, JsonElement> repository : manifest.getAsJsonObject("repositories").entrySet()) {
                final String base = repository.getValue().getAsString();
                try {
                    final HttpURLConnection connection = (HttpURLConnection) URI.create(base.endsWith("/") ? base + path : base + "/" + path).toURL().openConnection();
                    connection.setConnectTimeout(10_000);
                    connection.setReadTimeout(30_000);
                    if (connection.getResponseCode() != 200) throw new IOException(connection.getURL() + " answered " + connection.getResponseCode());
                    try (final InputStream in = connection.getInputStream()) {
                        Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        connection.disconnect();
                    }
                    if (!sha256(partial).equals(sha256)) throw new IOException(connection.getURL() + " is not the expected jar (checksum differs)");
                    Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                } catch (final IOException e) {
                    failure.addSuppressed(e);
                }
            }
            throw failure;
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    @Subscribe
    public void onInitialize(final ProxyInitializeEvent event) {
        try {
            final JsonObject manifest;
            try (final InputStream in = VelocityLoader.class.getResourceAsStream("/loader.json")) {
                if (in == null) throw new IllegalStateException("/loader.json is missing from the plugin jar");
                manifest = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            }
            loadLibraries(manifest);
            final Class<?> main = Class.forName(manifest.get("main").getAsString(), true, getClass().getClassLoader());
            try {
                implementation = main.getConstructor(ProxyServer.class, Logger.class, Path.class, Object.class)
                        .newInstance(server, logger, dataDirectory, this);
            } catch (final NoSuchMethodException e) {
                implementation = main.getConstructor(ProxyServer.class, Logger.class, Path.class)
                        .newInstance(server, logger, dataDirectory);
            }
            if (implementation instanceof Runnable runnable) runnable.run();
        } catch (final Exception e) {
            logger.error("Failed to load the plugin's libraries or start it", e);
        }
    }

    @Subscribe
    public void onShutdown(final ProxyShutdownEvent event) {
        if (!(implementation instanceof AutoCloseable closeable)) return;
        try {
            closeable.close();
        } catch (final Exception e) {
            logger.error("Failed to stop the plugin cleanly", e);
        }
    }

    private static String sha256(final Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
