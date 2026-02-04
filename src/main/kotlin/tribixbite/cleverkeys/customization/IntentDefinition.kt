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
