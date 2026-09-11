package tribixbite.cleverkeys.pinyin

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tribixbite.cleverkeys.BuildConfig
import tribixbite.cleverkeys.SuggestionBar
import tribixbite.cleverkeys.SuggestionMeta
import tribixbite.cleverkeys.SuggestionOrigin
import tribixbite.cleverkeys.langpack.LanguagePackManager

/**
 * IME glue for the pinyin composing mode (gh #177): decides when the active language is a
 * pinyin pack, owns the [PinyinSession], drives the editor composing region, and feeds the
 * suggestion bar. The pure session logic lives in [PinyinSession]; this class is the only
 * part that touches Android.
 *
 * ## When the mode is active
 *
 * The active primary language's INSTALLED pack declares `"inputMethod": "pinyin"` and
 * ships a parseable `phrases.bin`. [onStartInputView] evaluates this per field; the
 * language is re-checked on the next key when it changes mid-field. A missing/corrupt
 * table, a password/PIN field, or a non-pinyin pack leaves the controller inactive and the
 * keyboard behaves exactly as before (Latin letters commit directly) — the
 * fallback-to-Latin rule in `docs/specs/pinyin-ime.md` FR-11.
 *
 * ## Composing region vs committed fallback (spec open question Q1)
 *
 * The primary path publishes the preedit with [InputConnection.setComposingText], so
 * editors underline the pinyin and one `commitText` replaces it with the chosen 汉字. If
 * the editor refuses the first `setComposingText` (returns false), the controller flips to
 * a committed-text fallback for that field: the same buffer is mirrored as real text, and
 * updates/deletes use `commitText`/`deleteSurroundingText`, the pattern the English replace
 * path already uses. Both paths are exercised by tests; the switch is per field.
 *
 * ## Privacy
 *
 * Never active in password/PIN fields (the caller passes the classification from
 * `SuggestionBar.isPasswordField`). Nothing is learned: selections do not feed the n-gram
 * model or `PredictionContextTracker`, matching the spec's Phase-1 isolation rule.
 */
