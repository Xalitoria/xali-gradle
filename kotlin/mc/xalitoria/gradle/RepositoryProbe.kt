package mc.xalitoria.gradle

import org.gradle.api.logging.Logging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = Logging.getLogger("mc.xalitoria.gradle.loader")

/**
 * The repositories of [declared] that a loader needs to get [coordinates] (`group:name:version`, and what follows is
 * ignored), in their order.
 *
 * Gradle uses the first repository that has a module, and so does a loader, so each module is asked of the repositories
 * in turn and the first that has it is the one needed. [status] is the HTTP status of a URL, or `null` when it could
 * not be asked. A module that no repository answers for, and whose repositories could not all be asked, keeps those
 * repositories: a list that is too long loads the same libraries, one that is too short fails at run time.
 */
internal fun usedRepositories(
    declared: Map<String, String>,
    coordinates: List<String>,
    status: (String) -> Int? = ::headStatus,
): Map<String, String> {
    if (declared.isEmpty() || coordinates.isEmpty()) return emptyMap()
    val modules = coordinates.map { it.split(":").take(3) }.filter { it.size == 3 }.distinct()
    val pool = Executors.newFixedThreadPool(minOf(16, modules.size.coerceAtLeast(1)))
    try {
        val needed = modules.map { module -> pool.submit<Set<String>> { repositoriesFor(declared, module, status) } }
            .flatMap { it.get() }.toSet()
        return declared.filterKeys { it in needed }
    } finally {
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.SECONDS)
    }
}

/** The names of the repositories that serve [module] (`group`, `name`, `version`): one, or the ones that were not asked. */
private fun repositoriesFor(declared: Map<String, String>, module: List<String>, status: (String) -> Int?): Set<String> {
    val (group, name, version) = module
    val directory = "${group.replace('.', '/')}/$name/$version"
    // A snapshot's files are named by time, but the directory lists them.
    val file = if (version.endsWith("-SNAPSHOT")) "maven-metadata.xml" else "$name-$version.pom"
    val unknown = linkedSetOf<String>()
    for ((repository, url) in declared) {
        when (status("${url.trimEnd('/')}/$directory/$file")) {
            200 -> return setOf(repository)
            null -> unknown += repository
            else -> {}
        }
    }
    if (unknown.isNotEmpty()) logger.warn("Could not ask ${unknown.joinToString()} for $group:$name:$version; keeping them in loader.json.")
    return unknown
}

private val client: HttpClient by lazy {
    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(8)).build()
}

private fun headStatus(url: String): Int? = try {
    val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(8)).method("HEAD", HttpRequest.BodyPublishers.noBody())
    var response = client.send(request.build(), HttpResponse.BodyHandlers.discarding())
    // Some servers do not answer HEAD.
    if (response.statusCode() in listOf(405, 501)) {
        response = client.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.discarding())
    }
    response.statusCode()
} catch (_: java.io.IOException) {
    null
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    null
}
