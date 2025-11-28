package tribixbite.keyboard2.prefs

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.content.res.TypedArray
import android.util.AttributeSet
import android.view.View
import android.widget.ArrayAdapter
import tribixbite.keyboard2.CustomLayoutEditDialog
import tribixbite.keyboard2.KeyboardData
import tribixbite.keyboard2.R
import tribixbite.keyboard2.Utils
import org.json.JSONException
import org.json.JSONObject

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
        val layouts = ArrayAdapter(context, android.R.layout.simple_list_item_1, layoutDisplayNames)
        AlertDialog.Builder(context)
            .setView(View.inflate(context, R.layout.dialog_edit_text, null))
            .setAdapter(layouts) { _, which ->
                val name = getLayoutNames(context.resources)[which]
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
            selectCustom(callback, oldValue.xml)
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
    interface Layout

    class SystemLayout : Layout

    /** The name of a layout defined in [srcs/layouts]. */
    data class NamedLayout(val name: String) : Layout

    /** The XML description of a custom layout. */
    data class CustomLayout(val xml: String, val parsed: KeyboardData?) : Layout {
        companion object {
            @JvmStatic
            fun parse(xml: String): CustomLayout {
                val parsed = try {
                    KeyboardData.load_string_exn(xml)
                } catch (e: Exception) {
                    null
                }
                return CustomLayout(xml, parsed)
            }
        }
    }

    /**
     * Named layouts are serialized to strings and custom layouts to JSON
     * objects with a [kind] field.
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
                when (jsonObj.getString("kind")) {
                    "custom" -> CustomLayout.parse(jsonObj.getString("xml"))
                    "system" -> SystemLayout()
                    else -> SystemLayout()
                }
            }
        }

        @Throws(JSONException::class)
        override fun saveItem(v: Layout): Any {
            return when (v) {
                is NamedLayout -> v.name
                is CustomLayout -> JSONObject()
                    .put("kind", "custom")
                    .put("xml", v.xml)
                else -> JSONObject().put("kind", "system")
            }
        }
    }

    companion object {
        const val KEY = "layouts"
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
        fun loadFromPreferences(res: Resources, prefs: SharedPreferences): List<KeyboardData?> {
            val layouts = mutableListOf<KeyboardData?>()
            for (l in loadFromPreferences(KEY, prefs, DEFAULT, SERIALIZER) ?: DEFAULT) {
                when (l) {
                    is NamedLayout -> layouts.add(layoutOfString(res, l.name))
                    is CustomLayout -> layouts.add(l.parsed)
                    is SystemLayout -> layouts.add(null)
                }
            }
            return layouts
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
