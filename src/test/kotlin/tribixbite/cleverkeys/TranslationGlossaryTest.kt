package tribixbite.cleverkeys

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.text.Normalizer
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Terminology consistency across the 21 translated locales, driven by the checked-in
 * glossary `docs/i18n/glossary.json`.
 *
 * Why: every translation in this app is machine-produced and was added in batches by
 * different agents, so a later batch can pick a different word for an established product
 * concept (found 2026-09-29: Czech "swipování"/"tažení" beside the established "tah",
 * Indonesian "usap" beside "geser", Polish "przesuwanie" beside "pisanie gestem"). A
 * user then meets two names for one feature — in privacy/deletion copy that reads as two
 * different kinds of data.
 *
 * The glossary lists, per concept, the string KEYS whose English expresses it, and per
 * locale the allowed and forbidden term stems. Checks are key-scoped (a Czech "tažením"
 * meaning *drag* in an unrelated string is not flagged), stem-based, case-insensitive and
 * Unicode-aware; see [containsStem] for the matching rule.
 */
class TranslationGlossaryTest {

    private data class Terms(val allowed: List<String>, val forbidden: List<String>)
    private data class LocaleGlossary(
        val matchAnywhere: Boolean,
        val terms: Map<String, Terms>,
        /** Forms of the address register this locale does NOT use (see [register]). */
        val forbiddenRegister: List<Regex>,
    )

    private val glossaryFile = File("docs/i18n/glossary.json")

    private val root: JsonObject by lazy {
        assertTrue("missing ${glossaryFile.path}", glossaryFile.exists())
        JsonParser.parseString(glossaryFile.readText()).asJsonObject
    }

    /** concept -> keys expressing it. */
    private val concepts: Map<String, List<String>> by lazy {
        root.getAsJsonObject("concepts").entrySet().associate { (concept, body) ->
            concept to body.asJsonObject.getAsJsonArray("keys").map { it.asString }
        }
    }

    private val locales: Map<String, LocaleGlossary> by lazy {
        root.getAsJsonObject("locales").entrySet().associate { (locale, body) ->
            val obj = body.asJsonObject
            val terms = obj.getAsJsonObject("terms").entrySet().associate { (concept, t) ->
                val o = t.asJsonObject
                concept to Terms(
                    allowed = o.getAsJsonArray("allowed")?.map { it.asString }.orEmpty(),
                    forbidden = o.getAsJsonArray("forbidden")?.map { it.asString }.orEmpty(),
                )
            }
            val forbidden = obj.getAsJsonObject("register")?.getAsJsonArray("forbidden")
                // (?U): Unicode-aware \b/\w and case folding, so "\beszközöd" works on
                // accented letters exactly as the Python audit that produced the rules did.
                ?.map { Regex("(?U)" + it.asString, setOf(RegexOption.IGNORE_CASE)) }.orEmpty()
            locale to LocaleGlossary(obj.get("matchAnywhere")?.asBoolean ?: false, terms, forbidden)
        }
    }

    /** The glossary itself must stay well-formed as locales and keys evolve. */
    @Test fun glossaryCoversEveryLocaleAndOnlyRealKeys() {
        val dirs = TranslationResources.localeDirs.map(TranslationResources::localeOf).toSet()
        assertEquals("glossary locales must match res/values-* exactly", dirs, locales.keys)
        val defaults = TranslationResources.strings(TranslationResources.defaultDir)
        for ((concept, keys) in concepts) {
            assertTrue("concept $concept lists no keys", keys.isNotEmpty())
            for (key in keys) {
                assertTrue("concept $concept names unknown string $key", key in defaults)
            }
        }
        for ((locale, g) in locales) {
            for ((concept, terms) in g.terms) {
                assertTrue("$locale: unknown concept $concept", concept in concepts)
                assertTrue("$locale/$concept: no allowed stems", terms.allowed.isNotEmpty())
            }
        }
    }

