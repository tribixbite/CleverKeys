package tribixbite.cleverkeys

import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import tribixbite.cleverkeys.contextaware.BigramStore
import tribixbite.cleverkeys.contextaware.ContextModel

/**
 * Drives the REAL [CleverKeysService] for learning tests (test-gap audit 2026-11-28, items 10 and
 * 11): keys go in through the service's own [KeyEventHandler.key_up], the entry Keyboard2View's
 * pointers use, so the whole typing → commit → selection update → learn funnel path runs as it
 * does on a phone, and the learned bigrams are read from the predictor's live store.
 *
 * What it restores: [setUp] makes CleverKeys the keyboard and turns learning on, [tearDown] puts
 * the keyboard and the three learning preferences back. What it does NOT restore: the words the
 * tests type are learned for real (their pairs and personal-vocabulary counts stay in the app's
 * learned stores), exactly as typing them by hand would. Taking them back would need an exact
 * per-row undo the stores do not offer for an external caller, and a broader cleanup could remove
 * a phone owner's own learning; so run these on a test install or a test phone, never on a
 * personal one (final Fable audit M4, 2026-11-28: this KDoc used to claim "no trace").
 * TODO(test-gap M4): an exact undo of the rows a test created (read before, compare after) would
 *  make the claim true; needs a debug-only rollback seam on ContextModel and UserVocabulary.
 *
 * Learned counts are compared with the count read before the test ([bigram] baseline, read once
 * the keyboard service exists), so a phone that already learned a pair does not fail; on a fresh
 * test install every baseline is 0.
 *
 * No fixed sleeps: each key waits for the editor to show it and for the keyboard's cursor sync
 * that its selection update scheduled to have run ([awaitCursorSync]).
 */
internal class RealServiceLearning {

    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext

