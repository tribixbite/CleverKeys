package tribixbite.cleverkeys.backup

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Applies the learned-data sections of a dictionaries backup payload and counts what was
 * ACTUALLY restored (audit M2, 2026-10-20).
 *
 * The sections are `learned_bigrams_by_language` and `learned_trigrams_by_language` (objects
 * of language -> entry array, merged by the n-gram stores) and `user_vocabulary` (an array,
 * replaced by `UserVocabulary`). Before this existed, the stores swallowed every exception and
 * returned nothing, and the restore counted each section's array SIZE as restored — so a
 * corrupt section produced a success report with nothing imported.
 *
 * Semantics:
 *  - An ABSENT section is a silent no-op (older backups carry no trigram section — ARC-022's
 *    backward-compatibility contract).
 *  - Each section is applied independently: a malformed bigram section cannot cost the
 *    trigrams or the vocabulary, and vice versa.
 *  - Within an n-gram section each language is applied independently; the stores themselves
 *    skip individual malformed entries and return how many they merged. An entry the store
 *    did not merge — malformed, or one the learn path would never produce — counts as
 *    [Counts.skippedEntries].
 *  - A section that is present but not the expected shape, a language whose value is not an
 *    array, a non-object payload, or a sink that throws is recorded in
 *    [Counts.failedSections] and reported through [warn].
 *
 * Pure (no Android types): the sinks are passed in, so `runPureTests` covers it.
 */
internal object LearnedDataImport {

    const val BIGRAMS = "learned_bigrams_by_language"
    const val TRIGRAMS = "learned_trigrams_by_language"
    const val VOCABULARY = "user_vocabulary"

    /** What one learned-data import applied. */
    data class Counts(
        val bigrams: Int = 0,
        val trigrams: Int = 0,
        val vocabulary: Int = 0,
        /** N-gram entries present in the payload that the stores did not merge. */
        val skippedEntries: Int = 0,
        /** Section (or `section/language`) names that could not be applied at all. */
        val failedSections: List<String> = emptyList(),
    ) {
        /** True when some learned data in the payload was NOT restored. */
        val incomplete: Boolean get() = skippedEntries > 0 || failedSections.isNotEmpty()
    }

    /**
     * Apply [json]'s learned-data sections.
     *
     * @param importBigrams merges one language's bigram array; returns entries merged
     * @param importTrigrams merges one language's trigram array; returns entries merged
     * @param importVocabulary replaces the user vocabulary; returns words imported
     * @param warn receives a description of every skipped entry group or failed section
     */
    fun apply(
        json: String,
        importBigrams: (language: String, entriesJson: String) -> Int,
        importTrigrams: (language: String, entriesJson: String) -> Int,
        importVocabulary: (entriesJson: String) -> Int,
        warn: (message: String, cause: Throwable?) -> Unit,
    ): Counts {
        val root: JsonObject = try {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            warn("Learned-data payload is not valid JSON", e)
            null
        } ?: return Counts(failedSections = listOf("payload"))

        val failed = mutableListOf<String>()
        var skipped = 0

        fun ngrams(section: String, sink: (String, String) -> Int): Int {
            val element = root.get(section) ?: return 0
            if (!element.isJsonObject) {
                warn("Learned-data section $section is not an object; skipped", null)
                failed += section
                return 0
            }
            var applied = 0
            for ((language, entries) in element.asJsonObject.entrySet()) {
                applied += ngramLanguage(section, language, entries, sink, failed, warn) { skipped += it }
            }
            return applied
        }

        val bigrams = ngrams(BIGRAMS, importBigrams)
        val trigrams = ngrams(TRIGRAMS, importTrigrams)

        var vocabulary = 0
        root.get(VOCABULARY)?.let { element ->
            if (!element.isJsonArray) {
                warn("Learned-data section $VOCABULARY is not an array; skipped", null)
                failed += VOCABULARY
            } else {
                try {
                    vocabulary = importVocabulary(element.toString())
                } catch (e: Exception) {
                    warn("User-vocabulary import failed", e)
                    failed += VOCABULARY
                }
            }
        }
        return Counts(bigrams, trigrams, vocabulary, skipped, failed.toList())
    }

    /** One language of an n-gram section; returns entries merged and reports the rest. */
    private fun ngramLanguage(
        section: String,
        language: String,
        entries: JsonElement,
        sink: (String, String) -> Int,
        failed: MutableList<String>,
        warn: (String, Throwable?) -> Unit,
        onSkipped: (Int) -> Unit,
    ): Int {
        if (!entries.isJsonArray) {
            warn("Learned-data $section/$language is not an array; skipped", null)
            failed += "$section/$language"
            return 0
        }
        val present = entries.asJsonArray.size()
        val applied = try {
            sink(language, entries.toString())
        } catch (e: Exception) {
            warn("Learned-data $section/$language import failed", e)
            failed += "$section/$language"
            return 0
        }
        if (applied < present) {
            warn("Learned-data $section/$language: ${present - applied} of $present entries not restored", null)
            onSkipped(present - applied)
        }
        return applied
    }
}
