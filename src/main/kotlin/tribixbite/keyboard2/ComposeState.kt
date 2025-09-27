package tribixbite.keyboard2

/**
 * Minimal stub for ComposeState to resolve KeyModifier dependencies
 * TODO: Implement full compose state functionality
 */
data class ComposeState(
    val isActive: Boolean = false,
    val pendingCharacter: Char? = null
) {
    companion object {
        val EMPTY = ComposeState()
    }
}