    private var originalIme = ""
    private val originalPrefs = mutableMapOf<String, Boolean?>()

    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }

    /** Runs [block] on the main thread and returns its value. Never call it from the main thread. */
    fun <T> onMain(block: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(block) }
        return value!!.getOrThrow()
    }

    /**
     * Polls [condition] from the test thread (each reader below hops to the main thread itself);
     * false at the deadline.
     */
    fun within(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50L)
        }
        return false
    }

    fun waitFor(what: String, timeoutMs: Long = 15_000L, condition: () -> Boolean) {
        if (!within(timeoutMs, condition)) throw AssertionError("timed out waiting for $what (${state()})")
    }

    fun service(): CleverKeysService? = CleverKeysService.getInstance()

    fun shown(): Boolean = onMain { service()?.isInputViewShown == true }

    fun orientation(): Int = onMain {
        service()?.resources?.configuration?.orientation ?: Configuration.ORIENTATION_UNDEFINED
    }

    /**
     * The text before the caret in the field the keyboard is bound to. Read from the test thread:
     * an InputConnection call waits for the app, which (a WebView) may need the main thread.
     */
    fun editorText(): String {
        val connection = onMain { service()?.currentInputConnection } ?: return "<no connection>"
        return connection.getTextBeforeCursor(1_000, 0)?.toString() ?: "<unreadable>"
    }

    fun state(): String = "shown=${shown()} orientation=${orientation()} text='${editorText()}'"

    fun setUp() {
        originalIme = shell("settings get secure default_input_method")
        val component = "${context.packageName}/tribixbite.cleverkeys.CleverKeysService"
        shell("ime enable $component")
        shell("ime set $component")
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        val edit = prefs.edit()
        for (key in LEARNING_KEYS) {
            originalPrefs[key] = if (prefs.contains(key)) prefs.getBoolean(key, false) else null
            edit.putBoolean(key, true)
        }
        edit.commit()
    }

    fun tearDown() {
        val edit = DirectBootAwarePreferences.get_shared_preferences(context).edit()
        for ((key, value) in originalPrefs) if (value == null) edit.remove(key) else edit.putBoolean(key, value)
        edit.commit()
        if (originalIme.isNotBlank() && originalIme != "null") shell("ime set $originalIme")
    }

    private fun keyEvents(): KeyEventHandler =
        CleverKeysService::class.java.getDeclaredField("_keyeventhandler").apply { isAccessible = true }
            .get(service() ?: throw AssertionError("no keyboard service")) as KeyEventHandler

    private fun field(owner: Any, cls: Class<*>, name: String): Any? =
        cls.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    /**
     * The keyboard's debounced cursor sync (InputCoordinator.onCursorMoved): the runnable last
     * scheduled, and whether it is still queued. Below API 29 the queue cannot be asked
     * (`Handler.hasCallbacks`), so a scheduled runnable reads as queued until [awaitCursorSync]'s
     * debounce deadline.
     */
    private fun cursorSync(): Pair<Runnable?, Boolean?> = onMain {
        val service = service() ?: throw AssertionError("no keyboard service")
        val coordinator = field(service, CleverKeysService::class.java, "_inputCoordinator") as InputCoordinator
        val runnable = field(coordinator, InputCoordinator::class.java, "pendingSyncRunnable") as Runnable?
        val handler = field(coordinator, InputCoordinator::class.java, "syncHandler") as Handler
        runnable to (if (Build.VERSION.SDK_INT >= 29) runnable != null && handler.hasCallbacks(runnable) else null)
    }

    /**
     * Waits until the cursor sync scheduled after [before] has run: a new runnable (the key's
     * selection update reached the service) that is no longer queued. A key whose update the
     * keyboard owns schedules none (a receipt-validated commit), so after [SYNC_WAIT_MS] without
     * a new one this returns; the editor already showed the key, which the caller checked.
     */
    private fun awaitCursorSync(before: Runnable?) {
        var scheduledAt = -1L
        within(SYNC_WAIT_MS) {
            val (runnable, queued) = cursorSync()
            if (runnable == null || runnable === before) return@within false
            if (scheduledAt < 0) scheduledAt = SystemClock.uptimeMillis()
            queued?.not() ?: (SystemClock.uptimeMillis() - scheduledAt > SYNC_DEBOUNCE_MS)
        }
    }

    /**
     * One key through the service's KeyEventHandler; returns once the editor [shows] it and the
     * keyboard's cursor sync for it has run, as typing at a few keys per second lets it.
     */
    private fun key(value: KeyValue, what: String, shows: (String) -> Boolean) {
        val before = cursorSync().first
        onMain { keyEvents().key_up(value, Pointers.Modifiers.EMPTY) }
        waitFor(what, KEY_WAIT_MS) { shows(editorText()) }
        awaitCursorSync(before)
    }

    /**
     * Types [text] key by key (letters, digits, space). The editor must show each key (letter case
     * aside: auto-capitalisation may raise a first letter).
     */
    fun type(text: String) {
        val base = editorText()
        for (i in text.indices) {
            val c = text[i]
            val expected = base + text.substring(0, i + 1)
            key(if (c == ' ') KeyValue.getKeyByName("space") else KeyValue.makeCharKey(c), "'$c' of '$text' typed") {
                it.equals(expected, ignoreCase = true)
            }
        }
    }

    /** The keyboard's Backspace, [count] times; each must delete something. */
    fun backspace(count: Int) = repeat(count) { n ->
        val before = editorText()
        key(KeyValue.getKeyByName("backspace"), "Backspace ${n + 1} of $count") { it.length < before.length }
    }

    /** The live predictor's bigram store (private fields, read by reflection: debug build). */
    private fun bigramStore(): BigramStore {
        // Read only once the service exists (M4: a baseline read before the keyboard was shown
        // reflected on a null service).
        service() ?: throw AssertionError("bigram read before the keyboard service exists")
        val coordinator = CleverKeysService::class.java.getDeclaredField("_predictionCoordinator")
            .apply { isAccessible = true }.get(service()) as PredictionCoordinator?
        val predictor = coordinator?.getWordPredictor() ?: throw AssertionError("no word predictor")
        val model = WordPredictor::class.java.getDeclaredField("contextModel").apply { isAccessible = true }
            .get(predictor) as ContextModel? ?: throw AssertionError("no context model (learning off?)")
        return ContextModel::class.java.getDeclaredField("bigramStore").apply { isAccessible = true }
            .get(model) as BigramStore
    }

    /** Learned frequency of (w1 → w2) in the primary language, any letter case; 0 when absent. */
    fun bigram(w1: String, w2: String, language: String = primaryLanguage()): Int {
        return onMain {
            val store = bigramStore()
            listOf(w1, w1.replaceFirstChar { it.uppercase() }).distinct()
                .flatMap { store.getAllBigrams(language, it) }
                .filter { it.word2.equals(w2, ignoreCase = true) }
                .sumOf { it.frequency }
        }
    }

    fun primaryLanguage(): String = Config.globalConfig().primary_language

    companion object {
        /** The learning master switch and the two stores the bigram learn needs. */
        val LEARNING_KEYS = listOf("on_device_learning_enabled", "context_aware_predictions_enabled", "word_prediction_enabled")
        /** InputCoordinator.CURSOR_SYNC_DEBOUNCE_MS (100 ms) with a margin, for API < 29 only. */
        const val SYNC_DEBOUNCE_MS = 150L

        /** How long a key's cursor sync may take to be scheduled before the key counts as owned. */
        const val SYNC_WAIT_MS = 1_000L

        /** How long the editor may take to show one key. */
        const val KEY_WAIT_MS = 5_000L
    }
}
