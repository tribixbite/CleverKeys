package tribixbite.cleverkeys.prefs

import android.content.Context
import android.content.SharedPreferences
import android.preference.Preference
import android.preference.PreferenceGroup
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import org.json.JSONArray
import org.json.JSONException
import tribixbite.cleverkeys.Logs
import tribixbite.cleverkeys.R

/**
 * A dynamic list preference where users can add, modify, and remove items.
 * Backed by a string list stored in SharedPreferences using JSON serialization.
 *
 * Features:
 * - Add items to end of list
 * - Modify existing items
 * - Remove items with configurable constraints
 * - Custom item labels
 * - Customizable "Add" button
 * - JSON-based persistence
 * - Generic type support for any serializable type
 *
 * Usage:
 * 1. Extend this class with your item type E
 * 2. Implement abstract methods: label_of_value(), select(), get_serializer()
 * 3. Optionally override: on_attach_add_button(), should_allow_remove_item()
 *
 * Example:
 * ```kotlin
 * class MyListPreference(context: Context, attrs: AttributeSet) :
 *     ListGroupPreference<String>(context, attrs) {
 *
 *     override fun label_of_value(value: String, i: Int): String = value
 *     override fun get_serializer() = StringSerializer()
 *     override fun select(callback: SelectionCallback<String>, old_value: String?) {
 *         // Show dialog for user to select/create value
 *         callback.select("new value")
 *     }
 * }
 * ```
 *
 * Ported from Java to Kotlin with improvements.
 */