class PinyinController(
    private val languageProvider: () -> String?,
    private val inputConnectionProvider: () -> InputConnection?,
    private val suggestionBarProvider: () -> SuggestionBar?,
    private val packManagerProvider: () -> LanguagePackManager?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) : PinyinComposingHook {

    companion object {
        private const val TAG = "PinyinController"

        /**
         * Production factory: main-thread mutations, IO table load. The process-lifetime
         * scope is intentional — a field change must not cancel an in-flight parse, and the
         * service owns the objects for its own lifetime.
         */
        fun create(
            context: Context,
            languageProvider: () -> String?,
            inputConnectionProvider: () -> InputConnection?,
            suggestionBarProvider: () -> SuggestionBar?,
        ): PinyinController = PinyinController(
            languageProvider = languageProvider,
            inputConnectionProvider = inputConnectionProvider,
            suggestionBarProvider = suggestionBarProvider,
            packManagerProvider = { LanguagePackManager.getInstance(context) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            ioDispatcher = Dispatchers.IO,
        )
    }

    /** Language whose mode was last evaluated (null before the first evaluation). */
    private var modeLanguage: String? = null

    /** Whether the last evaluation wanted pinyin mode (before load success). */
    private var modeWanted = false

    /** Whether a table is loaded AND the mode is currently wanted. */
    private var active = false

    /** Last field's password classification, set by [onStartInputView]. */
    private var passwordField = false

    /** Whether the preedit is published through the composing region for this field. */
    private var composingMode = true

    private var loadedLanguage: String? = null
    private var table: CkpyPhraseTable.Table? = null
    private var session: PinyinSession? = null
    private var loadJob: Job? = null

    /** True while the composing session owns key input in the focused field. */
    fun isActive(): Boolean = active

    /**
     * Field-start hook. Resets the session, re-evaluates the language's pack, and keeps
     * the composing region for the new field.
     */
    fun onStartInputView(isPasswordField: Boolean) {
        passwordField = isPasswordField
        composingMode = true
        resetSession(clearBar = true)
        evaluateMode(force = true)
    }

    /**
     * Field-end hook: cancel any composing run best-effort (the connection may already be
     * gone), clear the bar, and deactivate until the next field.
     */
    fun onFinishInputView() {
        val s = session
        val conn = inputConnectionProvider()
        if (s != null && !s.isEmpty && conn != null) {
            runCatching {
                if (composingMode) conn.setComposingText("", 1) else replaceCommittedPinyin(conn, s)
            }
        }
        active = false
        resetSession(clearBar = true)
    }

    /**
     * A letter/space/other character was typed. Returns true when the composing session
     * consumed it; false lets `KeyEventHandler.sendText` run the normal path.
     */
    fun handleTypedText(text: String): Boolean {
        syncMode()
        if (!isActive()) return false
        val s = session ?: return false
        val conn = inputConnectionProvider() ?: return false

        if (text == " ") {
            if (s.isEmpty) return false
            commitTop(conn, s)
            return true
        }

        val normalized = if (text.length == 1) PinyinSession.normalizeLetter(text[0]) else null
        if (normalized != null) {
            preeditMatchesEditor(conn, s)
            if (!s.appendLetter(text[0])) return false
            updatePreedit(conn, s, appended = normalized.toString())
            updateCandidates()
            return true
        }

        // Punctuation, digits, a macro string… End the composing run and keep whatever the
        // user typed as plain text, then let the normal path commit this character.
        flushRaw(conn, s)
        return false
    }

    /**
     * Backspace: edit the buffer while one exists. Returns false on an empty buffer so the
     * normal backspace path (deleting committed text) runs.
     */
    fun handleBackspace(): Boolean {
        syncMode()
        if (!isActive()) return false
        val s = session ?: return false
        if (s.isEmpty) return false
        val conn = inputConnectionProvider() ?: return false

        preeditMatchesEditor(conn, s)
        if (s.isEmpty) return false

        val removed = s.composingText.last()
        s.backspace()
        if (composingMode) {
            if (!conn.setComposingText(s.composingText, 1)) composingMode = false
        }
        if (!composingMode) {
            val before = conn.getTextBeforeCursor(1, 0)?.toString()
            if (before == removed.toString()) conn.deleteSurroundingText(1, 0)
        }
        if (s.isEmpty) clearCandidates() else updateCandidates()
        return true
    }

    /**
     * Enter commits the top candidate and returns false so the editor action still fires;
     * Escape cancels the composing run and is consumed. Other key events are left alone
     * (the next typed key verifies whether the composing run is still aligned).
     */
    fun handleKeyevent(keyCode: Int): Boolean {
        syncMode()
        if (!isActive()) return false
        val s = session ?: return false
        if (s.isEmpty) return false
        val conn = inputConnectionProvider() ?: return false
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                commitTop(conn, s)
                false
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                cancel(conn, s)
                true
            }
            else -> false
        }
    }

    /**
     * A candidate tap from the suggestion bar. Only words the session itself offered are
     * consumed, so a stale English suggestion can never be swallowed as 汉字.
     */
    fun onCandidateSelected(word: String): Boolean {
        syncMode()
        if (!isActive()) return false
        val s = session ?: return false
        if (s.isEmpty) return false
        if (s.candidates().none { it.text == word }) return false
        val conn = inputConnectionProvider() ?: return false
        commitText(conn, s, word)
        return true
    }

    /** Swipe decoded to a pinyin spelling: buffer it, show candidates, never auto-commit. */
    override fun onSwipeDecoded(predictions: List<String>, scores: List<Int>): Boolean {
        syncMode()
        if (!isActive()) return false
        val s = session ?: return true
        val conn = inputConnectionProvider() ?: return true

        preeditMatchesEditor(conn, s)
        val appended = s.appendSurface(predictions.firstOrNull().orEmpty())
        if (appended.isNotEmpty()) {
            if (composingMode) {
                if (!conn.setComposingText(s.composingText, 1) && s.length == appended.length) {
                    composingMode = false
                    conn.commitText(s.composingText, 1)
                }
            } else {
                conn.commitText(appended, 1)
            }
        }
        updateCandidates()
        return true
    }

    // -------------------------------------------------------------------- mode + loading

    /** Re-evaluate the mode only when the active language changed since the last check. */
    private fun syncMode() {
        if (languageProvider() != modeLanguage) evaluateMode(force = false)
    }

    private fun evaluateMode(force: Boolean) {
        val language = languageProvider()
        if (!force && language == modeLanguage) return
        modeLanguage = language

        val manifest = language?.let { packManagerProvider()?.getInstalledPack(it) }
        modeWanted = !passwordField &&
            manifest?.inputMethod == LanguagePackManager.INPUT_METHOD_PINYIN

        if (!modeWanted) {
            loadJob?.cancel()
            table = null
            loadedLanguage = null
            session = null
            active = false
            return
        }

        val loaded = table
        if (loaded != null && loadedLanguage == language) {
            if (session == null) session = PinyinSession(loaded)
            active = true
            return
        }

        active = false
        loadAsync(language!!)
    }

    private fun loadAsync(language: String) {
        loadJob?.cancel()
        loadJob = scope.launch {
            val loaded = withContext(ioDispatcher) {
                val file = packManagerProvider()?.getPhrasesPath(language)
                    ?: return@withContext null
                runCatching { CkpyPhraseTable.read(file) }.getOrNull()
            }
            if (loaded == null) {
                // Pack declared pinyin but the table is unusable: stay inactive, commit Latin.
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    Log.w(TAG, "phrases.bin for \"$language\" could not be loaded; pinyin mode off")
                }
                return@launch
            }
            if (languageProvider() != language) return@launch
            table = loaded
            loadedLanguage = language
            session = PinyinSession(loaded)
            active = modeWanted
        }
    }

    // ----------------------------------------------------------------- preedit + candidates

    /**
     * Verify that the editor still shows exactly our composing run (or that we are in the
     * committed fallback, which is verified at its deletion sites). On a mismatch the run
     * is abandoned and the caller starts cleanly — the repo's "verify at use" pattern.
     */
    private fun preeditMatchesEditor(conn: InputConnection, s: PinyinSession): Boolean {
        if (s.isEmpty || !composingMode) return true
        val before = conn.getTextBeforeCursor(s.length, 0)?.toString()
        if (before == s.composingText) return true
        s.clear()
        clearCandidates()
        return false
    }

    private fun updatePreedit(conn: InputConnection, s: PinyinSession, appended: String) {
        if (composingMode) {
            if (!conn.setComposingText(s.composingText, 1)) {
                // The editor refused the composing region. Mirror the fresh run as real
                // text; later updates commit their characters and deletions remove them.
                if (s.length == appended.length) {
                    composingMode = false
                    conn.commitText(s.composingText, 1)
                }
            }
        } else {
            conn.commitText(appended, 1)
        }
    }

    private fun commitTop(conn: InputConnection, s: PinyinSession) {
        commitText(conn, s, s.topCandidate() ?: s.composingText)
    }

    private fun commitText(conn: InputConnection, s: PinyinSession, text: String) {
        if (text.isEmpty()) return
        if (!composingMode) replaceCommittedPinyin(conn, s)
        conn.commitText(text, 1)
        s.clear()
        clearCandidates()
    }

    /** Keep the typed pinyin as plain text and end the composing run. */
    private fun flushRaw(conn: InputConnection, s: PinyinSession) {
        if (s.isEmpty) return
        if (composingMode) conn.finishComposingText()
        s.clear()
        clearCandidates()
    }

    /** Drop the composing run without committing the candidate. */
    private fun cancel(conn: InputConnection, s: PinyinSession) {
        if (s.isEmpty) return
        if (composingMode) conn.setComposingText("", 1) else replaceCommittedPinyin(conn, s)
        s.clear()
        clearCandidates()
    }

    /** In the fallback path the preedit is real text: delete it before replacing. */
    private fun replaceCommittedPinyin(conn: InputConnection, s: PinyinSession) {
        val text = s.composingText
        if (text.isEmpty()) return
        if (conn.getTextBeforeCursor(text.length, 0)?.toString() == text) {
            conn.deleteSurroundingText(text.length, 0)
        }
    }

    private fun resetSession(clearBar: Boolean) {
        session?.clear()
        if (clearBar) clearCandidates()
    }

    private fun updateCandidates() {
        val s = session ?: return
        if (s.isEmpty) {
            clearCandidates()
            return
        }
        val candidates = s.candidates()
        if (candidates.isEmpty()) {
            clearCandidates()
            return
        }
        val bar = suggestionBarProvider() ?: return
        bar.setSuggestionsWithScores(
            candidates.map { it.text },
            candidates.map { it.score },
            candidates.map { SuggestionMeta(SuggestionOrigin.PINYIN) },
        )
    }

    private fun clearCandidates() {
        suggestionBarProvider()?.clearSuggestions()
    }
}
