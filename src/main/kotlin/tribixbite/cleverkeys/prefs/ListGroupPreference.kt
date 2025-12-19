package tribixbite.cleverkeys.prefs

import android.content.Context
import android.content.SharedPreferences
import android.preference.Preference
import android.preference.PreferenceGroup
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import tribixbite.cleverkeys.Logs
import tribixbite.cleverkeys.R
import org.json.JSONArray
import org.json.JSONException

/**
 * A list of preferences where the users can add items to the end and modify
 * and remove items. Backed by a string list. Implement user selection in
 * [select].
 */
@Suppress("DEPRECATION")
abstract class ListGroupPreference<E>(context: Context, attrs: AttributeSet?) : PreferenceGroup(context, attrs) {
    private var attached = false
    protected var values: MutableList<E> = mutableListOf()
    /** The "add" button currently displayed. */
    private var addButton: AddButton? = null

    init {
        isOrderingAsAdded = true
        layoutResource = R.layout.pref_listgroup_group
    }

    /** Overrideable */

    /** The label to display on the item for a given value. */
    abstract fun labelOfValue(value: E, i: Int): String

    /**
     * Called every time the list changes and allows to change the "Add" button
     * appearance.
     * [prevBtn] is the previously attached button, might be null.
     */
    open fun onAttachAddButton(prevBtn: AddButton?): AddButton {
        return prevBtn ?: AddButton(context)
    }

    /**
     * Called every time the list changes and allows to disable the "Remove"
     * buttons on every items. Might be used to enforce a minimum number of
     * items.
     */
    open fun shouldAllowRemoveItem(value: E): Boolean = true

    /**
     * Called when an item is added or modified. [oldValue] is [null] if the
     * item is being added.
     */
    abstract fun select(callback: SelectionCallback<E>, oldValue: E?)

    /**
     * A separate class is used as the same serializer must be used in the
     * static context. See [Serializer] below.
     */
    abstract fun getSerializer(): Serializer<E>

    /** Protected API */

    /** Set the values. If [persist] is [true], persist into the store. */
    protected fun setValues(vs: MutableList<E>, persist: Boolean) {
        values = vs
        reattach()
        if (persist) {
            persistString(saveToString(vs, getSerializer()))
        }
    }

    private fun addItem(v: E) {
        values.add(v)
        setValues(values, true)
    }

    private fun changeItem(i: Int, v: E) {
        values[i] = v
        setValues(values, true)
    }

    private fun removeItem(i: Int) {
        values.removeAt(i)
        setValues(values, true)
    }

    /** Internal */

    override fun onSetInitialValue(restoreValue: Boolean, defaultValue: Any?) {
        val input = if (restoreValue) getPersistedString(null) else defaultValue as? String
        input?.let {
            val loadedValues = loadFromString(it, getSerializer())
            loadedValues?.let { setValues(it.toMutableList(), false) }
        }
    }

    override fun onAttachedToActivity() {
        super.onAttachedToActivity()
        if (attached) return
        attached = true
        reattach()
    }

    private fun reattach() {
        if (!attached) return
        removeAll()

        values.forEachIndexed { i, v ->
            addPreference(Item(context, i, v))
        }

        addButton = onAttachAddButton(addButton)
        addButton?.order = Preference.DEFAULT_ORDER
        addButton?.let { addPreference(it) }
    }

    inner class Item(ctx: Context, private val index: Int, private val value: E) : Preference(ctx) {
        init {
            isPersistent = false
            title = labelOfValue(value, index)
            if (shouldAllowRemoveItem(value)) {
                widgetLayoutResource = R.layout.pref_listgroup_item_widget
            }
        }

        override fun onCreateView(parent: ViewGroup): View {
            val v = super.onCreateView(parent)

            v.findViewById<View>(R.id.pref_listgroup_remove_btn)?.setOnClickListener {
                removeItem(index)
            }

            v.setOnClickListener {
                select(object : SelectionCallback<E> {
                    override fun select(value: E?) {
                        if (value == null) {
                            removeItem(index)
                        } else {
                            changeItem(index, value)
                        }
                    }

                    override fun allowRemove(): Boolean = true
                }, value)
            }

            return v
        }
    }

    open inner class AddButton(ctx: Context) : Preference(ctx) {
        init {
            isPersistent = false
            layoutResource = R.layout.pref_listgroup_add_btn
        }

        override fun onClick() {
            select(object : SelectionCallback<E> {
                override fun select(value: E?) {
                    value?.let { addItem(it) }
                }

                override fun allowRemove(): Boolean = false
            }, null)
        }
    }

    interface SelectionCallback<E> {
        fun select(value: E?)

        /**
         * If this method returns [true], [null] might be passed to [select] to
         * remove the item.
         */
        fun allowRemove(): Boolean
    }

    /**
     * Methods for serializing and deserializing abstract items.
     * [StringSerializer] is an implementation.
     */
    interface Serializer<E> {
        /** [obj] is an object returned by [saveItem]. */
        @Throws(JSONException::class)
        fun loadItem(obj: Any): E

        /**
         * Serialize an item into JSON. Might return an object that can be inserted
         * in a [JSONArray].
         */
        @Throws(JSONException::class)
        fun saveItem(v: E): Any
    }

    class StringSerializer : Serializer<String> {
        override fun loadItem(obj: Any): String = obj as String
        override fun saveItem(v: String): Any = v
    }

    companion object {
        /** Load/save utils */

        /**
         * Read a value saved by preference from a [SharedPreferences] object.
         * [serializer] must be the same that is returned by [getSerializer].
         * Returns [null] on error.
         */
        @JvmStatic
        fun <E> loadFromPreferences(
            key: String,
            prefs: SharedPreferences,
            def: List<E>?,
            serializer: Serializer<E>
        ): List<E>? {
            val s = prefs.getString(key, null)
            return if (s != null) loadFromString(s, serializer) else def
        }

        /** Save items into the preferences. Does not call [prefs.commit]. */
        @JvmStatic
        fun <E> saveToPreferences(
            key: String,
            prefs: SharedPreferences.Editor,
            items: List<E>,
            serializer: Serializer<E>
        ) {
            prefs.putString(key, saveToString(items, serializer))
        }

        /**
         * Decode a list of string previously encoded with [saveToString]. Returns
         * [null] on error.
         */
        @JvmStatic
        fun <E> loadFromString(inp: String, serializer: Serializer<E>): List<E>? {
            return try {
                val l = mutableListOf<E>()
                val arr = JSONArray(inp)
                for (i in 0 until arr.length()) {
                    l.add(serializer.loadItem(arr.get(i)))
                }
                l
            } catch (e: JSONException) {
                Logs.exn("load_from_string", e)
                null
            }
        }

        /**
         * Encode a list of string so it can be passed to
         * [Preference.persistString]. Decode with [loadFromString].
         */
        @JvmStatic
        fun <E> saveToString(items: List<E>, serializer: Serializer<E>): String {
            val serializedItems = mutableListOf<Any>()
            for (it in items) {
                try {
                    serializedItems.add(serializer.saveItem(it))
                } catch (e: JSONException) {
                    Logs.exn("save_to_string", e)
                }
            }
            return JSONArray(serializedItems).toString()
        }
    }
}
