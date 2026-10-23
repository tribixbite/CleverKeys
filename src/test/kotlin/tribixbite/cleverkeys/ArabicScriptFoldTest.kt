package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The per-language Arabic-script lookup fold ([ArabicScriptFold], decision (c), 2026-10-23;
 * research `docs/research/2026-10-21-persian-zwnj.md` §4). Pure JVM.
 */
class ArabicScriptFoldTest {

    @Test
    fun `fa folds Arabic yeh, kaf, alef maksura and the final heh forms`() {
        assertWithMessage("ي ك").that(ArabicScriptFold.fold("كتاب عربي", "fa")).isEqualTo("کتاب عربی")
        assertWithMessage("ى").that(ArabicScriptFold.fold("موسى", "fa")).isEqualTo("موسی")
        assertWithMessage("ۀ").that(ArabicScriptFold.fold("خانۀ", "fa")).isEqualTo("خانه")
        assertWithMessage("ة").that(ArabicScriptFold.fold("خانة", "fa")).isEqualTo("خانه")
        assertWithMessage("ە").that(ArabicScriptFold.fold("خانە", "fa")).isEqualTo("خانه")
        assertWithMessage("region tag ignored").that(ArabicScriptFold.fold("كتاب", "fa_IR")).isEqualTo("کتاب")
    }

    @Test
    fun `fa never folds alef madda or hamza carriers`() {
        for (word in listOf("آب", "أنا", "إسلام", "مؤمن", "مسئله", "جزء", "هٔ")) {
            assertWithMessage(word).that(ArabicScriptFold.fold(word, "fa")).isEqualTo(word)
        }
    }

    @Test
    fun `the fold is one code unit for one code unit`() {
        val text = "كتابي خانۀ ة ە ى"
        assertWithMessage("length kept").that(ArabicScriptFold.fold(text, "fa").length).isEqualTo(text.length)
        val untouched = "کتاب"
        assertWithMessage("same instance when nothing changes")
            .that(ArabicScriptFold.fold(untouched, "fa")).isSameInstanceAs(untouched)
    }

    @Test
    fun `Kurdish folds only kaf and yeh`() {
        assertWithMessage("ك ي").that(ArabicScriptFold.fold("كوردي", "ckb")).isEqualTo("کوردی")
        assertWithMessage("ە is a Sorani letter").that(ArabicScriptFold.fold("خانە", "ckb")).isEqualTo("خانە")
        assertWithMessage("ة untouched").that(ArabicScriptFold.fold("خانة", "ckb")).isEqualTo("خانة")
    }

    @Test
    fun `other languages fold nothing`() {
        for (lang in listOf("ar", "ur", "en", "he", "ru", "", null)) {
            assertWithMessage("$lang").that(ArabicScriptFold.fold("كتاب خانۀ", lang)).isEqualTo("كتاب خانۀ")
            assertWithMessage("$lang inactive").that(ArabicScriptFold.isActive(lang)).isFalse()
        }
    }

    @Test
    fun `loaded keys are re-keyed with their prefix index and merged by max frequency`() {
        val dict = mutableMapOf("كتاب" to 100, "کتاب" to 900, "خانۀ" to 500, "hello" to 50)
        val index = HashMap<String, MutableSet<String>>()
        for (w in dict.keys) for (len in 1..minOf(3, w.length)) index.getOrPut(w.substring(0, len)) { HashSet() }.add(w)

        val changed = ArabicScriptFold.foldDictionaryKeys(dict, index, 3, "fa")

        assertWithMessage("two keys rewritten").that(changed).isEqualTo(2)
        assertWithMessage("merged keys").that(dict).containsExactly("کتاب", 900, "خانه", 500, "hello", 50)
        assertWithMessage("old prefix gone").that(index.keys).containsNoneOf("ك", "كت", "كتا")
        assertWithMessage("folded prefix present").that(index["خان"]).containsExactly("خانه")
        assertWithMessage("other prefixes untouched").that(index["hel"]).containsExactly("hello")
    }

    @Test
    fun `loaded keys are untouched for a language without a table`() {
        val dict = mutableMapOf("كتاب" to 100)
        assertWithMessage("ar: nothing").that(ArabicScriptFold.foldDictionaryKeys(dict, null, 3, "ar")).isEqualTo(0)
        assertWithMessage("key kept").that(dict.keys).containsExactly("كتاب")
    }
}
