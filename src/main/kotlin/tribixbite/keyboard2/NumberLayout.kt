package tribixbite.keyboard2

/**
 * Number layout enumeration
 */
enum class NumberLayout {
    PIN, NUMBER, NUMPAD;
    
    companion object {
        fun of_string(name: String): NumberLayout {
            return when (name) {
                "pin" -> PIN
                "number" -> NUMBER
                "numpad" -> NUMPAD
                else -> PIN
            }
        }
    }
}