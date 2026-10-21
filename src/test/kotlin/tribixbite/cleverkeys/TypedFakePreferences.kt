package tribixbite.cleverkeys

import android.content.SharedPreferences

/**
 * In-memory [SharedPreferences] with Android's TYPE semantics: a typed getter on a value stored
 * as a different type throws [ClassCastException] (the real `SharedPreferencesImpl` does a plain
 * cast), and an absent key returns the caller's default. [MockSharedPreferences] instead answers
 * a mismatch with the default, so it cannot exercise the type-recovery readers
 * (`Config.safeGetInt` and friends) or a migration that meets a type-corrupted value.
 *
 * The editor follows Android too: nothing is visible until `apply()`/`commit()`, `clear()` runs
 * before the same editor's puts, and `putString(k, null)` removes `k`. Shared by the audit-L3/L4/
 * L5/L6/L7/L16 tests; pure Kotlin, so it runs in both the pure and the mock runner.
 */
class TypedFakePreferences(initial: Map<String, Any> = emptyMap()) : SharedPreferences {

    /** Current committed values; exposed so tests can assert exactly what a migration wrote. */
    val values: MutableMap<String, Any> = LinkedHashMap(initial)

    /** Number of `apply()`/`commit()` calls, for "wrote exactly once" assertions. */
    var commits: Int = 0
        private set

    /** Android's behaviour: a stored value of another type is a [ClassCastException]. */
    private inline fun <reified T> typed(key: String?, default: T): T {
        val stored = values[key] ?: return default
        return stored as? T
            ?: throw ClassCastException("${stored.javaClass.name} cannot be cast to ${T::class.java.name}")
    }

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)
    override fun getString(key: String?, defValue: String?): String? {
        val stored = values[key] ?: return defValue
        return stored as? String ?: throw ClassCastException("${stored.javaClass.name} cannot be cast to String")
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        val stored = values[key] ?: return defValues
        return (stored as? Set<String>)?.toMutableSet()
            ?: throw ClassCastException("${stored.javaClass.name} cannot be cast to Set")
    }

    override fun getInt(key: String?, defValue: Int): Int = typed(key, defValue)
    override fun getLong(key: String?, defValue: Long): Long = typed(key, defValue)
    override fun getFloat(key: String?, defValue: Float): Float = typed(key, defValue)
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = typed(key, defValue)
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        /** Key → new value; [REMOVED] deletes the key on commit. */
        private val pending = LinkedHashMap<String, Any>()
        private var clearRequested = false

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            if (key != null) pending[key] = value ?: REMOVED
            return this
        }

        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = put(key, null)

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean {
            if (clearRequested) values.clear()
            for ((key, value) in pending) {
                if (value === REMOVED) values.remove(key) else values[key] = value
            }
            pending.clear()
            clearRequested = false
            commits++
            return true
        }

        override fun apply() {
            commit()
        }
    }

    private companion object {
        /** Sentinel for a pending removal (null cannot be stored in the non-null map). */
        val REMOVED = Any()
    }
}
