package tribixbite.keyboard2.prefs

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.content.res.Resources
import android.content.res.TypedArray
import android.preference.DialogPreference
import android.util.AttributeSet
import android.view.View
import android.widget.ArrayAdapter
import kotlinx.coroutines.*
import org.json.JSONException
import org.json.JSONObject
import tribixbite.keyboard2.*
import tribixbite.keyboard2.KeyboardData
import tribixbite.keyboard2.CustomLayoutEditDialog
import tribixbite.keyboard2.Utils
import java.util.*

/**
 * Layout selection preference for CleverKeys.
 * Manages keyboard layouts including system default, named layouts, and custom user layouts.
 *
 * Features:
 * - System layout (uses device's default layout)
 * - Named layouts (predefined layouts from resources)
 * - Custom layouts (user-defined XML layouts)
 * - Dynamic layout validation and parsing
 * - Reactive preference updates
 */
class LayoutsPreference @JvmOverloads constructor(
    private val context: Context,
    attrs: AttributeSet? = null
) : DialogPreference(context, attrs) {

    // Layout values and related properties
    private var values: MutableList<Layout> = mutableListOf()
    private val layoutDisplayNames: Array<String> get() = values.map { labelOfLayout(it) }.toTypedArray()

    companion object {
        const val KEY = "layouts"

        @JvmField
        val DEFAULT: List<Layout> = listOf(SystemLayout())

        @JvmField
        val SERIALIZER: Serializer = Serializer()

        // Cached layout information
        private var unsafeLayoutIdsStr: List<String>? = null
        private var unsafeLayoutIdsRes: TypedArray? = null

        /**
         * Get all available layout internal names from resources.
         * Includes "system" and "custom" entries.
         */
        @JvmStatic
        fun getLayoutNames(resources: Resources): List<String> {
            if (unsafeLayoutIdsStr == null) {
                unsafeLayoutIdsStr = resources.getStringArray(R.array.pref_layout_values).toList()
            }
            return unsafeLayoutIdsStr!!
        }

        /**
         * Get layout resource ID for a given layout name.
         * Returns -1 if layout name not found.
         */
        @JvmStatic
        fun layoutIdOfName(resources: Resources, name: String): Int {
            if (unsafeLayoutIdsRes == null) {
                unsafeLayoutIdsRes = resources.obtainTypedArray(R.array.layout_ids)
            }
            val index = getLayoutNames(resources).indexOf(name)
            return if (index >= 0) {
                unsafeLayoutIdsRes!!.getResourceId(index, 0)
            } else {
                -1
            }
        }

        /**
         * Load keyboard layouts from SharedPreferences.
         * Returns null for system layout entries.
         */
        @JvmStatic
        fun loadFromPreferences(resources: Resources, prefs: SharedPreferences): List<KeyboardData?> {
            val layouts = mutableListOf<KeyboardData?>()
            val layoutPrefs = loadFromPreferences(KEY, prefs, DEFAULT, SERIALIZER)

            for (layout in layoutPrefs) {
                when (layout) {
                    is NamedLayout -> {
                        layouts.add(layoutOfString(resources, layout.name))
                    }
                    is CustomLayout -> {
                        layouts.add(layout.parsed)
                    }
                    is SystemLayout -> {
                        layouts.add(null) // System layout represented as null
                    }
                }
            }
            return layouts
        }

        /**
         * Save layout preferences. Does not commit automatically.
         */
        @JvmStatic
        fun saveToPreferences(editor: SharedPreferences.Editor, layouts: List<Layout>) {
            saveToPreferences(KEY, editor, layouts, SERIALIZER)
        }

        /**
         * Load keyboard layout from layout name.
         * Returns null for system layout or when layout not found.
         */
        @JvmStatic
        fun layoutOfString(resources: Resources, name: String): KeyboardData? {
            val layoutId = layoutIdOfName(resources, name)
            return if (layoutId > 0) {
                try {
                    KeyboardData.load(resources, layoutId)
                } catch (e: Exception) {
                    null // Return system layout on error
                }
            } else {
                null // Might happen when app is downgraded
            }
        }
    }

    /** Display names for layouts shown in dialog */
    private val layoutDisplayNames: Array<String> = context.resources.getStringArray(R.array.pref_layout_entries)

    init {
        key = KEY
    }

    override fun onSetInitialValue(restoreValue: Boolean, defaultValue: Any?) {
        super.onSetInitialValue(restoreValue, defaultValue)
        if (values.isEmpty()) {
            setValues(ArrayList(DEFAULT), false)
        }
    }

    /**
     * Get display label for a layout entry.
     */
    private fun labelOfLayout(layout: Layout): String {
        return when (layout) {
            is NamedLayout -> {
                val layoutNames = getLayoutNames(context.resources)
                val valueIndex = layoutNames.indexOf(layout.name)
                if (valueIndex >= 0) {
                    layoutDisplayNames[valueIndex]
                } else {
                    layout.name
                }
            }
            is CustomLayout -> {
                // Use layout's name if available, otherwise generic label
                if (layout.parsed?.name?.isNotEmpty() == true) {
                    layout.parsed.name
                } else {
                    context.getString(R.string.pref_layout_e_custom)
                }
            }
            is SystemLayout -> {
                context.getString(R.string.pref_layout_e_system)
            }
        }
    }

    fun labelOfValue(value: Layout, index: Int): String {
        return "Layout ${index + 1}: ${labelOfLayout(value)}"
    }

    fun onAttachAddButton(prevButton: LayoutsAddButton?): LayoutsAddButton {
        return prevButton ?: LayoutsAddButton(context)
    }

    fun shouldAllowRemoveItem(value: Layout): Boolean {
        // Allow removal if more than one layout exists and it's not a custom layout
        return values.size > 1 && value !is CustomLayout
    }

    fun getSerializer(): Serializer = SERIALIZER

    /**
     * Selection callback interface for layout dialogs.
     */
    interface SelectionCallback {
        fun select(layout: Layout?)
        fun allowRemove(): Boolean = false
    }

    /**
     * Show layout selection dialog.
     */
    private fun selectDialog(callback: SelectionCallback) {
        val layoutsAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_list_item_1,
            layoutDisplayNames
        )

        AlertDialog.Builder(context)
            .setView(View.inflate(context, R.layout.dialog_edit_text, null))
            .setAdapter(layoutsAdapter) { _, which ->
                val layoutName = getLayoutNames(context.resources)[which]
                when (layoutName) {
                    "system" -> callback.select(SystemLayout())
                    "custom" -> selectCustom(callback, readInitialCustomLayout())
                    else -> callback.select(NamedLayout(layoutName))
                }
            }
            .show()
    }

    /**
     * Show custom layout editing dialog.
     * @param callback Selection callback
     * @param initialText Initial XML text for the layout
     */
    private fun selectCustom(callback: SelectionCallback, initialText: String) {
        val allowRemove = callback.allowRemove() && values.size > 1

        CustomLayoutEditDialog.show(
            context,
            initialText,
            allowRemove,
            object : CustomLayoutEditDialog.Callback {
                override fun onSelect(text: String?) {
                    if (text == null) {
                        callback.select(null)
                    } else {
                        callback.select(CustomLayout.parse(text))
                    }
                }

                override fun validate(text: String): String? {
                    return try {
                        KeyboardData.loadStringExn(text)
                        null // Validation passed
                    } catch (e: Exception) {
                        e.message
                    }
                }
            }
        )
    }

    /**
     * Handle layout selection with special handling for custom layouts.
     */
    override fun select(callback: SelectionCallback, prevLayout: Layout?) {
        if (prevLayout is CustomLayout) {
            selectCustom(callback, prevLayout.xml)
        } else {
            selectDialog(callback)
        }
    }

    /**
     * Read initial custom layout text from resources.
     * Uses QWERTY US layout as default with documentation.
     */
    private fun readInitialCustomLayout(): String {
        return try {
            val resources = context.resources
            Utils.readAllUtf8(resources.openRawResource(R.raw.qwerty))
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Custom add button for layouts preference.
     */
    private class LayoutsAddButton(context: Context) : View(context) {
        init {
            // Simple button implementation
        }
    }

    /**
     * Base interface for all layout types.
     */
    interface Layout

    /**
     * System layout - uses device's default keyboard layout.
     */
    data class SystemLayout(val placeholder: Unit = Unit) : Layout

    /**
     * Named layout - references a predefined layout from resources.
     */
    data class NamedLayout(val name: String) : Layout

    /**
     * Custom layout - user-defined XML layout description.
     */
    data class CustomLayout(
        val xml: String,
        val parsed: KeyboardData? = null
    ) : Layout {

        companion object {
            /**
             * Parse custom layout from XML string.
             */
            fun parse(xml: String): CustomLayout {
                val parsed = try {
                    KeyboardData.loadStringExn(xml)
                } catch (e: Exception) {
                    null
                }
                return CustomLayout(xml, parsed)
            }
        }
    }

    /**
     * Serializer for saving/loading layout preferences.
     * Named layouts are serialized as strings, custom layouts as JSON objects.
     */
    class Serializer {

        @Throws(JSONException::class)
        fun loadItem(obj: Any): Layout {
            return when (obj) {
                is String -> {
                    when (obj) {
                        "system" -> SystemLayout()
                        else -> NamedLayout(obj)
                    }
                }
                is JSONObject -> {
                    when (obj.getString("kind")) {
                        "custom" -> CustomLayout.parse(obj.getString("xml"))
                        "system" -> SystemLayout()
                        else -> SystemLayout()
                    }
                }
                else -> SystemLayout()
            }
        }

        @Throws(JSONException::class)
        fun saveItem(layout: Layout): Any {
            return when (layout) {
                is NamedLayout -> layout.name
                is CustomLayout -> JSONObject()
                    .put("kind", "custom")
                    .put("xml", layout.xml)
                is SystemLayout -> JSONObject().put("kind", "system")
                else -> JSONObject().put("kind", "system") // Default fallback
            }
        }
    }
}