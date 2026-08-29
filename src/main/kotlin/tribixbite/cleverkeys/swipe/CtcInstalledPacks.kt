package tribixbite.cleverkeys.swipe

import android.content.Context
import android.util.Log
import tribixbite.cleverkeys.BuildConfig
import tribixbite.cleverkeys.DirectBootAwarePreferences
import tribixbite.cleverkeys.langpack.LanguagePackManager
import tribixbite.cleverkeys.swipe.ctc.CtcImportedPackSupport
import tribixbite.cleverkeys.swipe.ctc.CtcLanguageSupport
import tribixbite.cleverkeys.swipe.geometric.CkdtDictionaryReader
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The device-side half of imported-language-pack support for the CTC swipe engine: finds each
 * pack's `dictionary.bin`, measures it once against [CtcImportedPackSupport]'s a–z-projectability
 * policy, and caches the verdict so the swipe dispatch path can answer "does CTC serve this
 * language" with a `stat` and a map lookup.
 *
 * The POLICY, the thresholds and the reasoning live in [CtcImportedPackSupport] — pure, so
 * `runPureTests` decodes through the same rules the device applies. This object is the impurity
 * boundary for it, exactly as [CtcEngineAdapter] is for the decoder: it owns the file access, the
 * preference-backed cache and the background thread, and registers itself into the pure seam via
 * [CtcImportedPackSupport.installResolver].
 *
 * ## Why the verdict is cached rather than recomputed
 *
 * Measuring a pack means parsing its whole canonical section — 20,000–50,000 UTF-8 strings, about
 * 2 MB — and projecting every word. That is background work, and the gate that needs the answer
 * (`InputCoordinator.performCtcSwipeTyping`) runs on the MAIN thread at the end of every swipe.
 * So the measurement is taken off-thread and the result is written to preferences keyed by the
 * pack file's identity ([CtcImportedPackSupport.packFingerprint] — length + mtime). A reimport
 * rewrites the file and moves its mtime, so the stored verdict stops matching and the pack is
 * re-measured; nothing has to notice the import.
 *
 * ## What an unmeasured pack does
 *
 * It answers **false** — the swipe goes to the geometric engine — and schedules the measurement.
 * That is the same "fall through to geometric rather than serve an empty slate" contract the
 * dead-session and missing-lexicon gates already keep, and it fails in the safe direction: the
 * user gets the engine they had before, once, and CTC from the next swipe on. It is never
 * correct to block the swipe on the read.
 */
object CtcInstalledPacks {

    private const val TAG = "CtcInstalledPacks"

    /**
     * ONE preference key for every pack's verdict.
     *
     * Not a key per language: `SettingsValidation.INTERNAL_KEYS` (which keeps device-local derived
     * state out of settings backups) matches keys EXACTLY, so a `ctc_pack_verdict_<code>` family
     * would leak a measurement of THIS device's files into every exported backup and then restore
     * it onto a device whose packs are different. One key is one entry in that set.
     */
    const val PREF_KEY = "ctc_langpack_verdicts"

    /** Verdicts by language code, mirroring [PREF_KEY]. Loaded once, written through. */
    private val cache = ConcurrentHashMap<String, CtcImportedPackSupport.CachedVerdict>()

    @Volatile
    private var cacheLoaded = false

    /** Codes with a measurement in flight, so a swipe storm schedules one evaluation, not many. */
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(HashSet())

    @Volatile
    private var appContext: Context? = null

