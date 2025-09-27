package tribixbite.keyboard2

/**
 * Key modifier system for handling shift, alt, ctrl states
 * Kotlin implementation with sealed classes and state management
 */
class KeyModifier {
    
    /**
     * Modifier types
     */
    sealed class Modifier(val flag: Int) {
        object Shift : Modifier(1)
        object Ctrl : Modifier(2) 
        object Alt : Modifier(4)
        object Meta : Modifier(8)
        object Fn : Modifier(16)
        
        companion object {
            fun fromFlag(flag: Int): List<Modifier> {
                val modifiers = mutableListOf<Modifier>()
                if (flag and Shift.flag != 0) modifiers.add(Shift)
                if (flag and Ctrl.flag != 0) modifiers.add(Ctrl)
                if (flag and Alt.flag != 0) modifiers.add(Alt)
                if (flag and Meta.flag != 0) modifiers.add(Meta)
                if (flag and Fn.flag != 0) modifiers.add(Fn)
                return modifiers
            }
        }
    }
    
    /**
     * Modifier state tracking
     */
    data class ModifierState(
        val activeModifiers: Set<Modifier> = emptySet(),
        val lockedModifiers: Set<Modifier> = emptySet(),
        val tempModifiers: Set<Modifier> = emptySet()
    ) {
        /**
         * Apply modifier to key value
         */
        fun applyToKey(keyValue: KeyValue): KeyValue {
            return when (keyValue) {
                is KeyValue.CharKey -> {
                    val char = keyValue.char
                    when {
                        activeModifiers.contains(Modifier.Shift) || lockedModifiers.contains(Modifier.Shift) -> {
                            KeyValue.CharKey(char.uppercaseChar())
                        }
                        else -> keyValue
                    }
                }
                else -> keyValue
            }
        }
        
        /**
         * Add modifier
         */
        fun addModifier(modifier: Modifier, isLocked: Boolean = false): ModifierState {
            return if (isLocked) {
                copy(lockedModifiers = lockedModifiers + modifier)
            } else {
                copy(activeModifiers = activeModifiers + modifier)
            }
        }
        
        /**
         * Remove modifier
         */
        fun removeModifier(modifier: Modifier): ModifierState {
            return copy(
                activeModifiers = activeModifiers - modifier,
                lockedModifiers = lockedModifiers - modifier,
                tempModifiers = tempModifiers - modifier
            )
        }
        
        /**
         * Clear temporary modifiers (after key press)
         */
        fun clearTempModifiers(): ModifierState {
            return copy(
                activeModifiers = activeModifiers - tempModifiers,
                tempModifiers = emptySet()
            )
        }
        
        /**
         * Check if modifier is active
         */
        fun isActive(modifier: Modifier): Boolean {
            return modifier in activeModifiers || modifier in lockedModifiers
        }
        
        /**
         * Get combined flag value
         */
        val combinedFlags: Int
            get() = (activeModifiers + lockedModifiers + tempModifiers).fold(0) { acc, mod -> acc or mod.flag }
    }
    
    /**
     * Process dead key combinations
     */
    fun processDeadKey(deadChar: Char, baseChar: Char): KeyValue {
        val combined = when (deadChar) {
            '\'' -> when (baseChar) {
                'a' -> 'á'
                'e' -> 'é'
                'i' -> 'í'
                'o' -> 'ó'
                'u' -> 'ú'
                else -> baseChar
            }
            '`' -> when (baseChar) {
                'a' -> 'à'
                'e' -> 'è'
                'i' -> 'ì'
                'o' -> 'ò'
                'u' -> 'ù'
                else -> baseChar
            }
            '^' -> when (baseChar) {
                'a' -> 'â'
                'e' -> 'ê'
                'i' -> 'î'
                'o' -> 'ô'
                'u' -> 'û'
                else -> baseChar
            }
            '~' -> when (baseChar) {
                'a' -> 'ã'
                'n' -> 'ñ'
                'o' -> 'õ'
                else -> baseChar
            }
            '"' -> when (baseChar) {
                'a' -> 'ä'
                'e' -> 'ë'
                'i' -> 'ï'
                'o' -> 'ö'
                'u' -> 'ü'
                else -> baseChar
            }
            else -> baseChar
        }
        
        return KeyValue.CharKey(combined)
    }
    
    /**
     * Apply compose state or fallback to dead char
     */
    fun applyComposeOrFallback(state: ComposeKey.LegacyComposeSystem.ComposeState, deadChar: Char): KeyValue {
        return state.getResult()?.let { result ->
            KeyValue.StringKey(result)
        } ?: KeyValue.CharKey(deadChar)
    }

    companion object {
        /**
         * Set modmap for keyboard layout - no-op in current implementation
         */
        fun set_modmap(modmap: Any?) {
            // No-op: modmap functionality not implemented in current system
        }

        /**
         * Modify key value with modifiers
         */
        fun modify(keyValue: KeyValue?, mods: Pointers.Modifiers): KeyValue {
            return keyValue ?: KeyValue.CharKey(' ')
        }

        /**
         * Modify key value for long press action
         */
        fun modifyLongPress(keyValue: KeyValue): KeyValue {
            // For long press, return the key as-is or a modified version
            return when (keyValue) {
                is KeyValue.CharKey -> {
                    // Long press on letters could produce uppercase
                    if (keyValue.char.isLowerCase()) {
                        KeyValue.CharKey(keyValue.char.uppercase().first())
                    } else keyValue
                }
                else -> keyValue
            }
        }
    }
}