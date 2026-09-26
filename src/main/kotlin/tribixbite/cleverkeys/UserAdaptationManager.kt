package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import tribixbite.cleverkeys.persist.DebouncedPersister
import java.util.concurrent.ScheduledExecutorService

/**
 * Manages user adaptation by tracking word selection history and adjusting
 * word frequencies based on user preferences.
 *
 * Thin Android wrapper (M7, review 2026-08-06) around the pure-JVM
 * [SelectionHistory] core, which owns the counting, multiplier math, bounded
 * pruning, and concurrency contracts (unit-tested in `SelectionHistoryTest`).
 * This class owns only the SharedPreferences persistence and the periodic
 * decay schedule.
 *
 * Persistence (W6, learning-system audit 2026-09-26): debounced write-back via
 * [DebouncedPersister], the same substrate as the n-gram and vocabulary stores —
 * every selection marks the store dirty, a write lands ~5 s later (30 s cap under
 * continuous use), and `PredictionCoordinator.flushLearnedData` checkpoints it at
 * every input-session boundary. (It used to save on every 10th selection only.)
 *
 * Aging (W4): counts halve every [SelectionHistory.DECAY_HALF_LIFE_MS], anchored on
 * the persisted `last_decay` instant. This REPLACES a 30-day wholesale wipe that armed
 * itself the first time `last_reset` was written — which the v4 upgrade migration does
 * for every upgrader. Explicit wipes remain: [resetAdaptation] (user reset) and the
 * one-time v4 migration reset via [consumePendingReset].
 *
 * Retention contract: [SelectionHistory.snapshotForPersist] reports the words
 * pruned since the last save; [saveSelectionHistory] DELETES their
 * `word_selections_<word>` keys so pruned selections no longer persist forever
 * and resurrect on the next load.
 *
 * Privacy: writes are gated by `LearningGate.canLearnAdaptation` at the call
 * site (`SuggestionHandler.onSuggestionSelected`); reads are gated by
 * `LearningGate.canUseAdaptation` in `WordPredictor` AND belt-and-braces via
 * [setEnabled], which `WordPredictor.setConfig` keeps synced to the master
 * `on_device_learning_enabled` gate (H3).
 */
