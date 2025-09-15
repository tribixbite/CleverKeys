package tribixbite.keyboard2

/**
 * Extra keys management
 */
enum class ExtraKeys {
    NONE, CUSTOM, FUNCTION;
    
    companion object {
        fun fromString(value: String): ExtraKeys {
            return when (value) {
                "custom" -> CUSTOM
                "function" -> FUNCTION
                else -> NONE
            }
        }
    }
}