package tribixbite.cleverkeys

import android.util.Log

/**
 * Prefix index for fast dictionary lookup with accent normalization support.
 *
 * This index is built on **normalized** (accent-stripped) words, allowing the
 * neural swipe model's 26-letter output to match accented dictionary entries.
 *
 * ## How It Works
 * 1. User swipes "café" → NN outputs "cafe" (26-letter vocab)
 * 2. Prefix lookup: "caf" finds normalized candidates
 * 3. Each candidate maps back to canonical (accented) form
 *
 * ## Data Structure
 * - `prefixIndex`: Map<normalized_prefix, Set<normalized_words>>
 * - `canonicalMap`: Map<normalized_word, List<canonical_forms>>
 * - `frequencyMap`: Map<canonical_word, frequency_rank>
 *
 * @since v1.2.0 - Multilanguage support
 */
class NormalizedPrefixIndex {

    companion object {
        private const val TAG = "NormalizedPrefixIndex"
        private const val PREFIX_MAX_LENGTH = 3  // Index prefixes up to 3 chars
    }

    // Prefix → set of normalized words starting with that prefix
    private val prefixIndex = mutableMapOf<String, MutableSet<String>>()

    // Normalized word → list of canonical forms (sorted by frequency)
    private val canonicalMap = mutableMapOf<String, MutableList<CanonicalEntry>>()

    // Total word count for statistics
    private var wordCount = 0

    /**
     * Entry for a canonical word form with its frequency rank.
     *
     * @property canonical The display form (may have accents)
     * @property frequencyRank 0-255, lower = more common
     */
    data class CanonicalEntry(
        val canonical: String,
        val frequencyRank: Int
    )

    /**
     * Result of a prefix lookup.
     *
     * @property normalized The normalized (accent-stripped) word
     * @property canonicals List of canonical forms sorted by frequency
     * @property bestCanonical The most frequent canonical form
     * @property bestFrequencyRank Frequency rank of best canonical (0-255)
     */
    data class LookupResult(
        val normalized: String,
        val canonicals: List<String>,
        val bestCanonical: String,
        val bestFrequencyRank: Int
    )

    /**
     * Add a word to the index.
     *
     * @param canonical The display form (may have accents)
     * @param frequencyRank Frequency rank 0-255 (0 = most common)
     */
    fun addWord(canonical: String, frequencyRank: Int) {
        val normalized = AccentNormalizer.normalize(canonical)
        if (normalized.isEmpty()) return

        // Add to canonical map
        val entry = CanonicalEntry(canonical, frequencyRank)
        canonicalMap.getOrPut(normalized) { mutableListOf() }.add(entry)

        // Add to prefix index (prefixes of length 1 to PREFIX_MAX_LENGTH)
        val maxLen = minOf(PREFIX_MAX_LENGTH, normalized.length)
        for (len in 1..maxLen) {
            val prefix = normalized.substring(0, len)
            prefixIndex.getOrPut(prefix) { mutableSetOf() }.add(normalized)
        }

        wordCount++
    }

    /**
     * Build index from a list of words with frequencies.
     *
     * @param words List of (canonical, frequencyRank) pairs
     */
    fun buildFromList(words: List<Pair<String, Int>>) {
        clear()
        for ((canonical, frequencyRank) in words) {
            addWord(canonical, frequencyRank)
        }
        // Sort canonical lists by frequency after all words added
        sortCanonicalsByFrequency()
        Log.i(TAG, "Built index: $wordCount words, ${prefixIndex.size} prefixes, " +
                "${canonicalMap.size} normalized forms")
    }

    /**
     * Sort all canonical entry lists by frequency (lowest rank = most common first).
     */
    private fun sortCanonicalsByFrequency() {
        for ((_, entries) in canonicalMap) {
            entries.sortBy { it.frequencyRank }
        }
    }

    /**
     * Get all words starting with a prefix.
     *
     * @param prefix The prefix to search for (will be normalized)
     * @return List of LookupResults for matching words
     */
    fun getWordsWithPrefix(prefix: String): List<LookupResult> {
        val normalizedPrefix = AccentNormalizer.normalize(prefix)
        if (normalizedPrefix.isEmpty()) return emptyList()

        // Use prefix index for O(1) lookup when prefix is short
        val lookupPrefix = if (normalizedPrefix.length <= PREFIX_MAX_LENGTH) {
            normalizedPrefix
        } else {
            normalizedPrefix.substring(0, PREFIX_MAX_LENGTH)
        }

        val candidates = prefixIndex[lookupPrefix] ?: return emptyList()

        // Filter to words that match the full prefix
        val matchingWords = if (normalizedPrefix.length > PREFIX_MAX_LENGTH) {
            candidates.filter { it.startsWith(normalizedPrefix) }
        } else {
            candidates.filter { it.startsWith(normalizedPrefix) }
        }

        // Build results
        return matchingWords.mapNotNull { normalized ->
            val entries = canonicalMap[normalized] ?: return@mapNotNull null
            if (entries.isEmpty()) return@mapNotNull null

            LookupResult(
                normalized = normalized,
                canonicals = entries.map { it.canonical },
                bestCanonical = entries.first().canonical,
                bestFrequencyRank = entries.first().frequencyRank
            )
        }.sortedBy { it.bestFrequencyRank }  // Sort by frequency
    }

