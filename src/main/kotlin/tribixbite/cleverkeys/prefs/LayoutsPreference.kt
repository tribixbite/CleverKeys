package tribixbite.cleverkeys.prefs

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.content.res.TypedArray
import android.util.AttributeSet
import android.view.View
import android.widget.ArrayAdapter
import tribixbite.cleverkeys.CustomLayoutEditDialog
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.LayoutLanguageBinding
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.Utils
import org.json.JSONException
import org.json.JSONObject

@Suppress("DEPRECATION")
class LayoutsPreference(ctx: Context, attrs: AttributeSet?) : ListGroupPreference<LayoutsPreference.Layout>(ctx, attrs) {
    /** Text displayed for each layout in the dialog list. */
    private val layoutDisplayNames: Array<String>

    init {
        key = KEY
        val res = ctx.resources
        layoutDisplayNames = res.getStringArray(R.array.pref_layout_entries)
    }

    override fun onSetInitialValue(restoreValue: Boolean, defaultValue: Any?) {
        super.onSetInitialValue(restoreValue, defaultValue)
        if (values.isEmpty()) {
            setValues(DEFAULT.toMutableList(), false)
        }
    }

    private fun labelOfLayout(l: Layout): String {
        return when (l) {
            is NamedLayout -> {
                val valueI = getLayoutNames(context.resources).indexOf(l.name)
                if (valueI < 0) l.name else layoutDisplayNames[valueI]
            }
            is CustomLayout -> {
                // Use the layout's name if possible
                if (l.parsed?.name?.isNotEmpty() == true) {
                    l.parsed.name
                } else {
                    context.getString(R.string.pref_layout_e_custom)
                }
            }
            is SystemLayout -> context.getString(R.string.pref_layout_e_system)
            else -> ""
        }
    }

    override fun labelOfValue(value: Layout, i: Int): String {
        return context.getString(R.string.pref_layouts_item, i + 1, labelOfLayout(value))
    }

    override fun onAttachAddButton(prevBtn: AddButton?): AddButton {
        return prevBtn ?: LayoutsAddButton(context)
    }

    override fun shouldAllowRemoveItem(value: Layout): Boolean {
        return values.size > 1 && value !is CustomLayout
    }

    override fun getSerializer(): ListGroupPreference.Serializer<Layout> = SERIALIZER

    private fun selectDialog(callback: SelectionCallback<Layout>) {
        // Check if SystemLayout already exists - only allow one
        val hasSystemLayout = values.any { it is SystemLayout }
        val allNames = getLayoutNames(context.resources)
        val allDisplayNames = layoutDisplayNames.toList()

        // Filter out "system" if already present
        val filteredIndices = allNames.indices.filter { i ->
            !(allNames[i] == "system" && hasSystemLayout)
        }
        val filteredDisplayNames = filteredIndices.map { allDisplayNames[it] }

        val layouts = ArrayAdapter(context, android.R.layout.simple_list_item_1, filteredDisplayNames)
        AlertDialog.Builder(context)
            .setView(View.inflate(context, R.layout.dialog_edit_text, null))
            .setAdapter(layouts) { _, which ->
                val originalIndex = filteredIndices[which]
                val name = allNames[originalIndex]
                when (name) {
                    "system" -> callback.select(SystemLayout())
                    "custom" -> selectCustom(callback, readInitialCustomLayout())
                    else -> callback.select(NamedLayout(name))
                }
            }
            .show()
    }

    /**
     * Dialog for specifying a custom layout. [initialText] is the layout
     * description when modifying a layout.
     */
    private fun selectCustom(callback: SelectionCallback<Layout>, initialText: String) {
        val allowRemove = callback.allowRemove() && values.size > 1
        CustomLayoutEditDialog.show(
            context,
            initialText,
            allowRemove,
            object : CustomLayoutEditDialog.Callback {
                override fun select(text: String?) {
                    if (text == null) {
                        callback.select(null)
                    } else {
                        callback.select(CustomLayout.parse(text))
                    }
                }

                override fun validate(text: String): String? {
                    return try {
                        KeyboardData.load_string_exn(text)
                        null // Validation passed
                    } catch (e: Exception) {
                        e.message
                    }
                }
            }
        )
    }

