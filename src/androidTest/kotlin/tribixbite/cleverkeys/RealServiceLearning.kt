package tribixbite.cleverkeys

import android.content.res.Configuration
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
 * Leaves no trace: [setUp] makes CleverKeys the keyboard and turns learning on, [tearDown] puts
 * the keyboard and the three learning preferences back. Learned counts are compared with the
 * count read before the test ([bigram] baseline), so a phone that already learned a pair does
 * not fail; on a fresh test install every baseline is 0.
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

    /** One key through the service's KeyEventHandler, then a pause for the editor's update. */
    private fun key(value: KeyValue) {
        onMain { keyEvents().key_up(value, Pointers.Modifiers.EMPTY) }
        // Longer than the 100 ms cursor-sync debounce: each key's selection update and sync land
        // before the next key, as typing at a few keys per second does.
        Thread.sleep(KEY_GAP_MS)
    }

    /** Types [text] key by key (letters, digits, space). */
    fun type(text: String) {
        for (c in text) key(if (c == ' ') KeyValue.getKeyByName("space") else KeyValue.makeCharKey(c))
    }

    /** The keyboard's Backspace, [count] times. */
    fun backspace(count: Int) = repeat(count) { key(KeyValue.getKeyByName("backspace")) }

    /** The live predictor's bigram store (private fields, read by reflection: debug build). */
    private fun bigramStore(): BigramStore {
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
        const val KEY_GAP_MS = 180L
    }
}
