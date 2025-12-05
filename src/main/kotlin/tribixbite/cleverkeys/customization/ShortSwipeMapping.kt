package tribixbite.cleverkeys.customization

/**
 * Represents a single short swipe mapping for a key in a specific direction.
 *
 * @property keyCode The key identifier (lowercase letter, e.g., "a", "b", or special keys like "space")
 * @property direction The swipe direction this mapping applies to
 * @property displayText The text shown on the key's sub-label (max 4 characters for display)
 * @property actionType The type of action to execute
 * @property actionValue The action data (text content, command name, or key event code)
 */
data class ShortSwipeMapping(
    val keyCode: String,
    val direction: SwipeDirection,
    val displayText: String,
    val actionType: ActionType,
    val actionValue: String
) {
    init {
        require(keyCode.isNotEmpty()) { "keyCode cannot be empty" }
        require(displayText.length <= MAX_DISPLAY_LENGTH) {
            "displayText must be at most $MAX_DISPLAY_LENGTH characters"
        }
        require(actionValue.length <= MAX_ACTION_LENGTH) {
            "actionValue must be at most $MAX_ACTION_LENGTH characters"
        }
        // Validate command exists if action type is COMMAND
        if (actionType == ActionType.COMMAND) {
            require(AvailableCommand.fromString(actionValue) != null) {
                "Invalid command: $actionValue. Must be one of: ${AvailableCommand.entries.map { it.name }}"
            }
        }
    }

    companion object {
        /** Maximum characters for display text on key sub-label */
        const val MAX_DISPLAY_LENGTH = 4

        /** Maximum characters for action value (text input) */
        const val MAX_ACTION_LENGTH = 100

        /**
         * Create a text input mapping.
         */
        fun textInput(
            keyCode: String,
            direction: SwipeDirection,
            displayText: String,
            text: String
        ): ShortSwipeMapping = ShortSwipeMapping(
            keyCode = keyCode.lowercase(),
            direction = direction,
            displayText = displayText.take(MAX_DISPLAY_LENGTH),
            actionType = ActionType.TEXT,
            actionValue = text.take(MAX_ACTION_LENGTH)
        )

        /**
         * Create a command mapping.
         */
        fun command(
            keyCode: String,
            direction: SwipeDirection,
            displayText: String,
            command: AvailableCommand
        ): ShortSwipeMapping = ShortSwipeMapping(
            keyCode = keyCode.lowercase(),
            direction = direction,
            displayText = displayText.take(MAX_DISPLAY_LENGTH),
            actionType = ActionType.COMMAND,
            actionValue = command.name
        )

        /**
         * Create a key event mapping.
         */
        fun keyEvent(
            keyCode: String,
            direction: SwipeDirection,
            displayText: String,
            keyEventCode: Int
        ): ShortSwipeMapping = ShortSwipeMapping(
            keyCode = keyCode.lowercase(),
            direction = direction,
            displayText = displayText.take(MAX_DISPLAY_LENGTH),
            actionType = ActionType.KEY_EVENT,
            actionValue = keyEventCode.toString()
        )
    }

    /**
     * Get the command if this is a COMMAND type mapping.
     */
    fun getCommand(): AvailableCommand? {
        return if (actionType == ActionType.COMMAND) {
            AvailableCommand.fromString(actionValue)
        } else null
    }

    /**
     * Get the key event code if this is a KEY_EVENT type mapping.
     */
    fun getKeyEventCode(): Int? {
        return if (actionType == ActionType.KEY_EVENT) {
            actionValue.toIntOrNull()
        } else null
    }

    /**
     * Create a unique key for HashMap storage.
     * Format: "keyCode:direction" e.g., "a:NE"
     */
    fun toStorageKey(): String = "${keyCode.lowercase()}:${direction.name}"
}

/**
 * Storage model for JSON serialization.
 * Groups mappings by key code for efficient storage format.
 */
data class ShortSwipeCustomizations(
    val version: Int = CURRENT_VERSION,
    val mappings: Map<String, Map<String, DirectionMapping>> = emptyMap()
) {
    /**
     * Convert to flat list of ShortSwipeMapping objects.
     */
    fun toMappingList(): List<ShortSwipeMapping> {
        return mappings.flatMap { (keyCode, directions) ->
            directions.mapNotNull { (directionName, mapping) ->
                val direction = SwipeDirection.entries.find { it.name == directionName }
                direction?.let {
                    ShortSwipeMapping(
                        keyCode = keyCode,
                        direction = it,
                        displayText = mapping.displayText,
                        actionType = ActionType.fromString(mapping.actionType),
                        actionValue = mapping.actionValue
                    )
                }
            }
        }
    }

    companion object {
        const val CURRENT_VERSION = 1

        /**
         * Convert from flat list to storage format.
         */
        fun fromMappingList(mappings: List<ShortSwipeMapping>): ShortSwipeCustomizations {
            val grouped = mappings.groupBy { it.keyCode.lowercase() }
                .mapValues { (_, keyMappings) ->
                    keyMappings.associate { mapping ->
                        mapping.direction.name to DirectionMapping(
                            displayText = mapping.displayText,
                            actionType = mapping.actionType.name,
                            actionValue = mapping.actionValue
                        )
                    }
                }
            return ShortSwipeCustomizations(mappings = grouped)
        }
    }
}

/**
 * JSON-friendly model for a single direction mapping.
 */
data class DirectionMapping(
    val displayText: String,
    val actionType: String,
    val actionValue: String
)
