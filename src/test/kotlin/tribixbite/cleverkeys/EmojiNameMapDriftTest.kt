package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * ARC-106: the `nameToEmoji` literal in `Emoji.initNameMap` is a `mapOf(...)` where a
 * duplicate key silently OVERWRITES the earlier entry. The map mixes an emoji section
 * with a text-emoticon/kaomoji section, and before the fix 24 keys collided — e.g.
 * `"heart"` resolved to the `<3` emoticon instead of ❤️, `"smile"` to `:)` instead
 * of 😄, and ❤️ lost its canonical long-press name.
 *
 * Ownership policy (pinned here):
 *  - The EMOJI owns the bare name. A user typing "heart" into emoji search expects ❤️.
 *  - The emoticon/kaomoji keeps a QUALIFIED key that still CONTAINS the bare name
 *    (e.g. "heart emoticon"). `Emoji.searchByName`'s nameMap fallback matches with
 *    `name.contains(query)`, so the bare query still surfaces the emoticon as an
 *    additional result — nothing becomes unreachable.
 *  - Emoji-vs-emoji collisions get a distinguishing name for the less-expected sense
 *    ("water wave" 🌊, "computer mouse" 🖱️, "cricket game" 🏏, "turkey flag" 🇹🇷,
 *    "dark sunglasses" 🕶️).
 *
 * The map is a private literal built at runtime from `R.raw.emojis`, so this is a
 * source-scan test (same project-root-CWD convention as [LanguageSlotCoverageDriftTest]):
 * it parses the literal's `"key" to "value"` pairs directly out of `emoji/Emoji.kt`.
 */
class EmojiNameMapDriftTest {

    /** `"key" to "value"` pairs of the nameToEmoji literal, in declaration order (source-escaped). */
    private fun declaredPairs(): List<Pair<String, String>> {
        val src = File(System.getProperty("user.dir") ?: ".", "src/main/kotlin/tribixbite/cleverkeys/emoji/Emoji.kt")
        check(src.isFile) { "expected Emoji.kt at ${src.absolutePath} — run from the project root" }
        val text = src.readText()

        val start = text.indexOf("val nameToEmoji = mapOf(")
        check(start >= 0) { "nameToEmoji literal not found in Emoji.kt — update this test if it was renamed" }
        // The literal closes with a `)` at 12-space indentation (the mapOf call's own level).
        val end = text.indexOf("\n            )", start)
        check(end > start) { "closing paren of the nameToEmoji literal not found" }
        val block = text.substring(start, end)

        // A string literal is `"` + (non-quote-non-backslash | backslash-escape)* + `"`.
        // Values keep their source escaping; the pins below only use escape-free strings.
        val pair = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s+to\\s+\"((?:[^\"\\\\]|\\\\.)*)\"")
        val pairs = pair.findAll(block).map { it.groupValues[1] to it.groupValues[2] }.toList()

        // Guard against regex rot: the literal has ~1400 entries.
        assertWithMessage("nameToEmoji parse produced implausibly few pairs — pattern drift?")
            .that(pairs.size).isGreaterThan(1000)
        return pairs
    }

    @Test
    fun `no literal key is silently overwritten`() {
        val dupes = declaredPairs()
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        assertWithMessage(
            "duplicate keys in Emoji.initNameMap's nameToEmoji literal — later entries " +
                "silently overwrite earlier ones. Give the losing sense a qualified key " +
                "that still contains the bare name: $dupes"
        ).that(dupes).isEmpty()
    }

    @Test
    fun `emoji own their bare names`() {
        val map = declaredPairs().toMap() // last-wins, same semantics as mapOf
        val expected = mapOf(
            "heart" to "❤️",
            "smile" to "😄",
            "kiss" to "💋",
            "wink" to "😉",
            "grin" to "😁",
            "cat" to "🐱",
            "bear" to "🐻",
            "wave" to "👋",
            "shrug" to "🤷",
            "angry" to "😠",
            "sunglasses" to "😎",
            "tongue" to "👅",
            "victory" to "✌️",
            "rage" to "😡",
            "mouse" to "🐭",
            "turkey" to "🦃",
        )
        for ((name, emoji) in expected) {
            assertWithMessage("emoji search key '$name' must resolve to the emoji, not an emoticon")
                .that(map[name]).isEqualTo(emoji)
        }
    }

    @Test
    fun `displaced emoticons stay reachable under a key containing the bare name`() {
        val pairs = declaredPairs()
        // bare name -> emoticon value that used to squat on it (escape-free values only;
        // kaomoji with backslashes are covered by the no-duplicates invariant instead).
        val displaced = mapOf(
            "heart" to "<3",
            "smile" to ":)",
            "wink" to ";)",
            "grin" to ":D",
            "kiss" to ":*",
            "tongue" to ":P",
            "bear" to "ʕ•ᴥ•ʔ",
        )
        for ((bare, emoticon) in displaced) {
            val keys = pairs.filter { it.second == emoticon }.map { it.first }
            assertWithMessage("emoticon '$emoticon' must keep a key containing '$bare' so the bare query still finds it")
                .that(keys.any { it.contains(bare) }).isTrue()
        }
    }

    @Test
    fun `displaced emoticons exist in the raw emoji resource`() {
        // initNameMap drops any entry whose value is absent from R.raw.emojis (stringMap
        // lookup fails) — a rename is only real if the emoticon is actually loadable.
        val raw = File(System.getProperty("user.dir") ?: ".", "res/raw/emojis.txt")
        check(raw.isFile) { "expected res/raw/emojis.txt — run from the project root" }
        val lines = raw.readLines().toSet()
        for (emoticon in listOf("<3", ":)", ";)", ":D", ":*", ":P", "ʕ•ᴥ•ʔ")) {
            assertThat(lines).contains(emoticon)
        }
    }
}
