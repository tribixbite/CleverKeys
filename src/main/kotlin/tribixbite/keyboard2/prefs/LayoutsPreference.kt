package tribixbite.keyboard2.prefs

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.content.res.TypedArray
import android.util.AttributeSet
import android.view.View
import android.widget.ArrayAdapter
import org.json.JSONException
import org.json.JSONObject
import tribixbite.keyboard2.*

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
 * - Add/remove/modify layouts through ListGroupPreference infrastructure
 *
 * CRITICAL FIX (Bug #642): Now properly extends ListGroupPreference<Layout>
 * instead of DialogPreference. This enables full layout management UI.
 *
 * Ported from Java to Kotlin with modern improvements.
 */
class LayoutsPreference(
    context: Context,
    attrs: AttributeSet
) : ListGroupPreference<LayoutsPreference.Layout>(context, attrs) {

    // Layout display names loaded from resources
    private lateinit var layoutDisplayNames: Array<String>

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
                try {
                    // Use R class directly for compile-time safety
                    unsafeLayoutIdsStr = resources.getStringArray(R.array.pref_layout_values).toList()
                } catch (e: Exception) {
                    // Fallback to minimal set if resource not found
                    unsafeLayoutIdsStr = listOf("system", "custom")
                }
            }
            return unsafeLayoutIdsStr ?: emptyList()
        }

        /**
         * Get layout resource ID for a given layout name.
         * Returns -1 if layout name not found.
         */
        @JvmStatic
        fun layoutIdOfName(resources: Resources, name: String): Int {
            if (unsafeLayoutIdsRes == null) {
                try {
                    // Use R class directly for compile-time safety
                    unsafeLayoutIdsRes = resources.obtainTypedArray(R.array.layout_ids)
                } catch (e: Exception) {
                    return -1
                }
            }

            val layoutNames = getLayoutNames(resources)
            val index = layoutNames.indexOf(name)
            if (index >= 0 && unsafeLayoutIdsRes != null) {
                return unsafeLayoutIdsRes!!.getResourceId(index, 0)
            }
            return -1
        }

        /**
         * Load keyboard layouts from SharedPreferences.
         * Returns null for system layout entries.
         */
        @JvmStatic
        fun loadFromPreferences(resources: Resources, prefs: SharedPreferences): List<KeyboardData?> {
            val layouts = mutableListOf<KeyboardData?>()

            // Load layout list using ListGroupPreference's load_from_preferences
            val layoutItems = load_from_preferences(KEY, prefs, DEFAULT, SERIALIZER)

            for (layout in layoutItems) {
                when (layout) {
                    is NamedLayout -> layouts.add(layoutOfString(resources, layout.name))
                    is CustomLayout -> layouts.add(layout.parsed)
                    is SystemLayout -> {
                        // For SystemLayout, load a default layout (qwerty_us)
                        // This prevents empty layouts list when filterNotNull() is called
                        val defaultLayout = layoutOfString(resources, "qwerty_us")
                        if (defaultLayout != null) {
                            layouts.add(defaultLayout)
                        } else {
                            // Fallback: try to load any available layout
                            val layoutNames = getLayoutNames(resources)
                            var found = false
                            for (name in layoutNames) {
                                if (name != "system" && name != "custom") {
                                    val fallback = layoutOfString(resources, name)
                                    if (fallback != null) {
                                        layouts.add(fallback)
                                        found = true
                                        break
                                    }
                                }
                            }
                            if (!found) {
                                layouts.add(null) // Last resort
                            }
                        }
                    }
                    else -> layouts.add(null)
                }
            }

            return layouts
        }

        /**
         * Save layout preferences. Does not commit automatically.
         */
        @JvmStatic
        fun saveToPreferences(editor: SharedPreferences.Editor, layouts: List<Layout>) {
            save_to_preferences(KEY, editor, layouts, SERIALIZER)
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

    init {
        key = KEY

        // Load layout display names from resources
        try {
            val displayNamesId = context.resources.getIdentifier("pref_layout_entries", "array", null)
            layoutDisplayNames = if (displayNamesId != 0) {
                context.resources.getStringArray(displayNamesId)
            } else {
                // Fallback to layout names if resource not found
                getLayoutNames(context.resources).toTypedArray()
            }
        } catch (e: Exception) {
            // Fallback on error
            layoutDisplayNames = arrayOf("System", "Custom")
        }
    }

    override fun onSetInitialValue(restoreValue: Boolean, defaultValue: Any?) {
        super.onSetInitialValue(restoreValue, defaultValue)

        // Initialize with default values if empty (from ListGroupPreference)
        // Note: ListGroupPreference handles loading from SharedPreferences via getPersistedString()
    }

    // ====== ListGroupPreference Abstract Methods ======

    /**
     * Get display label for a layout entry.
     */
    private fun labelOfLayout(layout: Layout): String {
        return when (layout) {
            is NamedLayout -> {
                val layoutNames = getLayoutNames(context.resources)
                val valueIndex = layoutNames.indexOf(layout.name)
                if (valueIndex >= 0 && valueIndex < layoutDisplayNames.size) {
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
            else -> {
                "Unknown Layout"
            }
        }
    }

    /**
     * Required by ListGroupPreference: Format label for list item
     */
    override fun label_of_value(value: Layout, i: Int): String {
        return context.getString(R.string.pref_layouts_item, i + 1, labelOfLayout(value))
    }

    /**
     * Required by ListGroupPreference: Create add button
     */
    override fun on_attach_add_button(prev_btn: AddButton?): AddButton {
        return prev_btn ?: LayoutsAddButton()
    }

    /**
     * Required by ListGroupPreference: Determine if item can be removed
     */
    override fun should_allow_remove_item(value: Layout): Boolean {
        // Don't allow removal of custom layouts (they have "Remove" button in their dialog)
        // Allow removal of other layouts if more than one layout exists
        return value !is CustomLayout
    }

    /**
     * Required by ListGroupPreference: Get serializer
     */
    override fun get_serializer(): ListGroupPreference.Serializer<Layout> = SERIALIZER

    /**
     * Required by ListGroupPreference: Show selection dialog
     * Called when adding new layout or modifying existing one
     */
    override fun select(callback: SelectionCallback<Layout>, old_value: Layout?) {
        if (old_value is CustomLayout) {
            // Custom layouts get special edit dialog
            selectCustom(callback, old_value.xml)
        } else {
            // All other layouts use standard selection dialog
            selectDialog(callback)
        }
    }

    // ====== Helper Methods ======

    /**
     * Show layout selection dialog.
     */
    private fun selectDialog(callback: SelectionCallback<Layout>) {
        val layoutsAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_list_item_1,
            layoutDisplayNames
        )

        AlertDialog.Builder(context)
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
    private fun selectCustom(callback: SelectionCallback<Layout>, initialText: String) {
        val allowRemove = callback.allow_remove()

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
     * Read initial custom layout text from resources.
     * Uses QWERTY US layout as default with documentation.
     */
    private fun readInitialCustomLayout(): String {
        return try {
            val qwertyId = context.resources.getIdentifier("latn_qwerty_us", "raw", null)
            if (qwertyId != 0) {
                context.resources.openRawResource(qwertyId).use { inputStream ->
                    Utils.readAllUtf8(inputStream)
                }
            } else {
                "" // Fallback if resource not found
            }
        } catch (e: Exception) {
            "" // Fallback on error
        }
    }

    /**
     * Custom add button for layouts preference.
     */
    private inner class LayoutsAddButton() : AddButton(context) {
        init {
            layoutResource = R.layout.pref_layouts_add_btn
        }
    }

    // ====== Layout Types ======

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
    class Serializer : ListGroupPreference.Serializer<Layout> {

        @Throws(JSONException::class)
        override fun load_item(obj: Any): Layout {
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
        override fun save_item(item: Layout): Any {
            return when (item) {
                is NamedLayout -> item.name
                is CustomLayout -> JSONObject()
                    .put("kind", "custom")
                    .put("xml", item.xml)
                is SystemLayout -> JSONObject().put("kind", "system")
                else -> JSONObject().put("kind", "system") // Default fallback
            }
        }
    }
}
