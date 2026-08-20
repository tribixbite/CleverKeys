package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import tribixbite.cleverkeys.langpack.LanguagePackManager
import tribixbite.cleverkeys.swipe.SwipeContractionPolicy
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import java.io.File

/**
 * Computes cross-language contraction collisions for a chosen set of languages, at the moment
 * the user chooses them.
 *
 * ## Why here, and not anywhere else
 *
 * A REPLACE-mode contraction key is an alias with no reading of its own — but only in ITS OWN
 * language. When two languages are active the merged map can rewrite a real word of the other
 * one ([ContractionCollisionDemotion] has the measured casualties: fr+en `dont` → `don't`,
 * de+en `im` → `I'm`).
 *
 * For BUNDLED languages that is solved by shipped `contraction_collisions_<lang>.json` sidecars,
 * built offline by `scripts/build_contraction_collisions.py`. An IMPORTED language pack cannot
 * have one: its contraction file and its dictionary arrive on the device long after the build.
 *
 * Three places could close that gap, and this is the third:
 *
 *  - **At import** — wrong moment. A pack collides with whatever is active NOW, and the user
 *    imports packs they do not immediately enable. The answer would go stale the next time they
 *    changed languages.
 *  - **At every keystroke** — wrong cost. The other language's lexicon is not even resident
 *    while typing ([DictionaryManager] holds one predictor, for the current language).
 *  - **At language selection** — right on both counts. It is exactly the event that determines
 *    which languages are active, it happens in Settings where a few hundred milliseconds and a
 *    transient lexicon are affordable, and it is the one moment the user is present to be TOLD.
 *
 * So the scan runs when primary, secondary, or either alternate is chosen; the result is cached
 * for [ContractionManager] to merge alongside the shipped sidecars, and reported back so the UI
 * can warn.
 *
 * ## Memory
 *
 * Peak is ONE lexicon plus ONE key set. Pairs are processed one at a time and each lexicon is
 * released before the next is read, because this class runs in the Settings process alongside
 * everything else and a 40k-surface `HashSet` per bundled language would add up. Do not "optimise"
 * this into a map of all lexicons.
 */
object ContractionCollisionScanner {

    private const val TAG = "CollisionScanner"

    /** Prefs key holding the cached scan, as `{key: [lang, …]}`. */
    const val PREFS_KEY = "contraction_pack_collisions"

    /** Prefs key holding the language set the cached scan was computed for. */
    const val PREFS_SCOPE_KEY = "contraction_pack_collisions_scope"

    /**
     * What a scan found, for the caller to persist and to show.
     *
     * @param packCollisions `key -> colliding language codes`, covering ONLY keys the shipped
     *   sidecars do not already handle — i.e. those contributed by imported packs. Merging this
     *   with the sidecars is [ContractionManager]'s job.
     * @param bundledCollisionCount how many collisions the shipped sidecars already handle for
     *   this language set. Not persisted; reported so the warning can be honest that most of the
     *   protection is pre-existing rather than implying the scan found it all.
     * @param examples a few `key -> display` pairs, for showing the user what this concretely
     *   means. Drawn from the pack collisions first, since those are the news.
     * @param scannedLanguages the language set this result is valid for.
     */
    data class Report(
        val packCollisions: Map<String, Set<String>>,
        val bundledCollisionCount: Int,
        val examples: List<Pair<String, String>>,
        val scannedLanguages: Set<String>,
    ) {
        /** True when an imported pack contributed at least one collision — the warnable case. */
        val hasPackCollisions: Boolean get() = packCollisions.isNotEmpty()
    }