    /**
     * Look up a specific normalized word.
     *
     * @param normalized The normalized (accent-stripped) word
     * @return LookupResult or null if not found
     */
    fun lookup(normalized: String): LookupResult? {
        val entries = canonicalMap[normalized] ?: return null
        if (entries.isEmpty()) return null

        return LookupResult(
            normalized = normalized,
            canonicals = entries.map { it.canonical },
            bestCanonical = entries.first().canonical,
            bestFrequencyRank = entries.first().frequencyRank
        )
    }

    /**
     * Check if a normalized word exists in the index.
     *
     * @param normalized The normalized word to check
     * @return true if word exists
     */
    fun contains(normalized: String): Boolean {
        return canonicalMap.containsKey(normalized)
    }

    /**
     * Get the best canonical form for a normalized word.
     *
     * @param normalized The normalized word
     * @return Best (most frequent) canonical form, or normalized if not found
     */
    fun getBestCanonical(normalized: String): String {
        val entries = canonicalMap[normalized]
        return entries?.firstOrNull()?.canonical ?: normalized
    }

    /**
     * Get frequency rank for a normalized word.
     *
     * @param normalized The normalized word
     * @return Frequency rank (0-255), or 255 if not found
     */
    fun getFrequencyRank(normalized: String): Int {
        val entries = canonicalMap[normalized]
        return entries?.firstOrNull()?.frequencyRank ?: 255
    }

    /**
     * Remove a word from the index.
     *
     * @param canonical The canonical form to remove
     */
    fun removeWord(canonical: String) {
        val normalized = AccentNormalizer.normalize(canonical)
        val entries = canonicalMap[normalized] ?: return

        // Remove the specific canonical entry
        entries.removeIf { it.canonical == canonical }

        // If no more canonicals for this normalized form, remove from prefix index
        if (entries.isEmpty()) {
            canonicalMap.remove(normalized)

            val maxLen = minOf(PREFIX_MAX_LENGTH, normalized.length)
            for (len in 1..maxLen) {
                val prefix = normalized.substring(0, len)
                prefixIndex[prefix]?.remove(normalized)
                if (prefixIndex[prefix]?.isEmpty() == true) {
                    prefixIndex.remove(prefix)
                }
            }
        }

        wordCount--
    }

    /**
     * Clear the entire index.
     */
    fun clear() {
        prefixIndex.clear()
        canonicalMap.clear()
        wordCount = 0
    }

    /**
     * Get total number of canonical words in the index.
     */
    fun size(): Int = wordCount

    /**
     * Get number of unique normalized forms.
     */
    fun normalizedCount(): Int = canonicalMap.size

    /**
     * Get number of prefix entries.
     */
    fun prefixCount(): Int = prefixIndex.size

    /**
     * Merge another index into this one.
     *
     * Used for combining primary and secondary language dictionaries.
     * Words from the other index are added; if a normalized form already
     * exists, the canonical entries are merged and re-sorted by frequency.
     *
     * @param other The index to merge from
     * @param frequencyPenalty Penalty to apply to other's frequency ranks (0-255 added)
     */
    fun merge(other: NormalizedPrefixIndex, frequencyPenalty: Int = 0) {
        for ((normalized, entries) in other.canonicalMap) {
            val existing = canonicalMap.getOrPut(normalized) { mutableListOf() }

            for (entry in entries) {
                // Apply penalty to frequency rank (cap at 255)
                val adjustedRank = minOf(255, entry.frequencyRank + frequencyPenalty)
                existing.add(CanonicalEntry(entry.canonical, adjustedRank))
            }

            // Add to prefix index if new
            if (!prefixIndex.values.any { it.contains(normalized) }) {
                val maxLen = minOf(PREFIX_MAX_LENGTH, normalized.length)
                for (len in 1..maxLen) {
                    val prefix = normalized.substring(0, len)
                    prefixIndex.getOrPut(prefix) { mutableSetOf() }.add(normalized)
                }
            }
        }

        // Re-sort all affected canonical lists
        sortCanonicalsByFrequency()

        // Update word count
        wordCount = canonicalMap.values.sumOf { it.size }

        Log.i(TAG, "Merged index: now $wordCount words, ${canonicalMap.size} normalized forms")
    }
}
