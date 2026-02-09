package tribixbite.cleverkeys.customization

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
         * Common intent presets for quick selection.
         */
        val PRESETS: List<IntentDefinition> = listOf(
            // Browser
            IntentDefinition(
                name = "Open Browser",
                action = "android.intent.action.VIEW",
                data = "https://google.com"
            ),
            // Share text
            IntentDefinition(
                name = "Share Text",
                action = "android.intent.action.SEND",
                type = "text/plain",
                extras = mapOf("android.intent.extra.TEXT" to "")
            ),
            // Dial phone
            IntentDefinition(
                name = "Dial Phone",
                action = "android.intent.action.DIAL",
                data = "tel:"
            ),
            // Send email
            IntentDefinition(
                name = "Send Email",
                action = "android.intent.action.SENDTO",
                data = "mailto:"
            ),
            // Open settings
            IntentDefinition(
                name = "Open Settings",
                action = "android.settings.SETTINGS"
            ),
            // Open Wi-Fi settings
            IntentDefinition(
                name = "Wi-Fi Settings",
                action = "android.settings.WIFI_SETTINGS"
            ),
            // Open Bluetooth settings
            IntentDefinition(
                name = "Bluetooth Settings",
                action = "android.settings.BLUETOOTH_SETTINGS"
            ),
            // Open camera
            IntentDefinition(
                name = "Open Camera",
                action = "android.media.action.IMAGE_CAPTURE"
            ),
            // Open maps location
            IntentDefinition(
                name = "Open Maps",
                action = "android.intent.action.VIEW",
                data = "geo:0,0?q="
            ),
            // Search web
            IntentDefinition(
                name = "Web Search",
                action = "android.intent.action.WEB_SEARCH"
            ),
            // Termux command (for power users)
            IntentDefinition(
                name = "Termux Command",
                targetType = IntentTargetType.SERVICE,
                action = "com.termux.RUN_COMMAND",
                packageName = "com.termux",
                className = "com.termux.app.RunCommandService",
                extras = mapOf(
                    "com.termux.RUN_COMMAND_PATH" to "/data/data/com.termux/files/usr/bin/echo",
                    "com.termux.RUN_COMMAND_ARGUMENTS" to "Hello",
                    "com.termux.RUN_COMMAND_BACKGROUND" to "true"
                )
            )
        )
    }
}

/**
 * Type of intent target.
 */
enum class IntentTargetType {
    ACTIVITY,
    SERVICE,
    BROADCAST
}