    /**
     * Scan [languages] for collisions, reading bundled assets and installed packs.
     *
     * Blocking and IO-heavy — call off the main thread. Returns an empty report rather than
     * throwing if anything is unreadable: a failed scan must degrade to "no extra protection",
     * never to a broken Settings screen.
     */
    fun scan(context: Context, languages: Set<String>): Report {
        val active = languages
            .map { it.substringBefore('-').lowercase() }
            .filter { it.isNotBlank() && it != "none" }
            .toSet()
        if (active.size < 2) {
            // A single active language cannot collide with anything: every REPLACE key it holds
            // was classified against its own lexicon at generation time.
            return Report(emptyMap(), 0, emptyList(), active)
        }

        val packs = LanguagePackManager.getInstance(context)
        val shipped = active.associateWith { shippedSidecar(context, it) }
        var bundledCount = 0
        val packCollisions = HashMap<String, MutableSet<String>>()
        val examples = ArrayList<Pair<String, String>>()

        for (owner in active) {
            val mappings = replaceMappings(context, packs, owner)
            if (mappings.isEmpty()) continue
            val sidecar = shipped.getValue(owner)

            for (other in active) {
                if (other == owner) continue
                // One lexicon resident at a time — see the class KDoc on memory.
                val surfaces = lexiconSurfaces(context, packs, other)
                if (surfaces.isEmpty()) continue

                for ((key, display) in mappings) {
                    if (key !in surfaces) continue
                    if (other in sidecar[key].orEmpty()) {
                        bundledCount++
                        continue
                    }
                    packCollisions.getOrPut(key) { mutableSetOf() }.add(other)
                    if (examples.size < EXAMPLE_LIMIT && examples.none { it.first == key }) {
                        examples.add(key to display)
                    }
                }
            }
        }

        Log.d(TAG, "scan(${active.sorted()}): ${packCollisions.size} pack collisions, " +
            "$bundledCount already covered by shipped sidecars")
        return Report(packCollisions, bundledCount, examples, active)
    }

    private const val EXAMPLE_LIMIT = 5

    /**
     * `key -> display` REPLACE mappings for [lang], as [ContractionManager] ends up holding them.
     *
     * "As it ends up holding them" is the whole difficulty, and getting it wrong is not
     * cosmetic — a key this function reports that the runtime never applies produces a demotion
     * that suppresses a working contraction, and one it misses leaves a real word unprotected.
     *
     * English is the special case: its base is `contractions_non_paired.json`, and
     * `loadEnglishBase` then reclassifies every key that is also a base in
     * `contraction_pairings.json` out of the non-paired map — the 2026-07-23 fix that stopped
     * `well` → `we'll` destroying the word "well". `contractions_en.json` repeats 14 of those
     * bases, so they must be subtracted here too, exactly as
     * `scripts/build_contraction_collisions.py` does when building the sidecars. The two models
     * disagreeing is precisely how the re-add bug in `loadContractionsFromStream` was found.
     */
    private fun replaceMappings(
        context: Context,
        packs: LanguagePackManager,
        lang: String,
    ): Map<String, String> {
        // Mirrors ContractionManager.loadLanguageContractions: a pack that supplies its own
        // contractions file wins OUTRIGHT and the bundled file for that language is skipped.
        // Scanning both would report collisions for mappings the runtime never loads.
        packs.getContractionsPath(lang)?.let { file ->
            runCatching { return parseMappings(file.readText()) }
                .onFailure { Log.w(TAG, "unreadable pack contractions for $lang: ${it.message}") }
        }
        val bundled = runCatching {
            parseMappings(context.assets.open("dictionaries/contractions_$lang.json")
                .bufferedReader().use { it.readText() })
        }.getOrElse { emptyMap() }
        if (lang != SwipeContractionPolicy.ENGLISH) return bundled

        val base = runCatching {
            parseMappings(context.assets.open("dictionaries/contractions_non_paired.json")
                .bufferedReader().use { it.readText() })
        }.getOrElse { emptyMap() }
        val pairedBases = runCatching {
            val obj = JSONObject(context.assets.open("dictionaries/contraction_pairings.json")
                .bufferedReader().use { it.readText() })
            val out = HashSet<String>(obj.length() * 2)
            val keys = obj.keys()
            while (keys.hasNext()) out.add(keys.next().lowercase())
            out
        }.getOrElse { emptySet() }

        return (base + bundled).filterKeys { it !in pairedBases }
    }

