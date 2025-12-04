package tribixbite.cleverkeys

enum class NumberLayout {
    PIN,
    NUMBER,
    NORMAL;

    companion object {
        @JvmStatic
        fun of_string(name: String): NumberLayout {
            return when (name) {
                "number" -> NUMBER
                "normal" -> NORMAL
                "pin" -> PIN
                else -> PIN
            }
        }
    }
}
