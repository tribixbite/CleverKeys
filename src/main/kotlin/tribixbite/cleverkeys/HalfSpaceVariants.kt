package tribixbite.cleverkeys

/**
 * Dictionary words that differ from a typed word only by Persian half-spaces (U+200C between
 * Arabic-script letters, [WordChars]). Pure JVM.
 *
 * Decision (d) (research `docs/research/2026-10-21-persian-zwnj.md` §7): autocorrect never adds,
 * removes or moves a half-space, because the joined spellings are real, frequent words
 * (`میکنم` is MORE frequent than `می‌کنم`), the joined string cannot say where a split belongs
 * (`نامهای` is `نامه‌ای` or `نام‌های`), and `بهروز`/`به‌روز` differ in meaning. The other spelling
 * is OFFERED instead: typing `میشود` puts the dictionary's `می‌شود` in the suggestion bar, and
 * typing `می‌شود` offers `میشود` when that is a dictionary word too. The typed word keeps the
 * space-bar commit either way.
 *
 * Built from the dictionary keys: only words with a joining half-space are indexed, keyed by
 * their stripped spelling ([WordChars.stripJoiningZwnj]). Empty for every dictionary without
 * one (all Latin/Cyrillic/Greek/Hebrew packs), so [variantsOf] costs one string scan there.
 */
class HalfSpaceVariants private constructor(
    private val byStripped: Map<String, List<String>>,
) {
    /** Number of indexed half-space words. */
    val size: Int get() = byStripped.values.sumOf { it.size }

    /**
     * The other spellings of [typed] that are dictionary words: every indexed half-space form
     * with the same letters, plus the joined form when [isWord] accepts it — never [typed]
     * itself. Order: half-space forms as indexed (dictionary iteration order), then the joined
     * form.
     */
    fun variantsOf(typed: String, isWord: (String) -> Boolean): List<String> {
        if (typed.isEmpty()) return emptyList()
        val stripped = WordChars.stripJoiningZwnj(typed)
        val forms = byStripped[stripped]
        val joinedIsOther = stripped != typed && isWord(stripped)
        if (forms == null && !joinedIsOther) return emptyList()
        val out = ArrayList<String>((forms?.size ?: 0) + 1)
        forms?.forEach { if (it != typed) out.add(it) }
        if (joinedIsOther) out.add(stripped)
        return out
    }

    companion object {
        /** No half-space words: [variantsOf] only ever returns the joined form. */
        val EMPTY = HalfSpaceVariants(emptyMap())

        /** Index every key of [words] that carries a joining half-space. */
        fun build(words: Iterable<String>): HalfSpaceVariants {
            var map: HashMap<String, MutableList<String>>? = null
            for (word in words) {
                if (word.indexOf(WordChars.ZWNJ) < 0) continue
                val stripped = WordChars.stripJoiningZwnj(word)
                if (stripped == word) continue
                if (map == null) map = HashMap()
                map.getOrPut(stripped) { ArrayList(1) }.add(word)
            }
            return if (map == null) EMPTY else HalfSpaceVariants(map)
        }
    }
}