    private fun parseMappings(json: String): Map<String, String> {
        val obj = JSONObject(json)
        val out = HashMap<String, String>(obj.length() * 2)
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key.lowercase()] = obj.optString(key).lowercase()
        }
        return out
    }

    /** Lowercased lexicon surfaces for [lang]: pack dictionary, else bundled CKDT, else JSON. */
    private fun lexiconSurfaces(
        context: Context,
        packs: LanguagePackManager,
        lang: String,
    ): Set<String> {
        packs.getDictionaryPath(lang)?.let { file ->
            runCatching {
                return file.inputStream().use { CkdtDictionaryReader.readEntries(it) }
                    .mapTo(HashSet()) { it.word.lowercase() }
            }.onFailure { Log.w(TAG, "unreadable pack dictionary for $lang: ${it.message}") }
        }
        runCatching {
            return context.assets.open("dictionaries/${lang}_enhanced.bin")
                .use { CkdtDictionaryReader.readEntries(it) }
                .mapTo(HashSet()) { it.word.lowercase() }
        }
        // English ships JSON rather than CKDT.
        return runCatching {
            val obj = JSONObject(context.assets.open("dictionaries/${lang}_enhanced.json")
                .bufferedReader().use { it.readText() })
            val out = HashSet<String>(obj.length() * 2)
            val keys = obj.keys()
            while (keys.hasNext()) out.add(keys.next().lowercase())
            out
        }.getOrElse { emptySet() }
    }

    /** The shipped sidecar for [lang], or empty when it has none. */
    private fun shippedSidecar(context: Context, lang: String): Map<String, Set<String>> =
        runCatching {
            val obj = JSONObject(
                context.assets.open(ContractionCollisionDemotion.assetName(lang))
                    .bufferedReader().use { it.readText() }
            )
            val out = HashMap<String, Set<String>>(obj.length() * 2)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = obj.getJSONArray(key)
                out[key.lowercase()] = (0 until arr.length()).mapTo(HashSet()) { arr.getString(it) }
            }
            out
        }.getOrElse { emptyMap() }

    // ── cache ────────────────────────────────────────────────────────────────────────

    /** Persist [report] so [ContractionManager] can merge it alongside the shipped sidecars. */
    fun cache(context: Context, report: Report) {
        val obj = JSONObject()
        for ((key, langs) in report.packCollisions) obj.put(key, JSONArray(langs.sorted()))
        DirectBootAwarePreferences.get_shared_preferences(context).edit()
            .putString(PREFS_KEY, obj.toString())
            .putString(PREFS_SCOPE_KEY, report.scannedLanguages.sorted().joinToString(","))
            .apply()
    }

    /**
     * The cached pack collisions, but ONLY if they were computed for [languages].
     *
     * The scope check is what makes a stale cache harmless. If the user changes languages by a
     * route that does not re-scan (a restored backup, a pref edited by an import), the cached
     * table describes a different language set and applying it would demote keys for a language
     * that is no longer active — suppressing correct contractions. Returning empty instead means
     * the worst case is the pre-2026-08-20 behaviour for packs, which is what this feature is
     * improving on, rather than a new wrong behaviour it introduces.
     */
    fun cachedFor(context: Context, languages: Set<String>): Map<String, Set<String>> {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        val scope = prefs.getString(PREFS_SCOPE_KEY, null) ?: return emptyMap()
        val wanted = languages.map { it.substringBefore('-').lowercase() }
            .filter { it.isNotBlank() && it != "none" }.sorted().joinToString(",")
        if (scope != wanted) {
            Log.d(TAG, "cached collisions are for '$scope', active is '$wanted' — ignoring")
            return emptyMap()
        }
        val json = prefs.getString(PREFS_KEY, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            val out = HashMap<String, Set<String>>(obj.length() * 2)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = obj.getJSONArray(key)
                out[key] = (0 until arr.length()).mapTo(HashSet()) { arr.getString(it) }
            }
            out
        }.getOrElse { emptyMap() }
    }
}