    /** Called when modifying a layout. Custom layouts behave differently. */
    override fun select(callback: SelectionCallback<Layout>, oldValue: Layout?) {
        if (oldValue is CustomLayout) {
            // Editing the XML keeps the entry's language binding (GH #186/#61).
            selectCustom(object : SelectionCallback<Layout> {
                override fun select(value: Layout?) = callback.select(value?.withLanguage(oldValue.language))
                override fun allowRemove(): Boolean = callback.allowRemove()
            }, oldValue.xml)
        } else {
            selectDialog(callback)
        }
    }

    /**
     * The initial text for the custom layout entry box. The qwerty_us layout is
     * a good default and contains a bit of documentation.
     */
    private fun readInitialCustomLayout(): String {
        return try {
            val res = context.resources
            Utils.read_all_utf8(res.openRawResource(R.raw.latn_qwerty_us))
        } catch (e: Exception) {
            ""
        }
    }

    inner class LayoutsAddButton(ctx: Context) : AddButton(ctx) {
        init {
            layoutResource = R.layout.pref_layouts_add_btn
        }
    }

    /** A layout selected by the user. The only implementations are
     * [NamedLayout], [SystemLayout] and [CustomLayout]. */
    interface Layout {
        /**
         * The entry's language binding choice (GH #186/#61), already normalised by
         * [LayoutLanguageBinding.normalizeEntry]: null = no choice (the XML `language` default
         * applies), [LayoutLanguageBinding.UNBOUND] = explicitly unbound, else a language code.
         * Stored inside the entry so reordering, editing and deleting carry the binding along.
         */
        val language: String?

        /** This entry with its binding choice replaced by [language] (normalised). */
        fun withLanguage(language: String?): Layout
    }

    data class SystemLayout(override val language: String? = null) : Layout {
        override fun withLanguage(language: String?): Layout =
            copy(language = LayoutLanguageBinding.normalizeEntry(language))
    }

    /** The name of a layout defined in [srcs/layouts]. */
    data class NamedLayout(val name: String, override val language: String? = null) : Layout {
        override fun withLanguage(language: String?): Layout =
            copy(language = LayoutLanguageBinding.normalizeEntry(language))
    }

    /** The XML description of a custom layout. */
    data class CustomLayout(
        val xml: String,
        val parsed: KeyboardData?,
        override val language: String? = null
    ) : Layout {
        override fun withLanguage(language: String?): Layout =
            copy(language = LayoutLanguageBinding.normalizeEntry(language))

        companion object {
            @JvmStatic
            @JvmOverloads
            fun parse(xml: String, language: String? = null): CustomLayout {
                val parsed = try {
                    KeyboardData.load_string_exn(xml)
                } catch (e: Exception) {
                    null
                }
                return CustomLayout(xml, parsed, LayoutLanguageBinding.normalizeEntry(language))
            }
        }
    }

    /**
     * Named layouts are serialized to strings and custom layouts to JSON
     * objects with a [kind] field.
     *
     * GH #186/#61: an entry with a language binding also carries a `language` field; a bound
     * NAMED layout therefore needs the object form `{"kind":"named","name":…,"language":…}`.
     * Unbound entries keep their exact pre-binding encoding. Loading sanitises `language`
     * through [LayoutLanguageBinding.normalizeEntry] — the `layouts` blob is imported verbatim
     * by Backup & Restore, so this is where an invalid code from a file is dropped.
     */
    class Serializer : ListGroupPreference.Serializer<Layout> {
        @Throws(JSONException::class)
        override fun loadItem(obj: Any): Layout {
            return if (obj is String) {
                if (obj == "system") {
                    SystemLayout()
                } else {
                    NamedLayout(obj)
                }
            } else {
                val jsonObj = obj as JSONObject
                val language = LayoutLanguageBinding.normalizeEntry(
                    if (jsonObj.isNull(LANGUAGE_FIELD)) null else jsonObj.optString(LANGUAGE_FIELD)
                )
                when (jsonObj.getString("kind")) {
                    "custom" -> CustomLayout.parse(jsonObj.getString("xml"), language)
                    "named" -> NamedLayout(jsonObj.getString("name"), language)
                    "system" -> SystemLayout(language)
                    else -> SystemLayout()
                }
            }
        }