    /**
     * Serializes background measurements; created on first use so an install that never imports a
     * pack never spawns a thread. Daemon, because this object outlives no one and must not hold
     * the process up.
     */
    private val evaluator: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "ctc-langpack-eval").apply { isDaemon = true }
        }
    }

    /**
     * Binds this object to the app context and registers it as
     * [CtcImportedPackSupport.installedPackResolver][CtcImportedPackSupport.installResolver].
     *
     * Idempotent, and called from every entry point that can be the FIRST thing in the process:
     * `CleverKeysService.onCreate` (the IME), `SettingsActivity.onCreate` (the settings UI) and
     * [CtcEngineAdapter]'s constructor (instrumented tests, which build an adapter directly).
     * Until it runs, [CtcImportedPackSupport.servesImportedPack] answers false for everything —
     * i.e. exactly the pre-2026-08-29 behaviour, which is the right failure mode for a missed
     * call site. `CoreImeHygieneDriftTest` pins all three.
     */
    fun bind(context: Context) {
        appContext = context.applicationContext
        CtcImportedPackSupport.installResolver(::servesImportedPack)
    }

    /**
     * [CtcImportedPackSupport.InstalledPackResolver]'s implementation — cheap enough for the
     * dispatch path: a `stat` on the pack file plus a map lookup.
     *
     * Returns false and SCHEDULES a measurement when the pack has never been measured, or was
     * measured on a different file (a reimport). See the class KDoc.
     */
    fun servesImportedPack(code: String): Boolean {
        val context = appContext ?: return false
        if (!CtcImportedPackSupport.mayServeImportedPack(code)) return false
        val lang = CtcLanguageSupport.normalize(code)
        val file = packFile(context, lang) ?: run {
            // No pack (or it was deleted) — forget any verdict so a later reimport is measured
            // rather than answered from a stale entry that happens to fingerprint-match.
            if (cache.remove(lang) != null) persist(context)
            return false
        }
        val fingerprint = fingerprintOf(file)
        val cached = cachedVerdict(context, lang)
        if (cached != null && cached.fingerprint == fingerprint) {
            return cached.report.eligible
        }
        scheduleEvaluation(context, lang)
        return false
    }

    /**
     * Measures [code]'s installed pack NOW, returning its [CtcImportedPackSupport.Report], or null
     * when there is no pack for it or the language is not a candidate at all.
     *
     * **Blocking and I/O-bound — never call it on the main thread.** Callers: the settings
     * fallback card (a `produceState` on `Dispatchers.IO`, which is what makes this the
     * derived-check-at-SELECTION-time the user can see and act on) and the language-pack import
     * handler (so a freshly imported pack is measured while the user is still looking at the
     * import toast). A cached verdict for the current file is returned without re-reading.
     */
    fun evaluateNow(context: Context, code: String): CtcImportedPackSupport.Report? {
        if (!CtcImportedPackSupport.mayServeImportedPack(code)) return null
        val lang = CtcLanguageSupport.normalize(code)
        val file = packFile(context, lang) ?: return null
        val fingerprint = fingerprintOf(file)
        cachedVerdict(context, lang)?.let { if (it.fingerprint == fingerprint) return it.report }
        return measure(context, lang, file, fingerprint)
    }

    /**
     * The cached verdict for [code] IF it was measured on the pack file currently installed, else
     * null (never installed, deleted, or reimported since). Cheap; safe on the main thread.
     */
    fun currentVerdict(context: Context, code: String): CtcImportedPackSupport.Report? {
        val lang = CtcLanguageSupport.normalize(code)
        val file = packFile(context, lang) ?: return null
        val cached = cachedVerdict(context, lang) ?: return null
        return if (cached.fingerprint == fingerprintOf(file)) cached.report else null
    }

    /** True when [code] has an installed pack, whatever its verdict. */
    fun hasInstalledPack(context: Context, code: String): Boolean =
        packFile(context, CtcLanguageSupport.normalize(code)) != null

    /**
     * Language codes CTC serves from an imported pack on THIS device, sorted.
     *
     * The honest complement to `CtcLanguageSupport.SUPPORTED.keys` for any surface that lists
     * served languages — without it the settings fallback card tells a user with an eligible pack
     * that CTC does not serve their language while CTC is serving it.
     *
     * Reads only MEASURED verdicts ([currentVerdict]) and schedules nothing: a pack that has not
     * been measured yet is not being served yet, so listing it would be the same lie in the other
     * direction. Safe on the main thread.
     */
    fun servedCodes(context: Context): List<String> =
        LanguagePackManager.getInstance(context).getInstalledPacks()
            .map { CtcLanguageSupport.normalize(it.code) }
            .filter {
                CtcLanguageSupport.SUPPORTED[it] == null &&
                    currentVerdict(context, it)?.eligible == true
            }
            .distinct()
            .sorted()

    /**
     * Drops [code]'s cached verdict — called when a pack is DELETED, so the next import of the
     * same language is measured afresh rather than inheriting a verdict whose fingerprint could
     * coincidentally match (same bytes restored with a preserved mtime).
     */
    fun invalidate(context: Context, code: String) {
        val lang = CtcLanguageSupport.normalize(code)
        loadCache(context)
        if (cache.remove(lang) != null) persist(context)
    }

    // ── internals ──────────────────────────────────────────────────────────────────────

    /**
     * [code]'s installed pack dictionary, or null when absent. Goes through
     * [LanguagePackManager] (the component that WRITES the file) rather than rebuilding the path,
     * and cross-checks the result against
     * [CtcLanguageSupport.candidateLangpackRelativePath] — the constant the trie build and the
     * geometric engine both resolve through — so a divergence between the two is a compile-time
     * concern instead of a silently unserved language.
     */
    private fun packFile(context: Context, code: String): File? {
        val fromManager = LanguagePackManager.getInstance(context).getDictionaryPath(code)
            ?: return null
        val expected = CtcLanguageSupport.candidateLangpackRelativePath(code)
            ?.let { File(context.filesDir, it) }
        if (expected != null && expected.absolutePath != fromManager.absolutePath) {
            Log.w(
                TAG,
                "langpack path divergence for '$code': manager=${fromManager.absolutePath} " +
                    "vs table=${expected.absolutePath} — the CTC trie reads the TABLE's path, so " +
                    "this language would be measured on one file and decoded from another"
            )
        }
        return fromManager
    }

    private fun fingerprintOf(file: File): String =
        CtcImportedPackSupport.packFingerprint(file.length(), file.lastModified())

    private fun cachedVerdict(
        context: Context,
        code: String,
    ): CtcImportedPackSupport.CachedVerdict? {
        loadCache(context)
        return cache[code]
    }

    private fun loadCache(context: Context) {
        if (cacheLoaded) return
        synchronized(this) {
            if (cacheLoaded) return
            val stored = DirectBootAwarePreferences.get_shared_preferences(context)
                .getString(PREF_KEY, null)
            cache.putAll(CtcImportedPackSupport.decodeVerdicts(stored))
            cacheLoaded = true
        }
    }

    private fun persist(context: Context) {
        synchronized(this) {
            DirectBootAwarePreferences.get_shared_preferences(context)
                .edit()
                .putString(PREF_KEY, CtcImportedPackSupport.encodeVerdicts(cache))
                .apply()
        }
    }

    private fun scheduleEvaluation(context: Context, code: String) {
        if (!inFlight.add(code)) return
        val app = context.applicationContext
        try {
            evaluator.execute {
                try {
                    val file = packFile(app, code)
                    if (file != null) measure(app, code, file, fingerprintOf(file))
                } catch (e: Exception) {
                    Log.e(TAG, "background langpack evaluation failed for '$code'", e)
                } finally {
                    inFlight.remove(code)
                }
            }
        } catch (e: RuntimeException) {
            // Rejected (executor shut down by process teardown) — drop the reservation so a later
            // call can retry rather than latching this language off for the process's lifetime.
            inFlight.remove(code)
            Log.w(TAG, "could not schedule langpack evaluation for '$code'", e)
        }
    }

    /**
     * Reads [file], measures it, caches and persists the verdict, and returns the report.
     *
     * A file that cannot be parsed as CKDT v2 yields zero words and therefore
     * [CtcImportedPackSupport.Verdict.TOO_FEW_WORDS] — literally true of what was read, and it
     * caches like any other rejection so a corrupt pack is not re-read on every swipe.
     * `LanguagePackManager` validates the magic and version at IMPORT, so this is the
     * out-of-band case (a truncated write, a hand-copied file), not the ordinary one.
     */
    private fun measure(
        context: Context,
        code: String,
        file: File,
        fingerprint: String,
    ): CtcImportedPackSupport.Report {
        val started = System.currentTimeMillis()
        val words = try {
            file.inputStream().use { CkdtDictionaryReader.readEntries(it) }.map { it.word }
        } catch (e: Exception) {
            Log.e(TAG, "unreadable language pack for '$code' (${file.absolutePath})", e)
            emptyList()
        }
        val report = CtcImportedPackSupport.evaluate(code, words)
        loadCache(context)
        cache[code] = CtcImportedPackSupport.CachedVerdict(fingerprint, report)
        persist(context)
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
            Log.d(
                TAG,
                "langpack '$code' measured in ${System.currentTimeMillis() - started}ms: $report"
            )
        } else if (!report.eligible) {
            // Always logged, verbose or not: a pack the user imported and cannot swipe with is a
            // silent outcome otherwise, and this line is the only record of WHY.
            Log.i(
                TAG,
                "CTC will not serve '$code' from its language pack: ${report.verdict} " +
                    "(${report.projectable}/${report.words} words have an a-z spelling) — " +
                    "swipes stay on the geometric engine"
            )
        }
        return report
    }
}
