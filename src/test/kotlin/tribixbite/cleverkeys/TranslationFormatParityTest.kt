package tribixbite.cleverkeys

import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Mechanical parity of EVERY translatable resource in the 21 locales against the default
 * (English) `res/values/strings.xml`. The other translation tests pin a chosen set of keys
 * ([TranslationCoverageDriftTest]), terminology ([TranslationGlossaryTest]) or length
 * ([TranslationLengthTest]); this one covers the whole file, for the defects that are
 * mechanical rather than linguistic:
 *
 *  1. **Coverage.** Each translatable name exists in each locale with the same element kind
 *     (`<string>` vs `<plurals>`), and no locale carries a name the default does not translate.
 *     Lint's MissingTranslation/ExtraTranslation cover this too, but only in a ~45-minute
 *     `lintDebug` run on Termux.
 *  2. **Format arguments.** A `<string>` keeps the default's multiset of arguments (indexed
 *     `%1$s`, or sequential `%s` numbered by position). In a `<plurals>`, the `other` item
 *     keeps the default `other` item's arguments and no item adds one the default lacks
 *     (a `one` item may drop the count). A dropped or retyped argument is a
 *     MissingFormatArgumentException or IllegalFormatConversionException for that locale only.
 *  3. **Literal `%`.** A string without arguments that shows a literal `%` must carry
 *     `formatted="false"`: CI `lintDebug` failed `StringFormatInvalid` on vi "% chiều" (read
 *     as the conversion `% c`). A string the default marks `formatted="false"` keeps the
 *     attribute in every locale, because resource attributes are not inherited. A string WITH
 *     arguments, and every plural item, must write a literal percent as `%%`, or
 *     String.format fails on it at runtime.
 *  4. **Quotes.** aapt2 treats an unescaped `"` as a quoting delimiter and removes it, so
 *     `"%1$s" wurde hinzugefügt` renders without its quotes (found 2026-10-22 in en, de, es,
 *     it, nl, pt and nine other locales). Use `\"` or typographic quotes. Wrapping a whole
 *     value in `"…"` (to keep spaces) is the one legitimate use. An unescaped `'` outside
 *     such a quoted region is an aapt2 compile error.
 *
 * The rules are implemented once ([Rules]) and exercised two ways: against the real tree,
 * and against inline fixtures that contain one deliberate defect per rule, so a rule that
 * stopped detecting anything would fail [rulesDetectEachDefectInAFixture] rather than pass
 * silently. Runs with the project root as CWD, like the other translation tests.
 */
class TranslationFormatParityTest {

    /** One resource: its element kind, attributes that matter, and text per item. */
    internal data class Res(
        val kind: String,
        val translatable: Boolean,
        val formattedFalse: Boolean,
        /** `""` -> text for a `<string>`; quantity -> text for a `<plurals>`. */
        val texts: Map<String, String>,
    )

    internal object Rules {
        /** java.util.Formatter specifier, as String.format parses it. */
        private val spec = Regex("%(?:([0-9]+)\\$)?[-#+ 0,(<]*[0-9]*(?:\\.[0-9]+)?[tT]?([a-zA-Z%])")
        private val conversions = "bBhHsScCdoxXeEfgGaAtT".map(Char::toString).toSet()
        private val indexed = Regex("%[0-9]+\\$[-#+ 0,(<]*[0-9]*(?:\\.[0-9]+)?[tT]?[a-zA-Z]")