class UserAdaptationManager internal constructor(
    private val prefs: SharedPreferences,
    mainPrefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    scheduler: ScheduledExecutorService = DebouncedPersister.sharedScheduler(),
    debounceMs: Long = DebouncedPersister.DEFAULT_DEBOUNCE_MS,
    maxDelayMs: Long = DebouncedPersister.DEFAULT_MAX_DELAY_MS
) {
    /**
     * Production constructor. The pure-arguments constructor above exists so the
     * persistence and reset contracts are testable over in-memory preferences
     * (`UserAdaptationManagerPersistenceTest`) without a Context or android.util.Log.
     */
    private constructor(context: Context) : this(
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        mainPrefs = DirectBootAwarePreferences.get_shared_preferences(context),
        log = { msg -> Log.d(TAG, msg) }
    )

    private val history = SelectionHistory(
        maxTrackedWords = MAX_TRACKED_WORDS,
        minSelectionsForAdaptation = MIN_SELECTIONS_FOR_ADAPTATION,
        adaptationStrength = ADAPTATION_STRENGTH
    )

    /** W6: debounced write-back of [history] (see the class doc). */
    private val persister = DebouncedPersister(debounceMs, maxDelayMs, scheduler) {
        saveSelectionHistory()
    }

    /**
     * Earliest instant at which another decay period can be due (cheap hot-path check).
     * Declared before `init` so the value [applyDueDecay] sets there is not overwritten.
     */
    @Volatile
    private var nextDecayAtMs: Long = Long.MIN_VALUE

    /**
     * The decay anchor the in-RAM counts correspond to, written by [saveSelectionHistory] in
     * the SAME edit as the counts so storage never pairs halved counts with the old anchor
     * (which would halve them again at the next start). Null until the first decay check.
     */
    @Volatile
    private var decayAnchorMs: Long? = null

    init {
        loadSelectionHistory()
        // v4 learning-consent migration (2026-09-24): selection history is the one
        // store whose pre-2.0 writes ignored the learning gate, so an upgrade wipes
        // it once. Config.migrate stamps the flag (upgrades only); this consumes it.
        if (consumePendingReset(mainPrefs) { resetAdaptation() }) {
            log("Selection history reset once on upgrade (v4 learning-consent migration)")
        }
        applyDueDecay()
    }

    /**
     * Record that a word was selected by the user. No-op while disabled. Marks the
     * debounced write-back dirty (W6); a decay period that elapsed while the process
     * stayed alive is applied first.
     */
    fun recordSelection(word: String?) {
        if (clock() >= nextDecayAtMs) applyDueDecay()
        if (history.recordSelection(word)) {
            persister.markDirty()
        }
    }

    /** Synchronously persist unflushed selections (idempotent; no-op when clean). */
    fun flush() = persister.flush()

    /** Persist unflushed selections on the persistence thread (main-thread lifecycle sites). */
    fun requestFlush() = persister.requestFlush()

    /**
     * Get the adaptation multiplier for a word based on selection history.
     * Returns 1.0 for no adaptation (including while disabled — H3 read gate),
     * >1.0 for frequently selected words.
     */
    fun getAdaptationMultiplier(word: String?): Float = history.multiplierFor(word)

    /** Get selection count for a specific word. */
    fun getSelectionCount(word: String?): Int = history.selectionCount(word)

    /** Get total number of selections recorded. */
    fun getTotalSelections(): Int = history.totalSelections()

    /** Get number of unique words being tracked. */
    fun getTrackedWordCount(): Int = history.trackedWordCount()

    /**
     * Enable or disable user adaptation. Disabled ⇒ recording AND the
     * multiplier read are inert (synced from the master learning gate by
     * `WordPredictor.setConfig`).
     */
    fun setEnabled(enabled: Boolean) {
        if (history.enabled != enabled) {
            history.enabled = enabled
            log("User adaptation ${if (enabled) "enabled" else "disabled"}")
        }
    }

    /** Check if user adaptation is enabled. */
    fun isEnabled(): Boolean = history.enabled

    /** Reset all adaptation data (in RAM and persisted). */
    fun resetAdaptation() {
        val now = clock()
        // Before the flush below: any save from here on carries the reset's anchor.
        decayAnchorMs = now
        history.reset()
        // Settle any pending write-back BEFORE clearing, so no in-flight flush can land
        // after the clear (the history is already empty, so this writes only a zero total).
        persister.flush()

        prefs.edit().apply {
            clear()
            putLong(KEY_LAST_RESET, now)
            putLong(KEY_LAST_DECAY, now)
            apply()
        }
        nextDecayAtMs = now + SelectionHistory.DECAY_HALF_LIFE_MS

        log("User adaptation data reset")
    }

    /** Get adaptation statistics for debugging. */
    fun getAdaptationStats(): String {
        if (!history.enabled) {
            return "User adaptation disabled"
        }

        val total = history.totalSelections()
        val stats = StringBuilder()
        stats.append("User Adaptation Stats:\n")
        stats.append("- Total selections: $total\n")
        stats.append("- Unique words tracked: ${history.trackedWordCount()}\n")
        stats.append("- Adaptation active: ${if (total >= MIN_SELECTIONS_FOR_ADAPTATION) "Yes" else "No"}\n")

        if (total >= MIN_SELECTIONS_FOR_ADAPTATION) {
            stats.append("\nTop 10 most selected words:\n")
            history.topWords(10).forEach { (word, count) ->
                val multiplier = history.multiplierFor(word)
                stats.append("- $word: $count selections (${"%.2f".format(multiplier)}x boost)\n")
            }
        }

        return stats.toString()
    }

    /** Load selection history from persistent storage. */
    private fun loadSelectionHistory() {
        val total = prefs.getInt(KEY_TOTAL_SELECTIONS, 0)

        val counts = mutableMapOf<String, Int>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_WORD_SELECTIONS) && value is Int) {
                counts[key.substring(KEY_WORD_SELECTIONS.length)] = value
            }
        }
        history.load(counts, total)

        log("Loaded adaptation data: $total total selections, ${counts.size} unique words")
    }

    /**
     * Save selection history to persistent storage: prune-removals are DELETED
     * first (bounded retention, M7), then current counts are written.
     */
    private fun saveSelectionHistory() {
        val snapshot = history.snapshotForPersist()
        prefs.edit().apply {
            putInt(KEY_TOTAL_SELECTIONS, snapshot.total)

            // Remove pruned words' keys BEFORE the writes so a word re-selected
            // mid-snapshot (present in both sets) ends up written, not deleted.
            for (word in snapshot.removals) {
                remove(KEY_WORD_SELECTIONS + word)
            }
            for ((word, count) in snapshot.counts) {
                putInt(KEY_WORD_SELECTIONS + word, count)
            }
            // Read AFTER the snapshot: a decay landing in between leaves a pre-decay snapshot
            // with the new anchor (one halving skipped — benign), never halved counts with the
            // old anchor (a double halving). The decay's own flush follows immediately anyway.
            decayAnchorMs?.let { putLong(KEY_LAST_DECAY, it) }

            apply()
        }

        log("Saved adaptation data (${snapshot.counts.size} words, ${snapshot.removals.size} pruned keys removed)")
    }

    /**
     * W4: apply every whole decay period elapsed since the `last_decay` anchor, then
     * advance the anchor by exactly those periods and persist both at once — in one edit,
     * serialized with the debounced write-back (see [decayAnchorMs]).
     *
     * Anchor migration: stores written before this code have no `last_decay`. They fall
     * back to `last_reset` (written by every reset, including the v4 upgrade reset), and a
     * store with neither is anchored at "now" WITHOUT decaying — nothing says how old its
     * counts are, and wiping or halving on first sight would repeat the W4 mistake.
     */
    @Synchronized
    private fun applyDueDecay() {
        val now = clock()
        val anchor = when {
            prefs.contains(KEY_LAST_DECAY) -> prefs.getLong(KEY_LAST_DECAY, now)
            prefs.contains(KEY_LAST_RESET) -> prefs.getLong(KEY_LAST_RESET, now)
            else -> now
        }
        val halvings = SelectionHistory.decayHalvingsDue(anchor, now)
        val newAnchor = if (now < anchor) now else anchor + halvings * SelectionHistory.DECAY_HALF_LIFE_MS
        nextDecayAtMs = newAnchor + SelectionHistory.DECAY_HALF_LIFE_MS
        decayAnchorMs = newAnchor

        if (history.decay(halvings)) {
            log("Decayed selection history by $halvings half-life period(s)")
            // Decayed counts + pruned-to-zero key removals + the new anchor, in one edit, through
            // the persister (review of d8846a98): a direct save here raced a write-back already in
            // flight, whose pre-decay snapshot could land last and resurrect the dropped words.
            // flush() waits for that write-back to finish, then writes the decayed state.
            persister.markDirty()
            persister.flush()
        } else if (!prefs.contains(KEY_LAST_DECAY) || prefs.getLong(KEY_LAST_DECAY, 0L) != newAnchor) {
            // Counts unchanged: only the anchor moves (first sight of a store, or clock set back).
            prefs.edit().putLong(KEY_LAST_DECAY, newAnchor).apply()
        }
    }

    /** Synchronous checkpoint for teardown paths (alias of [flush], kept for callers). */
    fun cleanup() {
        flush()
    }

    companion object {
        private const val TAG = "UserAdaptationManager"
        private const val PREFS_NAME = "user_adaptation"
        private const val KEY_WORD_SELECTIONS = "word_selections_"
        private const val KEY_TOTAL_SELECTIONS = "total_selections"
        private const val KEY_LAST_RESET = "last_reset"
        private const val KEY_LAST_DECAY = "last_decay"

        // Configuration constants (thresholds live in SelectionHistory defaults)
        private const val MIN_SELECTIONS_FOR_ADAPTATION =
            SelectionHistory.DEFAULT_MIN_SELECTIONS_FOR_ADAPTATION
        private const val MAX_TRACKED_WORDS = SelectionHistory.DEFAULT_MAX_TRACKED_WORDS
        private const val ADAPTATION_STRENGTH = SelectionHistory.DEFAULT_ADAPTATION_STRENGTH

        /**
         * Consume [LearningMigration.SELECTION_HISTORY_RESET_PENDING_KEY] from the MAIN
         * prefs file: when present, run [onReset] exactly once and remove the flag.
         * Returns whether a reset ran. Separated from the constructor so the contract
         * is testable without the singleton (see LearningMigrationTest).
         */
        @JvmStatic
        fun consumePendingReset(mainPrefs: SharedPreferences, onReset: () -> Unit): Boolean {
            if (!mainPrefs.getBoolean(LearningMigration.SELECTION_HISTORY_RESET_PENDING_KEY, false)) {
                return false
            }
            onReset()
            mainPrefs.edit()
                .remove(LearningMigration.SELECTION_HISTORY_RESET_PENDING_KEY)
                .apply()
            return true
        }

        @Volatile
        private var instance: UserAdaptationManager? = null

        @JvmStatic
        fun getInstance(context: Context): UserAdaptationManager {
            return instance ?: synchronized(this) {
                instance ?: UserAdaptationManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