        @Throws(JSONException::class)
        override fun saveItem(v: Layout): Any {
            val language = LayoutLanguageBinding.normalizeEntry(v.language)
            return when (v) {
                is NamedLayout -> if (language == null) v.name else JSONObject()
                    .put("kind", "named")
                    .put("name", v.name)
                    .put(LANGUAGE_FIELD, language)
                is CustomLayout -> JSONObject()
                    .put("kind", "custom")
                    .put("xml", v.xml)
                    .apply { if (language != null) put(LANGUAGE_FIELD, language) }
                else -> JSONObject().put("kind", "system")
                    .apply { if (language != null) put(LANGUAGE_FIELD, language) }
            }
        }
    }

    companion object {
        const val KEY = "layouts"
        /** JSON field of an entry's language binding (GH #186/#61). */
        private const val LANGUAGE_FIELD = "language"
        val DEFAULT: List<Layout> = listOf(SystemLayout())
        val SERIALIZER: ListGroupPreference.Serializer<Layout> = Serializer()

        /** Obtained from [res/values/layouts.xml]. */
        private var unsafeLayoutIdsStr: List<String>? = null
        private var unsafeLayoutIdsRes: TypedArray? = null

        /** Layout internal names. Contains "system" and "custom". */
        @JvmStatic
        fun getLayoutNames(res: Resources): List<String> {
            if (unsafeLayoutIdsStr == null) {
                unsafeLayoutIdsStr = res.getStringArray(R.array.pref_layout_values).toList()
            }
            return unsafeLayoutIdsStr!!
        }

        /** Layout resource id for a layout name. [-1] if not found. */
        @JvmStatic
        fun layoutIdOfName(res: Resources, name: String): Int {
            if (unsafeLayoutIdsRes == null) {
                unsafeLayoutIdsRes = res.obtainTypedArray(R.array.layout_ids)
            }
            val i = getLayoutNames(res).indexOf(name)
            return if (i >= 0) {
                unsafeLayoutIdsRes!!.getResourceId(i, 0)
            } else {
                -1
            }
        }

        /** [null] for the "system" layout. */
        @JvmStatic
        @Deprecated("Use loadFromPreferences instead", ReplaceWith("loadFromPreferences(res, prefs)"))
        fun load_from_preferences(res: Resources, prefs: SharedPreferences): List<KeyboardData?> {
            return loadFromPreferences(res, prefs)
        }

        @JvmStatic
        fun loadFromPreferences(res: Resources, prefs: SharedPreferences): List<KeyboardData?> =
            loadLayoutsWithBindings(res, prefs).first

        /**
         * The layouts (null = System, resolved to the locale layout at runtime) together with
         * each layout's EFFECTIVE language binding, index-aligned (GH #186/#61). Loads and parses
         * the preference once for both, which is what `Config.refresh` needs.
         */
        @JvmStatic
        fun loadLayoutsWithBindings(
            res: Resources,
            prefs: SharedPreferences
        ): Pair<List<KeyboardData?>, List<String?>> {
            val layouts = mutableListOf<KeyboardData?>()
            val bindings = mutableListOf<String?>()
            for (l in loadFromPreferences(KEY, prefs, DEFAULT, SERIALIZER) ?: DEFAULT) {
                val data = when (l) {
                    is NamedLayout -> layoutOfString(res, l.name)
                    is CustomLayout -> l.parsed
                    else -> null
                }
                layouts.add(data)
                bindings.add(LayoutLanguageBinding.effective(l.language, data?.declared_language))
            }
            return layouts to bindings
        }

        /** Does not call [prefs.commit]. */
        @JvmStatic
        @Deprecated("Use saveToPreferences instead", ReplaceWith("saveToPreferences(prefs, items)"))
        fun save_to_preferences(prefs: SharedPreferences.Editor, items: List<Layout>) {
            saveToPreferences(prefs, items)
        }

        @JvmStatic
        fun saveToPreferences(prefs: SharedPreferences.Editor, items: List<Layout>) {
            saveToPreferences(KEY, prefs, items, SERIALIZER)
        }

        @JvmStatic
        fun layoutOfString(res: Resources, name: String): KeyboardData? {
            val id = layoutIdOfName(res, name)
            return if (id > 0) {
                KeyboardData.load(res, id)
            } else {
                // Might happen when the app is downgraded, return the system layout.
                null
            }
        }
    }
}
