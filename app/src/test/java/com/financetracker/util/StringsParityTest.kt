package com.financetracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `values/strings.xml` and `values-uk/strings.xml` are two tables of the same keys, and nothing
 * in the build notices when they drift: a key deleted from the English file leaves a Ukrainian
 * entry nobody can reach, and a key added only to the English file leaves a screen that falls
 * back to English for a Ukrainian reader — which is exactly what the language picker promises
 * not to happen.
 *
 * The one legitimate difference is `translatable="false"`: a placeholder, an endonym or a
 * format-only string belongs in one table by design, and the rule below is the only place that
 * says so. So the invariant is "every Ukrainian key is English, and every English key is either
 * Ukrainian or not translatable".
 *
 * Reads the files off disk rather than through the merged resources, because `aapt` merges the
 * two tables into one and a merged table cannot show which side a key came from. Gradle runs
 * unit tests with the module directory as the working directory; the second candidate keeps
 * the test honest if that ever changes.
 */
class StringsParityTest {

    private val english = stringsOf("values/strings.xml")
    private val ukrainian = stringsOf("values-uk/strings.xml")

    @Test
    fun `both tables are readable and substantial`() {
        // A regex that silently stopped matching would make every assertion below pass on two
        // empty sets, so the size of each table is the guard on the guard.
        assertTrue("read ${english.size} English keys", english.size > 200)
        assertTrue("read ${ukrainian.size} Ukrainian keys", ukrainian.size > 200)
    }

    @Test
    fun `every ukrainian key exists in english`() {
        val missing = ukrainian.keys - english.keys
        assertTrue("keys only in values-uk: $missing", missing.isEmpty())
    }

    @Test
    fun `every english key is ukrainian or explicitly not translatable`() {
        val untranslated = english.entries
            .filter { (key, translatable) -> translatable && key !in ukrainian }
            .map { it.key }
        assertTrue(
            "translatable keys missing from values-uk: $untranslated",
            untranslated.isEmpty()
        )
    }

    @Test
    fun `a non-translatable key is not translated by accident`() {
        // The other direction of the same drift: something marked translatable="false" in the
        // English table that has acquired a Ukrainian twin would translate a string that was
        // never meant to change, and the two would then have to be kept in step by hand.
        val englishOnly = english.filterValues { !it }.keys
        val overlap = englishOnly.intersect(ukrainian.keys)
        assertTrue(
            "keys marked translatable=\"false\" but present in values-uk: $overlap",
            overlap.isEmpty()
        )
    }

    @Test
    fun `a key is defined once per table`() {
        // A duplicate is silently shadowed by the later one in the resource table, so the
        // English and Ukrainian files could disagree about what the key says while every
        // screen resolves the same key and appears to be fine.
        assertTrue(
            "duplicate English keys: ${duplicatesOf("values/strings.xml")}",
            duplicatesOf("values/strings.xml").isEmpty()
        )
        assertTrue(
            "duplicate Ukrainian keys: ${duplicatesOf("values-uk/strings.xml")}",
            duplicatesOf("values-uk/strings.xml").isEmpty()
        )
    }

@Test
    fun `a translated string keeps the same format arguments as its english original`() {
        // The drift the key-parity checks above cannot see. A key present in both tables with
        // different arguments passes every other test in this file and fails at runtime, when
        // `getString` is handed fewer arguments than the format string asks for — or, worse,
        // silently drops one, so a sentence reads "Excluded by your rules: transaction(s)".
        //
        // The arguments are compared as a set of positions rather than as a string, because
        // order is allowed to differ: a translator may put "%2$s before %1$s" if the Ukrainian
        // sentence needs it to. What must not differ is which positions are used, and how many
        // times each is repeated, since a repeated argument is how a string uses the same value
        // twice.
        val mismatched = ukrainian.keys
            .filter { it in english }
            .mapNotNull { key ->
                val en = argumentsIn(valueOf("values/strings.xml", key))
                val uk = argumentsIn(valueOf("values-uk/strings.xml", key))
                if (en == uk) null else "$key (english=$en ukrainian=$uk)"
            }

        assertTrue("format arguments differ: $mismatched", mismatched.isEmpty())
    }

    @Test
    fun `a translated string with no english arguments has none either`() {
        // The same check, stated for the direction that is easiest to get wrong by accident: a
        // Ukrainian string gaining a placeholder the English one does not have would render a
        // literal "%1$s" for a reader who never sees the English text.
        val introduced = ukrainian.keys
            .filter { it in english }
            .filter {
                argumentsIn(valueOf("values-uk/strings.xml", it)).isNotEmpty() &&
                    argumentsIn(valueOf("values/strings.xml", it)).isEmpty()
            }

        assertTrue("ukrainian strings introducing a format argument: $introduced", introduced.isEmpty())
    }

    /** Format argument indices used by a string body, each repeated as many times as it appears. */
    private fun argumentsIn(body: String): List<Int> =
        ARGUMENT.findAll(body).map { it.groupValues[1].toInt() }.sorted().toList()

    private fun valueOf(path: String, key: String): String =
        BODY.findAll(read(path))
            .firstOrNull { it.groupValues[1] == key }
            ?.groupValues
            ?.get(2)
            ?: ""

    private fun duplicatesOf(path: String): Set<String> = xmlKeysOf(path).let { keys ->
        keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    }

    private fun xmlKeysOf(path: String): List<String> =
        TAG.findAll(read(path)).map { it.groupValues[1] }.toList()

    /** name → whether the entry is translatable (does NOT carry translatable="false"). */
    private fun stringsOf(path: String): Map<String, Boolean> =
        TAG.findAll(read(path))
            .map { it.groupValues[1] to !it.groupValues[2].isNonTranslatable() }
            .toList()
            .toMap()

    private fun String.isNonTranslatable(): Boolean =
        trim().startsWith(TRANSLATABLE_EQUALS_FALSE)

    private fun read(path: String): String = fileFor(path).readText(Charsets.UTF_8)

    private fun fileFor(path: String): File {
        val candidates = listOf(File("src/main/res/$path"), File("app/src/main/res/$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("cannot find $path from ${File("").absolutePath}")
    }

    private companion object {
        /**
         * One `<string …>` opening tag: the name attribute, then the rest of the attributes so
         * `translatable="false"` can be read. `<string-array` is not matched, because the tag
         * name is followed by `-` rather than by the space this pattern requires.
         */
        val TAG = Regex("""<string\s+name="([^"]+)"([^>]*)>""")

        /**
         * The whole element including its body, so a string's text can be read back rather than
         * only its opening tag. Non-greedy, because the first closing tag ends the body.
         */
        val BODY = Regex("""<string\s+name="([^"]+)"[^>]*>([\s\S]*?)</string>""")

        /**
         * A positional format argument, `%1$s`. Positional only: an implicit `%s` takes its
         * index from the call site's order rather than the string's, so it cannot be compared
         * between two tables at all, and this app does not use it.
         */
        val ARGUMENT = Regex("""%(\d+)\$[sd]""")
        /**
         * Whether the tag carries the one exact spelling `translatable="false"`: everything
         * after the name, trimmed, is compared against it. The whole literal is the right
         * constant here, so it is kept as one word and not reassembled from halves that
         * invite a reader to recombine them into something the file never said.
         */
        private const val TRANSLATABLE_EQUALS_FALSE = "translatable=\"false\""
    }
}