        fun parse(doc: Document): Map<String, Res> {
            val out = LinkedHashMap<String, Res>()
            val children = doc.documentElement.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i) as? Element ?: continue
                val name = node.getAttribute("name")
                if (name.isNullOrEmpty() || node.tagName !in setOf("string", "plurals")) continue
                val texts = LinkedHashMap<String, String>()
                if (node.tagName == "string") {
                    texts[""] = node.textContent.orEmpty()
                } else {
                    val items = node.getElementsByTagName("item")
                    for (j in 0 until items.length) {
                        val item = items.item(j) as Element
                        texts[item.getAttribute("quantity")] = item.textContent.orEmpty()
                    }
                }
                out[name] = Res(
                    kind = node.tagName,
                    translatable = node.getAttribute("translatable") != "false",
                    formattedFalse = node.getAttribute("formatted") == "false",
                    texts = texts,
                )
            }
            return out
        }

        fun parseFile(file: File): Map<String, Res> = parse(builder().parse(file))
        fun parseText(xml: String): Map<String, Res> = parse(builder().parse(InputSource(StringReader(xml))))

        private fun builder() = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }.newDocumentBuilder()

        /**
         * Normalized argument multiset: `1$s`, `2$d`… Sequential specifiers are numbered by
         * position. With [indexedOnly] (a `formatted="false"` value, where `% d` is literal
         * text) only `%n$x` specifiers count.
         */
        fun arguments(text: String, indexedOnly: Boolean = false): List<String> {
            if (indexedOnly) {
                return indexed.findAll(text).map { m ->
                    val v = m.value
                    v.substringAfter('%').substringBefore('$') + "$" + v.last().lowercaseChar()
                }.sorted().toList()
            }
            var sequential = 0
            return spec.findAll(text).mapNotNull { m ->
                val conversion = m.groupValues[2]
                // `%%` and `%n` take no argument; a letter Formatter rejects is not one either.
                if (conversion == "%" || conversion == "n" || conversion !in conversions) return@mapNotNull null
                val position = m.groupValues[1].ifEmpty { (++sequential).toString() }
                "$position$" + conversion.lowercase()
            }.sorted().toList()
        }

        /**
         * `true` when [text] has a `%` that is neither `%%` nor an argument: an indexed `%1$s`,
         * or a sequential `%s` without a space or `-` flag (`% c` in vi "% chiều" and `%-a` in hu
         * "%-ában" are literal text).
         */
        fun hasBarePercent(text: String): Boolean {
            val noArgs = indexed.replace(text.replace("%%", ""), "")
            return Regex("%[#+0,(]*[0-9]*(?:\\.[0-9]+)?[bBhHsScCdoxXeEfgGaA]").replace(noArgs, "").contains('%')
        }

        fun coverage(base: Map<String, Res>, locale: Map<String, Res>): List<String> {
            val wanted = base.filterValues { it.translatable }
            val out = mutableListOf<String>()
            for ((name, res) in wanted) {
                val tr = locale[name]
                when {
                    tr == null -> out += "$name: missing"
                    tr.kind != res.kind -> out += "$name: <${tr.kind}> but the default is <${res.kind}>"
                }
            }
            for (name in locale.keys - wanted.keys) {
                out += if (name in base) "$name: translated, but translatable=\"false\" in the default"
                else "$name: not in the default locale"
            }
            return out
        }

        fun argumentMismatches(base: Map<String, Res>, locale: Map<String, Res>): List<String> {
            val out = mutableListOf<String>()
            for ((name, b) in base) {
                if (!b.translatable) continue
                val t = locale[name] ?: continue
                if (t.kind != b.kind) continue
                if (b.kind == "string") {
                    if (b.formattedFalse) continue
                    val want = arguments(b.texts.getValue(""))
                    // A formatted="false" translation of an argument-free string only has
                    // literal percents; anything else is compared as String.format reads it.
                    val text = t.texts.getValue("")
                    // An argument-free default whose translation has no indexed argument can
                    // only differ by a literal percent; [literalPercent] reports that case.
                    if (want.isEmpty() && arguments(text, indexedOnly = true).isEmpty()) continue
                    val got = arguments(text, indexedOnly = t.formattedFalse && want.isEmpty())
                    if (want != got) out += "$name: $got, default $want"
                } else {
                    val baseOther = arguments(b.texts["other"].orEmpty())
                    val allowed = b.texts.values.flatMap { arguments(it) }.toSet()
                    for ((quantity, text) in t.texts) {
                        val got = arguments(text)
                        if (quantity == "other" && got != baseOther) {
                            out += "$name#other: $got, default $baseOther"
                        }
                        (got.toSet() - allowed).forEach { out += "$name#$quantity: adds $it" }
                    }
                }
            }
            return out
        }

        fun literalPercent(base: Map<String, Res>, locale: Map<String, Res>): List<String> {
            val out = mutableListOf<String>()
            for ((name, t) in locale) {
                val b = base[name] ?: t
                for ((quantity, text) in t.texts) {
                    val id = if (quantity.isEmpty()) name else "$name#$quantity"
                    when {
                        t.kind == "plurals" -> if (hasBarePercent(text)) {
                            out += "$id: a plural item is always formatted — write a literal percent as %%"
                        }
                        b.formattedFalse && !t.formattedFalse && text.contains('%') ->
                            out += "$id: the default is formatted=\"false\"; add it here too"
                        t.formattedFalse -> Unit
                        !hasBarePercent(text) -> Unit
                        // No arguments: lint reads "% c…" as a conversion (the vi CI failure).
                        arguments(b.texts[quantity].orEmpty()).isEmpty() ->
                            out += "$id: literal % without formatted=\"false\""
                        else -> out += "$id: has arguments, so a literal percent must be %%"
                    }
                }
            }
            return out
        }

        /** Unescaped `"` (stripped by aapt2) and `'` outside a quoted region (aapt2 error). */
        fun quoting(locale: Map<String, Res>): List<String> {
            val out = mutableListOf<String>()
            for ((name, t) in locale) {
                for ((quantity, text) in t.texts) {
                    val id = if (quantity.isEmpty()) name else "$name#$quantity"
                    val quotes = mutableListOf<Int>()
                    var inQuotes = false
                    var apostrophe = false
                    var i = 0
                    while (i < text.length) {
                        when (text[i]) {
                            '\\' -> i++
                            '"' -> { quotes += i; inQuotes = !inQuotes }
                            '\'' -> if (!inQuotes) apostrophe = true
                        }
                        i++
                    }
                    val trimmed = text.trim()
                    val wholeValueQuoted = quotes.size == 2 && trimmed.startsWith('"') && trimmed.endsWith('"')
                    if (quotes.isNotEmpty() && !wholeValueQuoted) {
                        out += "$id: unescaped \" (aapt2 removes it; use \\\" or typographic quotes)"
                    }
                    if (apostrophe) out += "$id: unescaped ' (use \\')"
                }
            }
            return out
        }
    }

    private val base by lazy { Rules.parseFile(File(TranslationResources.defaultDir, "strings.xml")) }
    private val locales by lazy {
        TranslationResources.localeDirs.associate {
            TranslationResources.localeOf(it) to Rules.parseFile(File(it, "strings.xml"))
        }
    }

    private fun report(what: String, findings: Map<String, List<String>>) {
        val lines = findings.flatMap { (locale, list) -> list.map { "$locale $it" } }
        assertTrue("${lines.size} $what:\n" + lines.joinToString("\n"), lines.isEmpty())
    }

    @Test fun everyLocaleHasExactlyTheTranslatableResources() {
        assertEquals("expected 21 locale directories", 21, locales.size)
        report("coverage defect(s)", locales.mapValues { (_, l) -> Rules.coverage(base, l) })
    }

    @Test fun formatArgumentsMatchTheDefaultLocale() {
        report("format-argument mismatch(es)", locales.mapValues { (_, l) -> Rules.argumentMismatches(base, l) })
    }

    @Test fun literalPercentSignsAreDeclaredOrEscaped() {
        report(
            "literal-% defect(s)",
            mapOf("en" to Rules.literalPercent(base, base)) +
                locales.mapValues { (_, l) -> Rules.literalPercent(base, l) },
        )
    }

    @Test fun quotesAndApostrophesAreEscaped() {
        report(
            "quoting defect(s)",
            mapOf("en" to Rules.quoting(base)) + locales.mapValues { (_, l) -> Rules.quoting(l) },
        )
    }

    /** Pins the parsers on the shapes that matter, including the ones that must NOT count. */
    @Test fun argumentParsing() {
        assertEquals(listOf("1\$s", "2\$d"), Rules.arguments("%2\$d of %1\$s"))
        assertEquals(listOf("1\$s", "2\$d"), Rules.arguments("%s: %d"))
        assertEquals(listOf("1\$d"), Rules.arguments("Min: %1\$d%%"))
        assertEquals(emptyList<String>(), Rules.arguments("% chiều", indexedOnly = true))
        assertEquals(listOf("1\$c"), Rules.arguments("% chiều")) // what lint/String.format sees
        assertTrue(Rules.hasBarePercent("%1\$d % of"))
        assertTrue(!Rules.hasBarePercent("csak %2\$d%%-a")) // Hungarian suffix after %%
        assertTrue(Rules.hasBarePercent("a képernyő %-ában")) // Hungarian suffix on a literal %
        assertTrue(Rules.hasBarePercent("theo % chiều"))
    }

    /**
     * Fail-first evidence kept as a test: a fixture locale with one deliberate defect per rule.
     * Each rule must report exactly its defect, and the clean twin must report nothing.
     */
    @Test fun rulesDetectEachDefectInAFixture() {
        val en = Rules.parseText(
            """<resources>
              <string name="app" translatable="false">App</string>
              <string name="count">%1${'$'}d of %2${'$'}s</string>
              <string name="pct" formatted="false">Height as % of screen</string>
              <string name="plain">Height as percentage</string>
              <string name="pane">Pane height as percentage of keyboard</string>
              <string name="fmt">%1${'$'}d keys</string>
              <string name="quote">Customize \"%1${'$'}s\"</string>
              <string name="kept">"  padded  "</string>
              <plurals name="items"><item quantity="one">One item</item><item quantity="other">%1${'$'}d items</item></plurals>
            </resources>"""
        )
        val clean = Rules.parseText(
            """<resources>
              <string name="count">%1${'$'}d z %2${'$'}s</string>
              <string name="pct" formatted="false">Výška v % obrazovky</string>
              <string name="plain">Výška v procentech</string>
              <string name="pane">Výška panelu v procentech</string>
              <string name="fmt">%1${'$'}d kláves</string>
              <string name="quote">Přizpůsobit „%1${'$'}s“</string>
              <string name="kept">"  odsazeno  "</string>
              <plurals name="items"><item quantity="one">Jedna položka</item><item quantity="few">%1${'$'}d položky</item><item quantity="other">%1${'$'}d položek</item></plurals>
            </resources>"""
        )
        val broken = Rules.parseText(
            """<resources>
              <string name="app">Aplikace</string>
              <string name="count">%1${'$'}s z %2${'$'}s</string>
              <string name="pct">Výška v % obrazovky</string>
              <string name="plain">Výška v % klávesnice</string>
              <string name="pane">Chiều cao theo % chiều bàn phím</string>
              <string name="fmt">%1${'$'}d kláves (100 %)</string>
              <string name="quote">Přizpůsobit "%1${'$'}s"</string>
              <string name="kept">Don't</string>
              <plurals name="items"><item quantity="one">Jedna</item><item quantity="other">%1${'$'}d položek (50 %)</item></plurals>
              <string name="stray">Navíc</string>
            </resources>"""
        )
        assertEquals(emptyList<String>(), Rules.coverage(en, clean))
        assertEquals(emptyList<String>(), Rules.argumentMismatches(en, clean))
        assertEquals(emptyList<String>(), Rules.literalPercent(en, clean))
        assertEquals(emptyList<String>(), Rules.quoting(clean))
        assertEquals(emptyList<String>(), Rules.quoting(en))

        assertEquals(
            listOf(
                "app: translated, but translatable=\"false\" in the default",
                "stray: not in the default locale",
            ),
            Rules.coverage(en, broken).sorted(),
        )
        assertEquals(
            listOf("count: [1\$s, 2\$s], default [1\$d, 2\$s]"),
            Rules.argumentMismatches(en, broken),
        )
        assertEquals(
            listOf(
                "fmt: has arguments, so a literal percent must be %%",
                "items#other: a plural item is always formatted — write a literal percent as %%",
                "pane: literal % without formatted=\"false\"",
                "pct: the default is formatted=\"false\"; add it here too",
                "plain: literal % without formatted=\"false\"",
            ),
            Rules.literalPercent(en, broken).sorted(),
        )
        assertEquals(
            listOf(
                "kept: unescaped ' (use \\')",
                "quote: unescaped \" (aapt2 removes it; use \\\" or typographic quotes)",
            ),
            Rules.quoting(broken).sorted(),
        )
    }
}