    /**
     * Every keyed string uses an allowed term and no forbidden variant. All violations are
     * collected so one run shows the whole backlog.
     */
    @Test fun keyedStringsUseTheGlossaryTerm() {
        val violations = mutableListOf<String>()
        for (dir in TranslationResources.localeDirs) {
            val locale = TranslationResources.localeOf(dir)
            val glossary = locales[locale] ?: continue
            val strings = TranslationResources.strings(dir)
            for ((concept, terms) in glossary.terms) {
                for (key in concepts.getValue(concept)) {
                    val text = strings[key]
                    if (text == null) {
                        violations += "$locale $key: missing (needed for concept $concept)"
                        continue
                    }
                    if (terms.allowed.none { containsStem(text, it, glossary.matchAnywhere) }) {
                        violations += "$locale $key [$concept]: none of ${terms.allowed} in \"$text\""
                    }
                    terms.forbidden.filter { containsStem(text, it, glossary.matchAnywhere) }
                        .forEach { violations += "$locale $key [$concept]: forbidden \"$it\" in \"$text\"" }
                }
            }
        }
        assertTrue(
            "${violations.size} glossary violation(s) — fix the translation, or if the term " +
                "choice itself is wrong, update docs/i18n/glossary.json with a note:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    /**
     * One address register per locale (device finding 2026-09-29: Hungarian mixed informal
     * "te" forms — "eszközödet", "megnézhesd" — with formal "Ön" forms inside one Privacy
     * section). For each locale whose glossary entry declares `register.forbidden`, no string
     * or plural item may contain a form of the other register. Patterns are Java regexes,
     * case-insensitive and Unicode-aware (`(?U)` is prepended, so `\b`/`\w` see accented
     * letters). The decisions and their evidence are in docs/i18n/2026-09-29-register.md.
     */
    @Test fun everyStringUsesTheLocalesAddressRegister() {
        val violations = mutableListOf<String>()
        for (dir in TranslationResources.localeDirs) {
            val locale = TranslationResources.localeOf(dir)
            val rules = locales[locale]?.forbiddenRegister.orEmpty()
            if (rules.isEmpty()) continue
            for ((name, raw) in TranslationResources.allTexts(dir)) {
                val text = TranslationResources.unescape(raw)
                for (rule in rules) {
                    rule.find(text)?.let {
                        violations += "$locale $name: \"${it.value}\" (rule ${rule.pattern}) in \"$text\""
                    }
                }
            }
        }
        assertTrue(
            "${violations.size} address-register violation(s) — rewrite in the locale's " +
                "register (docs/i18n/glossary.json locales.<l>.register):\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test fun hungarianIsPinnedToTheFormalRegister() {
        val hu = locales.getValue("hu").forbiddenRegister
        assertTrue("hu must declare register.forbidden", hu.isNotEmpty())
        // The two strings found on device, in their original informal wording, must be caught.
        assertTrue(hu.any { it.containsMatchIn("soha semmilyen adat nem hagyja el az eszközödet") })
        assertTrue(hu.any { it.containsMatchIn("hogy megnézhesd, exportálhasd vagy törölhesd őket") })
        // Formal copy must pass.
        val formal = "Koppintson a visszavonáshoz. Az Ön által begépelt szó; törölheti az eszközéről."
        assertTrue(hu.none { it.containsMatchIn(formal) })
    }

    /** Pins the matching rule so a refactor cannot silently loosen or tighten it. */
    @Test fun stemMatchingIsWordInitialCaseInsensitiveAndNormalized() {
        // Word-initial by default: "wisch" must not match inside "Zwischenablage".
        assertFalse(containsStem("Zwischenablage", "wisch", matchAnywhere = false))
        assertTrue(containsStem("Zwischenablage", "wisch", matchAnywhere = true))
        assertTrue(containsStem("Opravy Tahů", "tah", matchAnywhere = false))
        // A hyphen or quote is a word boundary (Filipino "i-swipe").
        assertTrue(containsStem("para i-swipe", "swip", matchAnywhere = false))
        // Turkish dotted capital İ lower-cases to i + U+0307; the dot is dropped.
        assertTrue(containsStem("İfade", "ifade", matchAnywhere = false))
        // NFD input still matches an NFC stem.
        val decomposed = Normalizer.normalize("Opravy tažení", Normalizer.Form.NFD)
        assertTrue(containsStem(decomposed, "tažení", matchAnywhere = false))
        // A combining mark continues the word, so it is not a boundary.
        assertFalse(containsStem("x́tah", "tah", matchAnywhere = false))
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT).replace("̇", "")

    /**
     * True when [stem] occurs in [text] after NFC normalization and ROOT lower-casing
     * (U+0307 dropped so Turkish İ matches i). Unless [matchAnywhere] (compounding
     * languages, scripts without spaces), the occurrence must start a word: the preceding
     * character is neither a letter nor a combining mark.
     */
    private fun containsStem(text: String, stem: String, matchAnywhere: Boolean): Boolean {
        val haystack = normalize(TranslationResources.unescape(text))
        val needle = normalize(stem)
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            if (matchAnywhere || i == 0) return true
            val prev = haystack[i - 1]
            val type = Character.getType(prev)
            val isMark = type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
            if (!Character.isLetter(prev) && !isMark) return true
            i = haystack.indexOf(needle, i + 1)
        }
        return false
    }
}
