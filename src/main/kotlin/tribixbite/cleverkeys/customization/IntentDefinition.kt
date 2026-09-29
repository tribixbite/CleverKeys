package tribixbite.cleverkeys.customization

import android.annotation.SuppressLint
import androidx.annotation.StringRes
import tribixbite.cleverkeys.R

/**
 * Definition of an intent to be executed.
 */
data class IntentDefinition(
    val name: String = "",
    val targetType: IntentTargetType = IntentTargetType.ACTIVITY,
    val action: String? = null,
    val data: String? = null, // URI
    val type: String? = null, // MIME type
    val packageName: String? = null,
    val className: String? = null,
    val extras: Map<String, String>? = null
) {
    companion object {
        /**
         * Prefix used by KeyValueParser to mark intent JSON in KeyValue strings.
         * When a layout XML contains `intent:'json'`, parsing produces a string key
         * with this prefix prepended. A future profile importer should strip this
         * prefix to recover the original JSON for round-trip compatibility.
         */
        const val INTENT_PREFIX = "__intent__:"

        /**
         * Parse an IntentDefinition from JSON with null-safety.
         * Gson bypasses Kotlin constructors (uses Unsafe), so fields that are absent
         * in JSON become null rather than their Kotlin defaults. This method applies
         * fallback defaults for non-nullable fields.
         *
         * @return Parsed IntentDefinition with safe defaults, or null on parse failure.
         */
        fun parseFromGson(json: String): IntentDefinition? {
            return try {
                val raw = com.google.gson.Gson().fromJson(json, IntentDefinition::class.java)
                    ?: return null
                // Re-apply Kotlin defaults for fields Gson may have set to null
                IntentDefinition(
                    name = raw.name ?: "",
                    targetType = raw.targetType ?: IntentTargetType.ACTIVITY,
                    action = raw.action,
                    data = raw.data,
                    type = raw.type,
                    packageName = raw.packageName,
                    className = raw.className,
                    extras = raw.extras
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Common intent presets for quick selection, each with its localized chip label.
         *
         * The preset's [IntentDefinition.name] stays the stable English identifier (tests and
         * logs refer to it); the editor shows [Preset.labelRes] and pre-fills the user-editable
         * name field with that localized label. Names already saved in a user's mappings are
         * never rewritten.
         */
        // The "/data/data/com.termux/files/usr/bin/echo" paths below are Termux's OWN
        // binary path (a foreign package), passed as the RUN_COMMAND_PATH extra to Termux's
        // RunCommandService. They are intentionally absolute Termux paths — NOT this app's
        // storage — so Context.getFilesDir() is inapplicable here.
        @SuppressLint("SdCardPath")
        val PRESET_ENTRIES: List<Preset> = listOf(
            // Browser
            Preset(
                R.string.intent_preset_open_browser,
                IntentDefinition(
                    name = "Open Browser",
                    action = "android.intent.action.VIEW",
                    data = "https://google.com"
                )
            ),
            // Share text
            Preset(
                R.string.intent_preset_share_text,
                IntentDefinition(
                    name = "Share Text",
                    action = "android.intent.action.SEND",
                    type = "text/plain",
                    extras = mapOf("android.intent.extra.TEXT" to "")
                )
            ),
            // Dial phone
            Preset(
                R.string.intent_preset_dial_phone,
                IntentDefinition(
                    name = "Dial Phone",
                    action = "android.intent.action.DIAL",
                    data = "tel:"
                )
            ),
            // Send email
            Preset(
                R.string.intent_preset_send_email,
                IntentDefinition(
                    name = "Send Email",
                    action = "android.intent.action.SENDTO",
                    data = "mailto:"
                )
            ),
            // Open settings
            Preset(
                R.string.intent_preset_open_settings,
                IntentDefinition(
                    name = "Open Settings",
                    action = "android.settings.SETTINGS"
                )
            ),
            // Open Wi-Fi settings
            Preset(
                R.string.intent_preset_wifi_settings,
                IntentDefinition(
                    name = "Wi-Fi Settings",
                    action = "android.settings.WIFI_SETTINGS"
                )
            ),
            // Open Bluetooth settings
            Preset(
                R.string.intent_preset_bluetooth_settings,
                IntentDefinition(
                    name = "Bluetooth Settings",
                    action = "android.settings.BLUETOOTH_SETTINGS"
                )
            ),
            // Open camera
            Preset(
                R.string.intent_preset_open_camera,
                IntentDefinition(
                    name = "Open Camera",
                    action = "android.media.action.IMAGE_CAPTURE"
                )
            ),
            // Open maps location
            Preset(
                R.string.intent_preset_open_maps,
                IntentDefinition(
                    name = "Open Maps",
                    action = "android.intent.action.VIEW",
                    data = "geo:0,0?q="
                )
            ),
            // Search web
            Preset(
                R.string.intent_preset_web_search,
                IntentDefinition(
                    name = "Web Search",
                    action = "android.intent.action.WEB_SEARCH"
                )
            ),
            // Termux background command (runs silently, no visible tab)
            Preset(
                R.string.intent_preset_termux_background,
                IntentDefinition(
                    name = "Termux Command (Background)",
                    targetType = IntentTargetType.SERVICE,
                    action = "com.termux.RUN_COMMAND",
                    packageName = "com.termux",
                    className = "com.termux.app.RunCommandService",
                    extras = mapOf(
                        "com.termux.RUN_COMMAND_PATH" to "/data/data/com.termux/files/usr/bin/echo",
                        "com.termux.RUN_COMMAND_ARGUMENTS" to "Hello from CleverKeys",
                        "com.termux.RUN_COMMAND_BACKGROUND" to "true",
                        // SESSION_ACTION=0 shows the terminal session tab (required when BACKGROUND=false)
                        // For background commands it's ignored, but included for documentation
                        "com.termux.RUN_COMMAND_SESSION_ACTION" to "0"
                    )
                )
            ),
            // Termux visible tab (opens terminal showing command output)
            Preset(
                R.string.intent_preset_termux_visible,
                IntentDefinition(
                    name = "Termux Tab (Visible)",
                    targetType = IntentTargetType.SERVICE,
                    action = "com.termux.RUN_COMMAND",
                    packageName = "com.termux",
                    className = "com.termux.app.RunCommandService",
                    extras = mapOf(
                        "com.termux.RUN_COMMAND_PATH" to "/data/data/com.termux/files/usr/bin/echo",
                        "com.termux.RUN_COMMAND_ARGUMENTS" to "Hello from CleverKeys",
                        "com.termux.RUN_COMMAND_BACKGROUND" to "false",
                        // SESSION_ACTION: 0=new tab, 1=new tab (switch), 2=attach to existing
                        "com.termux.RUN_COMMAND_SESSION_ACTION" to "0"
                    )
                )
            )
        )

        /** The preset intent definitions, in chip order. */
        val PRESETS: List<IntentDefinition> = PRESET_ENTRIES.map { it.definition }
    }

    /** One quick-selection preset: its localized chip label and the intent it fills in. */
    data class Preset(
        @StringRes val labelRes: Int,
        val definition: IntentDefinition
    )
}

/**
 * Type of intent target.
 */
enum class IntentTargetType {
    ACTIVITY,
    SERVICE,
    BROADCAST
}
