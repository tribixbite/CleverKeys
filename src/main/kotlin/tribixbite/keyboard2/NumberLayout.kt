package tribixbite.keyboard2

/**
 * Number layout enumeration
 */
enum class NumberLayout {
    PIN, NUMBER, NUMPAD;

    companion object {
        fun fromString(name: String): NumberLayout {
            return when (name.lowercase()) {
                "pin" -> PIN
                "number" -> NUMBER
                "numpad" -> NUMPAD
                else -> PIN
            }
        }

        @Deprecated("Use fromString instead", ReplaceWith("fromString(name)"))
        fun of_string(name: String): NumberLayout = fromString(name)
    }
}