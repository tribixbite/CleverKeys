package tribixbite.keyboard2

/**
 * Minimal stub for Modmap to resolve KeyboardData dependencies
 * TODO: Implement full functionality
 */
data class Modmap(
    val entries: Map<String, String> = emptyMap()
) {
    companion object {
        val EMPTY = Modmap()

        fun parse(data: String): Modmap {
            // TODO: Implement proper parsing
            return EMPTY
        }
    }
}