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
import tribixbite.cleverkeys.KeyGeometryField
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.LayoutKeyGeometry
import tribixbite.cleverkeys.LayoutLanguageBinding
import tribixbite.cleverkeys.LayoutThemeBinding
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.Utils
import org.json.JSONException
import org.json.JSONObject

/** One entry's stored per-field key-geometry choices (see [LayoutKeyGeometry]); null = none. */
typealias KeyGeometryEntry = Map<KeyGeometryField, LayoutKeyGeometry.Choice>

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
            // Editing the XML keeps the entry's language (GH #186/#61) and theme bindings.
            selectCustom(object : SelectionCallback<Layout> {
                override fun select(value: Layout?) =
                    callback.select(
                        value?.withLanguage(oldValue.language)?.withTheme(oldValue.theme)
                            ?.withKeyGeometry(oldValue.keyGeometry)
                    )
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

        /**
         * The entry's THEME binding choice, already normalised by
         * [LayoutThemeBinding.normalizeEntry]: null = no choice (the XML `theme` default applies),
         * [LayoutThemeBinding.UNBOUND] = explicitly follows the global theme, else a theme id.
         * Stored inside the entry, like [language], so reordering, editing, deleting and Backup &
         * Restore carry it along.
         */
        val theme: String?

        /** This entry with its theme binding choice replaced by [theme] (normalised). */
        fun withTheme(theme: String?): Layout

        /**
         * The entry's per-layout KEY GEOMETRY choices (gaps, border), normalised by
         * [LayoutKeyGeometry.normalized]: null = no choice (the XML attributes apply), else per
         * field [LayoutKeyGeometry.Choice.Inherit] (follow the global setting) or a value.
         * Stored inside the entry like [theme].
         */
        val keyGeometry: KeyGeometryEntry?

        /** This entry with its key-geometry choices replaced by [keyGeometry] (normalised). */
        fun withKeyGeometry(keyGeometry: KeyGeometryEntry?): Layout
    }

    data class SystemLayout(
        override val language: String? = null,
        override val theme: String? = null,
        override val keyGeometry: KeyGeometryEntry? = null
    ) : Layout {
        override fun withLanguage(language: String?): Layout =
            copy(language = LayoutLanguageBinding.normalizeEntry(language))

        override fun withTheme(theme: String?): Layout =
            copy(theme = LayoutThemeBinding.normalizeEntry(theme))

        override fun withKeyGeometry(keyGeometry: KeyGeometryEntry?): Layout =
            copy(keyGeometry = LayoutKeyGeometry.normalized(keyGeometry))
    }

    /** The name of a layout defined in [srcs/layouts]. */
    data class NamedLayout(
        val name: String,
        override val language: String? = null,
        override val theme: String? = null,
        override val keyGeometry: KeyGeometryEntry? = null
    ) : Layout {
        override fun withLanguage(language: String?): Layout =
            copy(language = LayoutLanguageBinding.normalizeEntry(language))

        override fun withTheme(theme: String?): Layout =
            copy(theme = LayoutThemeBinding.normalizeEntry(theme))

        override fun withKeyGeometry(keyGeometry: KeyGeometryEntry?): Layout =
            copy(keyGeometry = LayoutKeyGeometry.normalized(keyGeometry))
    }

    /** The XML description of a custom layout. */
    data class CustomLayout(
        val xml: String,
        val parsed: KeyboardData?,
        override val language: String? = null,
        override val theme: String? = null,
        override val keyGeometry: KeyGeometryEntry? = null
    ) : Layout {
        override fun withLanguage(language: String?): Layout =
            copy(language = LayoutLanguageBinding.normalizeEntry(language))

        override fun withTheme(theme: String?): Layout =
            copy(theme = LayoutThemeBinding.normalizeEntry(theme))

        override fun withKeyGeometry(keyGeometry: KeyGeometryEntry?): Layout =
            copy(keyGeometry = LayoutKeyGeometry.normalized(keyGeometry))

        companion object {
            @JvmStatic
            @JvmOverloads
            fun parse(
                xml: String,
                language: String? = null,
                theme: String? = null,
                keyGeometry: KeyGeometryEntry? = null,
            ): CustomLayout {
                val parsed = try {
                    KeyboardData.load_string_exn(xml)
                } catch (e: Exception) {
                    null
                }
                return CustomLayout(
                    xml,
                    parsed,
                    LayoutLanguageBinding.normalizeEntry(language),
                    LayoutThemeBinding.normalizeEntry(theme),
                    LayoutKeyGeometry.normalized(keyGeometry)
                )
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
     *
     * The per-layout THEME binding follows the same rules with a `theme` field, sanitised by
     * [LayoutThemeBinding.normalizeEntry]; a named layout uses the object form when either
     * binding is set.
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
                val language = LayoutLanguageBinding.normalizeEntry(optionalString(jsonObj, LANGUAGE_FIELD))
                val theme = LayoutThemeBinding.normalizeEntry(optionalString(jsonObj, THEME_FIELD))
                val geometry = LayoutKeyGeometry.normalizeEntry(optionalObject(jsonObj, LayoutKeyGeometry.JSON_FIELD))
                when (jsonObj.getString("kind")) {
                    "custom" -> CustomLayout.parse(jsonObj.getString("xml"), language, theme, geometry)
                    "named" -> NamedLayout(jsonObj.getString("name"), language, theme, geometry)
                    "system" -> SystemLayout(language, theme, geometry)
                    else -> SystemLayout()
                }
            }
        }

        /** [field] of [obj] as a string, or null when it is absent or JSON null. */
        private fun optionalString(obj: JSONObject, field: String): String? =
            if (obj.isNull(field)) null else obj.optString(field)

        /**
         * [field] of [obj] as a key → value map when it is a JSON object, else null — a hostile
         * non-object value from a backup is dropped (the layout survives).
         */
        private fun optionalObject(obj: JSONObject, field: String): Map<String, Any?>? {
            val o = obj.optJSONObject(field) ?: return null
            return o.keys().asSequence().associateWith { k -> if (o.isNull(k)) null else o.get(k) }
        }

        @Throws(JSONException::class)
        override fun saveItem(v: Layout): Any {
            val language = LayoutLanguageBinding.normalizeEntry(v.language)
            val theme = LayoutThemeBinding.normalizeEntry(v.theme)
            val geometry = LayoutKeyGeometry.toRaw(v.keyGeometry)
            // Unbound entries keep their exact pre-binding encoding (a named layout stays a
            // plain string), so an untouched preference is byte-identical across versions.
            val bindings: JSONObject.() -> JSONObject = {
                if (language != null) put(LANGUAGE_FIELD, language)
                if (theme != null) put(THEME_FIELD, theme)
                if (geometry != null) put(LayoutKeyGeometry.JSON_FIELD, JSONObject(geometry))
                this
            }
            return when (v) {
                is NamedLayout -> if (language == null && theme == null && geometry == null) v.name else JSONObject()
                    .put("kind", "named")
                    .put("name", v.name)
                    .bindings()
                is CustomLayout -> JSONObject()
                    .put("kind", "custom")
                    .put("xml", v.xml)
                    .bindings()
                else -> JSONObject().put("kind", "system").bindings()
            }
        }
    }

    /**
     * Result of [loadLayoutsWithBindings]: the layouts and each one's effective language and
     * theme binding (null = unbound), index-aligned.
     */
    data class LoadedLayouts(
        val layouts: List<KeyboardData?>,
        val languages: List<String?>,
        val themes: List<String?>,
        /** Each layout's effective key-geometry overrides ([LayoutKeyGeometry.effective]). */
        val keyGeometry: List<Map<KeyGeometryField, Float>> = emptyList(),
    )

    companion object {
        const val KEY = "layouts"
        /** JSON field of an entry's language binding (GH #186/#61). */
        private const val LANGUAGE_FIELD = "language"
        /** JSON field of an entry's theme binding (see [LayoutThemeBinding]). */
        private const val THEME_FIELD = "theme"
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
            loadLayoutsWithBindings(res, prefs).layouts

        /**
         * The layouts (null = System, resolved to the locale layout at runtime) together with
         * each layout's EFFECTIVE language binding (GH #186/#61) and EFFECTIVE theme binding,
         * all index-aligned. Loads and parses the preference once for all three, which is what
         * `Config.refresh` needs. Destructures as `(layouts, languages, themes)`.
         */
        @JvmStatic
        fun loadLayoutsWithBindings(res: Resources, prefs: SharedPreferences): LoadedLayouts {
            val layouts = mutableListOf<KeyboardData?>()
            val languages = mutableListOf<String?>()
            val themes = mutableListOf<String?>()
            val geometry = mutableListOf<Map<KeyGeometryField, Float>>()
            for (l in loadFromPreferences(KEY, prefs, DEFAULT, SERIALIZER) ?: DEFAULT) {
                val data = when (l) {
                    is NamedLayout -> layoutOfString(res, l.name)
                    is CustomLayout -> l.parsed
                    else -> null
                }
                layouts.add(data)
                languages.add(LayoutLanguageBinding.effective(l.language, data?.declared_language))
                themes.add(LayoutThemeBinding.effective(l.theme, data?.declared_theme))
                geometry.add(
                    LayoutKeyGeometry.effective(
                        l.keyGeometry, LayoutKeyGeometry.declaredValues(data?.declared_key_geometry),
                    ),
                )
            }
            return LoadedLayouts(layouts, languages, themes, geometry)
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
