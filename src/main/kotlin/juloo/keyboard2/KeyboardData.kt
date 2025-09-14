package juloo.keyboard2

import android.graphics.PointF

/**
 * Keyboard layout data with Kotlin improvements
 * Simplified version of the Java KeyboardData class
 */
data class KeyboardData(
    val name: String,
    val keys: List<List<Key>>,
    val width: Int,
    val height: Int
) {
    
    /**
     * Individual key data
     */
    data class Key(
        val keys: Array<KeyValue?>,
        val width: Float = 1.0f,
        val shift: Float = 0.0f,
        val slider: Boolean = false
    ) {
        // Primary key value
        val keyValue: KeyValue? get() = keys.firstOrNull()
        
        // Get key value for specific modifier state
        fun getKeyValue(modifiers: Int): KeyValue? {
            val index = when {
                modifiers and SHIFT_MASK != 0 && keys.size > 1 -> 1
                modifiers and FN_MASK != 0 && keys.size > 2 -> 2
                else -> 0
            }
            return keys.getOrNull(index)
        }
        
        companion object {
            const val SHIFT_MASK = 1
            const val FN_MASK = 2
        }
        
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Key
            return keys.contentEquals(other.keys) && width == other.width && shift == other.shift
        }
        
        override fun hashCode(): Int {
            var result = keys.contentHashCode()
            result = 31 * result + width.hashCode()
            result = 31 * result + shift.hashCode()
            return result
        }
    }
    
    /**
     * Preferred position for extra keys
     */
    enum class PreferredPos { LEFT, RIGHT, CENTER }
    
    /**
     * Find key at screen coordinates
     */
    fun getKeyAt(x: Float, y: Float, keyWidth: Float, keyHeight: Float): Key? {
        val row = (y / keyHeight).toInt()
        val adjustedX = x - (if (row == 1) keyWidth * 0.5f else 0f) // QWERTY offset
        val col = (adjustedX / keyWidth).toInt()
        
        return keys.getOrNull(row)?.getOrNull(col)
    }
    
    /**
     * Get all character keys for position mapping
     */
    val characterKeys: Map<Char, PointF>
        get() {
            val result = mutableMapOf<Char, PointF>()
            
            keys.forEachIndexed { rowIndex, row ->
                row.forEachIndexed { colIndex, key ->
                    key.keyValue?.let { kv ->
                        if (kv.kind == KeyValue.Kind.Char) {
                            val x = colIndex * 100f + (if (rowIndex == 1) 50f else 0f)
                            val y = rowIndex * 100f
                            result[kv.char] = PointF(x, y)
                        }
                    }
                }
            }
            
            return result
        }
    
    companion object {
        /**
         * Create default QWERTY layout
         */
        fun createDefaultQwerty(): KeyboardData {
            val qwertyRows = arrayOf(
                arrayOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                arrayOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                arrayOf("z", "x", "c", "v", "b", "n", "m")
            )
            
            val keys = qwertyRows.map { row ->
                row.map { char ->
                    Key(arrayOf(KeyValue.makeCharKey(char[0])))
                }
            }
            
            return KeyboardData("qwerty", keys, 10, 3)
        }
    }
}