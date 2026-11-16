package mc.xalitoria.gradle

import mc.xalitoria.gradle.repo.Serves
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryProbeTest {
    private val declared = linkedMapOf("a" to "https://a.example/maven", "b" to "https://b.example/maven")
    private val coordinates = listOf("com.example:lib:1.0:sha")

    /** Answers by the host of the URL asked; a host that is not in [answers] could not be asked. */
    private fun used(answers: Map<String, Int?>, serves: (String, String, String) -> Boolean = { _, _, _ -> true }) =
        usedRepositories(declared, coordinates, { url -> answers[url.substringAfter("https://").substringBefore('.')] }, serves).keys.toList()

    @Test
    fun `the first repository that has the module is the one`() {
        assertEquals(listOf("a"), used(mapOf("a" to 200, "b" to 200)))
        assertEquals(listOf("b"), used(mapOf("a" to 404, "b" to 200)))
    }

    @Test
    fun `a repository that Gradle's filter would not ask is not asked`() {
        // A proxies the module but only serves com.xalitoria, so Gradle took it from B.
        assertEquals(listOf("b"), used(mapOf("a" to 200, "b" to 200)) { repository, group, _ -> !(repository == "a" && group == "com.example") })
    }

    @Test
    fun `only a 404 or a 410 says a repository does not have the module`() {
        assertEquals(listOf("a"), used(mapOf("a" to 503, "b" to 404)))
        assertEquals(listOf("a"), used(mapOf("a" to 429, "b" to 410)))
        assertEquals(listOf("a", "b"), used(mapOf("a" to 503, "b" to null)))
        assertEquals(emptyList(), used(mapOf("a" to 404, "b" to 404)))
    }

    @Test
    fun `a repository that did not answer before the one that has the module is kept with it`() {
        assertEquals(listOf("a", "b"), used(mapOf("a" to 503, "b" to 200)))
    }

    @Test
    fun `what a repository serves`() {
        val xali = Serves(listOf("com.xalitoria", "net.xalitoria"), snapshots = false)
        assertTrue(xali.accepts("com.xalitoria", "1.0"))
        assertTrue(xali.accepts("com.xalitoria.util", "1.0"))
        assertFalse(xali.accepts("com.xalitoriax", "1.0"))
        assertFalse(xali.accepts("com.xalitoria", "1.0-SNAPSHOT"))
        assertFalse(Serves(releases = false).accepts("any", "1.0"))
        assertTrue(Serves(releases = false).accepts("any", "1.0-SNAPSHOT"))
        val jitpack = Serves(regex = """com\.github\.(?!NuVotifier\b).*""")
        assertTrue(jitpack.accepts("com.github.owner", "1"))
        assertFalse(jitpack.accepts("com.github.NuVotifier", "1"))
        assertTrue(Serves().accepts("anything", "1"))
    }
}
