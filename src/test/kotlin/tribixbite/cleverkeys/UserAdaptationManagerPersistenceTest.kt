package tribixbite.cleverkeys

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistence + retention contract of [UserAdaptationManager] (learning-system audit
 * 2026-09-26):
 *
 * - **W6** — selection history used to persist only on every 10th selection, and
 *   `cleanup()` had no callers, so up to 9 selections were lost at every process death
 *   (frequent under LMK on the maintainer's phone). It now uses the same debounced
 *   write-back as the other learned stores and is flushed at the input-session boundary.
 * - **W4** — a 30-day WHOLESALE wipe armed itself the first time `last_reset` was written,
 *   which the v4 upgrade migration now does for every upgrader. Learned preferences are now
 *   aged by halving instead (half-life 30 days); the explicit user reset and the one-time v4
 *   migration reset still wipe.
 *
 * Runs in `runMockTests` (android.jar stubs on the classpath for the SharedPreferences
 * interface); the preferences are a thread-safe in-memory fake because the debounced flush
 * runs on the persistence thread.
 */
class UserAdaptationManagerPersistenceTest {

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val T0 = 1_700_000_000_000L
    }

    /** Thread-safe in-memory SharedPreferences (apply == commit, removals really remove). */
    private class FakePrefs : SharedPreferences {
        val data = ConcurrentHashMap<String, Any>()

        override fun getAll(): Map<String, *> = HashMap(data)
        override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            data[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val puts = LinkedHashMap<String, Any>()
            private val removes = LinkedHashSet<String>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { if (value == null) removes += key else puts[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { if (values == null) removes += key else puts[key] = values }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { removes += key }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                synchronized(data) {
                    // Android semantics: clear() first, then removals, then puts.
                    if (clear) data.clear()
                    removes.forEach { data.remove(it) }
                    data.putAll(puts)
                }
                return true
            }
            override fun apply() { commit() }
        }
    }

    private fun waitUntil(timeoutMs: Long = 12_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    // ------------------------------------------------------------------ W6

    @Test
    fun `W6 - a few selections are persisted by the write-back without any lifecycle call`() {
        val prefs = FakePrefs()
        val manager = UserAdaptationManager(prefs, FakePrefs(), clock = { T0 })
        repeat(3) { manager.recordSelection("git") }

        // No flush/cleanup call: process death may come at any moment after this.
        val persisted = waitUntil { prefs.getInt("word_selections_git", 0) == 3 }
        assertThat(persisted).isTrue()

        val revived = UserAdaptationManager(prefs, FakePrefs(), clock = { T0 })
        assertThat(revived.getSelectionCount("git")).isEqualTo(3)
    }

    @Test
    fun `W6 - the input-session checkpoint flushes selection history too`() {
        val coordinator = File("src/main/kotlin/tribixbite/cleverkeys/PredictionCoordinator.kt").readText()
        val body = coordinator.substringAfter("fun flushLearnedData()").substringBefore("\n    }\n")
        assertThat(body).contains("adaptationManager?.requestFlush()")
    }

    // ------------------------------------------------------------------ W4

    /** A store whose `last_reset` was written at [T0] (any reset — e.g. the v4 migration). */
    private fun seededPrefs(counts: Map<String, Int>): FakePrefs = FakePrefs().apply {
        data["last_reset"] = T0
        data["total_selections"] = counts.values.sum()
        counts.forEach { (w, c) -> data["word_selections_$w"] = c }
    }

    @Test
    fun `W4 - learned selections survive 30 days, halved rather than wiped`() {
        val prefs = seededPrefs(mapOf("git" to 8, "got" to 2))
        val manager = UserAdaptationManager(prefs, FakePrefs(), clock = { T0 + 31 * DAY_MS })

        assertThat(manager.getSelectionCount("git")).isEqualTo(4)
        assertThat(manager.getSelectionCount("got")).isEqualTo(1)
    }

    @Test
    fun `W4 - decay compounds once per elapsed period and drops words that reach zero`() {
        val prefs = seededPrefs(mapOf("git" to 8, "got" to 2))
        val manager = UserAdaptationManager(prefs, FakePrefs(), clock = { T0 + 61 * DAY_MS })

        assertThat(manager.getSelectionCount("git")).isEqualTo(2)
        assertThat(manager.getSelectionCount("got")).isEqualTo(0)
        assertThat(manager.getTrackedWordCount()).isEqualTo(1)
    }

    @Test
    fun `W4 - decay is anchored, so a restart inside the period does not decay again`() {
        val prefs = seededPrefs(mapOf("git" to 8))
        UserAdaptationManager(prefs, FakePrefs(), clock = { T0 + 31 * DAY_MS }).cleanup()
        val later = UserAdaptationManager(prefs, FakePrefs(), clock = { T0 + 40 * DAY_MS })
        assertThat(later.getSelectionCount("git")).isEqualTo(4)
    }

    @Test
    fun `W4 - an explicit reset no longer arms a wipe 30 days later`() {
        val prefs = FakePrefs()
        var now = T0
        val manager = UserAdaptationManager(prefs, FakePrefs(), clock = { now })
        manager.resetAdaptation()
        repeat(6) { manager.recordSelection("git") }
        manager.cleanup()

        now = T0 + 31 * DAY_MS
        val revived = UserAdaptationManager(prefs, FakePrefs(), clock = { now })
        assertThat(revived.getSelectionCount("git")).isEqualTo(3)
    }

    @Test
    fun `W4 - a store with no anchor is never decayed on first sight`() {
        // Installs that never reset have no last_reset key; the first run anchors "now".
        val prefs = FakePrefs().apply {
            data["total_selections"] = 8
            data["word_selections_git"] = 8
        }
        val manager = UserAdaptationManager(prefs, FakePrefs(), clock = { T0 + 400 * DAY_MS })
        assertThat(manager.getSelectionCount("git")).isEqualTo(8)
    }

    // --------------------------------------------- resets that must still wipe

    @Test
    fun `explicit resetAdaptation still wipes RAM and persisted history`() {
        val prefs = seededPrefs(mapOf("git" to 8))
        val manager = UserAdaptationManager(prefs, FakePrefs(), clock = { T0 + DAY_MS })
        manager.resetAdaptation()
        manager.cleanup()

        assertThat(manager.getSelectionCount("git")).isEqualTo(0)
        assertThat(prefs.contains("word_selections_git")).isFalse()
        assertThat(UserAdaptationManager(prefs, FakePrefs(), clock = { T0 + DAY_MS }).getSelectionCount("git")).isEqualTo(0)
    }

    @Test
    fun `the v4 upgrade reset still wipes exactly once`() {
        val prefs = seededPrefs(mapOf("git" to 8))
        val main = FakePrefs().apply { data[LearningMigration.SELECTION_HISTORY_RESET_PENDING_KEY] = true }

        val first = UserAdaptationManager(prefs, main, clock = { T0 + DAY_MS })
        assertThat(first.getSelectionCount("git")).isEqualTo(0)
        assertThat(main.contains(LearningMigration.SELECTION_HISTORY_RESET_PENDING_KEY)).isFalse()

        repeat(2) { first.recordSelection("git") }
        first.cleanup()
        val second = UserAdaptationManager(prefs, main, clock = { T0 + 2 * DAY_MS })
        assertThat(second.getSelectionCount("git")).isEqualTo(2)
    }
}