@Suppress("DEPRECATION")
abstract class ListGroupPreference<E>(
    context: Context,
    attrs: AttributeSet
) : PreferenceGroup(context, attrs) {

    private var attached = false
    private var values = mutableListOf<E>()

    /** The "add" button currently displayed */
    private var addButton: AddButton? = null

    init {
        setOrderingAsAdded(true)
        setLayoutResource(R.layout.pref_listgroup_group)
    }

    // ====== Abstract Methods (Must Override) ======

    /**
     * The label to display on the item for a given value
     *
     * @param value The item value
     * @param i The item index
     * @return Display label for this item
     */
    abstract fun label_of_value(value: E, i: Int): String

    /**
     * Called when an item is added or modified
     *
     * @param callback Callback to invoke with selected value
     * @param old_value The current value if modifying, null if adding new item
     */
    abstract fun select(callback: SelectionCallback<E>, old_value: E?)

    /**
     * Get the serializer for converting items to/from JSON
     *
     * @return Serializer instance (must be consistent across calls)
     */
    abstract fun get_serializer(): Serializer<E>

    // ====== Overrideable Methods ======

    /**
     * Called every time the list changes to update the "Add" button appearance
     *
     * @param prev_btn Previously attached button, might be null
     * @return Button to attach (can be same instance or new one)
     */
    open fun on_attach_add_button(prev_btn: AddButton?): AddButton {
        return prev_btn ?: AddButton(context)
    }

    /**
     * Called to determine if remove button should be shown for an item.
     * Can be used to enforce minimum number of items.
     *
     * @param value The item value
     * @return true if item can be removed
     */
    open fun should_allow_remove_item(value: E): Boolean {
        return true
    }

    // ====== Protected API ======

    /**
     * Set the values
     *
     * @param vs New values
     * @param persist If true, persist to SharedPreferences
     */
    protected fun set_values(vs: List<E>, persist: Boolean) {
        values = vs.toMutableList()
        reattach()
        if (persist) {
            persistString(save_to_string(vs, get_serializer()))
        }
    }

    /**
     * Add an item to the end of the list
     */
    protected fun add_item(v: E) {
        values.add(v)
        set_values(values, true)
    }

    /**
     * Change an existing item
     */
    protected fun change_item(i: Int, v: E) {
        values[i] = v
        set_values(values, true)
    }

    /**
     * Remove an item
     */
    protected fun remove_item(i: Int) {
        values.removeAt(i)
        set_values(values, true)
    }

    // ====== Internal Implementation ======

    override fun onSetInitialValue(restoreValue: Boolean, defaultValue: Any?) {
        val input = if (restoreValue) {
            getPersistedString(null)
        } else {
            defaultValue as? String
        }

        if (input != null) {
            val loadedValues = load_from_string(input, get_serializer())
            if (loadedValues != null) {
                set_values(loadedValues, false)
            }
        }
    }

    override fun onAttachedToActivity() {
        super.onAttachedToActivity()
        if (attached) return

        attached = true
        reattach()
    }

    /**
     * Rebuild the preference list from current values
     */
    private fun reattach() {
        if (!attached) return

        removeAll()

        // Add all items
        values.forEachIndexed { i, v ->
            addPreference(Item(context, i, v))
        }

        // Add "Add" button at end
        addButton = on_attach_add_button(addButton)
        addButton?.setOrder(Preference.DEFAULT_ORDER)
        addButton?.let { addPreference(it) }
    }

    // ====== Inner Classes ======

    /**
     * Preference item representing a single value in the list
     */
    inner class Item(
        ctx: Context,
        private val index: Int,
        private val value: E
    ) : Preference(ctx) {

        init {
            isPersistent = false
            title = label_of_value(value, index)

            if (should_allow_remove_item(value)) {
                widgetLayoutResource = R.layout.pref_listgroup_item_widget
            }
        }

        override fun onCreateView(parent: ViewGroup): View {
            val v = super.onCreateView(parent)

            // Setup remove button
            v.findViewById<View>(R.id.pref_listgroup_remove_btn)?.setOnClickListener {
                remove_item(index)
            }

            // Setup item click to modify
            v.setOnClickListener {
                select(object : SelectionCallback<E> {
                    override fun select(value: E?) {
                        if (value == null) {
                            remove_item(index)
                        } else {
                            change_item(index, value)
                        }
                    }

                    override fun allow_remove(): Boolean = true
                }, value)
            }

            return v
        }
    }

    /**
     * "Add" button shown at the end of the list
     */
    open inner class AddButton(ctx: Context) : Preference(ctx) {

        init {
            isPersistent = false
            layoutResource = R.layout.pref_listgroup_add_btn
        }

        override fun onClick() {
            select(object : SelectionCallback<E> {
                override fun select(value: E?) {
                    if (value != null) {
                        add_item(value)
                    }
                }

                override fun allow_remove(): Boolean = false
            }, null)
        }
    }

    // ====== Interfaces ======

    /**
     * Callback for item selection/modification
     */
    interface SelectionCallback<E> {
        /**
         * Called with selected value
         *
         * @param value New value, or null to remove (if allow_remove() returns true)
         */
        fun select(value: E?)

        /**
         * If this returns true, null might be passed to select() to remove the item
         */
        fun allow_remove(): Boolean
    }

    /**
     * Methods for serializing and deserializing items to/from JSON
     */
    interface Serializer<E> {
        /**
         * Deserialize an item from JSON object
         *
         * @param obj Object returned by save_item()
         * @return Deserialized item
         * @throws JSONException if deserialization fails
         */
        @Throws(JSONException::class)
        fun load_item(obj: Any): E

        /**
         * Serialize an item to JSON
         *
         * @param v Item to serialize
         * @return Object that can be inserted in JSONArray
         * @throws JSONException if serialization fails
         */
        @Throws(JSONException::class)
        fun save_item(v: E): Any
    }

    /**
     * Simple string serializer (pass-through)
     */
    class StringSerializer : Serializer<String> {
        override fun load_item(obj: Any): String = obj as String
        override fun save_item(v: String): Any = v
    }

    companion object {
        /**
         * Read a value saved by preference from SharedPreferences
         *
         * @param key Preference key
         * @param prefs SharedPreferences instance
         * @param def Default value if not found
         * @param serializer Serializer (must match get_serializer())
         * @return Loaded list or default
         */
        @JvmStatic
        fun <E> load_from_preferences(
            key: String,
            prefs: SharedPreferences,
            def: List<E>,
            serializer: Serializer<E>
        ): List<E> {
            val s = prefs.getString(key, null)
            return if (s != null) load_from_string(s, serializer) ?: def else def
        }

        /**
         * Save items to SharedPreferences
         *
         * Does not call commit() or apply()
         *
         * @param key Preference key
         * @param prefs Editor instance
         * @param items Items to save
         * @param serializer Serializer for converting items
         */
        @JvmStatic
        fun <E> save_to_preferences(
            key: String,
            prefs: SharedPreferences.Editor,
            items: List<E>,
            serializer: Serializer<E>
        ) {
            prefs.putString(key, save_to_string(items, serializer))
        }

        /**
         * Decode a list from JSON string
         *
         * @param inp JSON string encoded with save_to_string()
         * @param serializer Serializer for items
         * @return Decoded list or null on error
         */
        @JvmStatic
        fun <E> load_from_string(inp: String, serializer: Serializer<E>): List<E>? {
            return try {
                val list = mutableListOf<E>()
                val arr = JSONArray(inp)
                for (i in 0 until arr.length()) {
                    list.add(serializer.load_item(arr.get(i)))
                }
                list
            } catch (e: JSONException) {
                Logs.e("ListGroupPreference", "load_from_string failed", e)
                null
            }
        }

        /**
         * Encode a list to JSON string
         *
         * @param items Items to encode
         * @param serializer Serializer for items
         * @return JSON string
         */
        @JvmStatic
        fun <E> save_to_string(items: List<E>, serializer: Serializer<E>): String {
            val serialized_items = mutableListOf<Any>()
            for (item in items) {
                try {
                    serialized_items.add(serializer.save_item(item))
                } catch (e: JSONException) {
                    Logs.e("ListGroupPreference", "save_to_string failed", e)
                }
            }
            return JSONArray(serialized_items).toString()
        }
    }
}
