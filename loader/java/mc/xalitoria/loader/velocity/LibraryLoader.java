package mc.xalitoria.loader.velocity;

import com.google.gson.Gson;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Downloads the libraries a Velocity plugin lists and adds them to the plugin's class loader. Call {@link #load} first
 * thing in the plugin's constructor, before any class that needs a library is touched.
 *
 * <p>{@code /velocity-libraries.json} holds the repositories to try and, for every jar, its Maven coordinates and
 * SHA-256, so each download is checked and a jar already in the folder is only downloaded again if it doesn't match.
 */
public final class LibraryLoader {
    private static final Gson GSON = new Gson();

    private record Library(String group, String name, String version, String sha256) {
        String path() {
            return group.replace('.', '/') + "/" + name + "/" + version + "/" + name + "-" + version + ".jar";
        }
    }

    private record Libraries(List<String> repositories, List<Library> libraries) {}

    private LibraryLoader() {}

    /**
     * @param plugin  the plugin's main object, whose class loader receives the libraries
     * @param folder  where the jars are kept, usually inside the plugin's data directory
     */
    public static void load(Object plugin, Path folder, Logger logger) {
        final Libraries listed;
        try (InputStream in = plugin.getClass().getResourceAsStream("/velocity-libraries.json")) {
            if (in == null) throw new IllegalStateException("/velocity-libraries.json is missing from the plugin jar");
            listed = GSON.fromJson(new InputStreamReader(in), Libraries.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // PluginClassLoader.addPath is package-private, but Velocity and its plugins share one unnamed module, so this is allowed.
        final ClassLoader loader = plugin.getClass().getClassLoader();
        final Method addPath;
        try {
            addPath = loader.getClass().getDeclaredMethod("addPath", Path.class);
            addPath.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("This version of Velocity can't add libraries to a plugin's class loader", e);
        }

        final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        try {
            Files.createDirectories(folder);
            for (final Library library : listed.libraries()) {
                final Path jar = folder.resolve(library.name() + "-" + library.version() + ".jar");
                if (!Files.exists(jar) || !sha256(jar).equals(library.sha256())) {
                    logger.info("Downloading {}:{}:{}", library.group(), library.name(), library.version());
                    download(http, listed.repositories(), library, jar);
                }
                addPath.invoke(loader, jar);
            }
        } catch (IOException | InterruptedException | ReflectiveOperationException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not load the plugin's libraries", e);
        }
    }

    /** Tries each repository in turn; the jar is only put in place once its checksum is right. */
    private static void download(HttpClient http, List<String> repositories, Library library, Path target)
            throws IOException, InterruptedException {
        final Path partial = target.resolveSibling(target.getFileName() + ".part");
        IOException failure = null;
        for (final String repository : repositories) {
            final URI uri = URI.create(repository.endsWith("/") ? repository + library.path() : repository + "/" + library.path());
            final HttpResponse<Path> response = http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofFile(partial));
            if (response.statusCode() == 200 && sha256(partial).equals(library.sha256())) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            Files.deleteIfExists(partial);
            failure = new IOException(uri + " answered " + response.statusCode() + " or a jar that doesn't match its checksum");
        }
        throw failure != null ? failure : new IOException("No repositories listed for " + library.name());
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
