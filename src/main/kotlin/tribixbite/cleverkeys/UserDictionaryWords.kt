package tribixbite.cleverkeys

import android.content.Context
import android.provider.UserDictionary
import android.util.Log
import tribixbite.cleverkeys.swipe.UserDictionarySnapshot

/**
 * The ONE locale-filtered read of the platform user dictionary
 * (`android.provider.UserDictionary.Words`).
 *
 * Extracted from [WordPredictor] when ARC-081 gave the swipe adapters the same words the tap
 * path already had. The point of extracting rather than adding a third copy is that the LOCALE
 * FILTER is the delicate part and it must be identical everywhere, or the two paths disagree
 * about which words exist:
 *
 *  - a row whose `LOCALE` equals the language code, or starts with it (`fr` matches `fr_FR`,
 *    `fr_CA`), always matches;
 *  - a row with a NULL locale — an untagged word, which is what most callers of
 *    `UserDictionary.Words.addWord(context, word, freq, null, null)` produce — matches ONLY
 *    for English. Untagged words are overwhelmingly English in practice, and admitting them
 *    into a French or German lexicon is the "English contamination" v1.2.0 fixed.
 *
 * Failures are swallowed and logged: the provider is optional (a device can deny the read, and
 * some ROMs ship no user-dictionary provider at all), and neither typing nor swiping may break
 * because a personal dictionary could not be listed.
 */
object UserDictionaryWords {

    private const val TAG = "UserDictionaryWords"

    private val snapshotCache = UserDictionarySnapshotCache()

    /**
     * Frequency used when the provider omits the column. Matches what the tap path has always
     * substituted, so the two paths rank a column-less row identically.
     */
    const val DEFAULT_FREQUENCY = 1000

    /**
     * The provider's rows for [language] as `(word as stored, observed frequency)`, in provider
     * order. Empty when the provider is unavailable or the read fails.
     *
     * Word case is preserved — the caller decides whether to fold it (the tap path keeps the
     * original for proper nouns, `userWordOriginalCase`).
     */
    fun read(context: Context, language: String): List<Pair<String, Int>> {
        val rows = ArrayList<Pair<String, Int>>()
        try {
            // Only English admits untagged (NULL-locale) rows — see the class KDoc.
            val selection = if (language == "en") {
                "${UserDictionary.Words.LOCALE} = ? OR ${UserDictionary.Words.LOCALE} LIKE ? " +
                    "OR ${UserDictionary.Words.LOCALE} IS NULL"
            } else {
                "${UserDictionary.Words.LOCALE} = ? OR ${UserDictionary.Words.LOCALE} LIKE ?"
            }
            val selectionArgs = arrayOf(language, "$language%")

            val cursor = context.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.FREQUENCY),
                selection,
                selectionArgs,
                null
            )
            cursor?.use {
                val wordIndex = it.getColumnIndex(UserDictionary.Words.WORD)
                val freqIndex = it.getColumnIndex(UserDictionary.Words.FREQUENCY)
                if (wordIndex < 0) return@use
                while (it.moveToNext()) {
                    val word = it.getString(wordIndex) ?: continue
                    val freq = if (freqIndex >= 0) it.getInt(freqIndex) else DEFAULT_FREQUENCY
                    rows.add(word to freq)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read user dictionary for '$language'", e)
            return emptyList()
        }
        return rows
    }

    /**
     * [read] normalized into the pure snapshot the swipe adapters merge and fingerprint
     * (ARC-081), reused through the observer-gated epoch cache from ARC-102.
     */
    fun snapshot(context: Context, language: String): UserDictionarySnapshot =
        snapshotCache.snapshot(language) { lang ->
            UserDictionarySnapshot.of(read(context, lang))
        }

    /** Provider-wide invalidation signal; called before an observer reloads its local cache. */
    internal fun onProviderChanged() = snapshotCache.providerChanged()

    /** Enables snapshot reuse only after a real observer has been registered. */
    internal fun onObserverStarted() = snapshotCache.observerStarted()

    /** Disables and drops snapshot reuse when the final observer is gone. */
    internal fun onObserverStopped() = snapshotCache.observerStopped()
}
