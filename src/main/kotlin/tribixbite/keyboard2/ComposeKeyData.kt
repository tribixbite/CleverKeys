package tribixbite.keyboard2

import android.content.Context
import java.io.DataInputStream

/**
 * Generated compose key data for CleverKeys.
 *
 * THIS FILE IS AUTO-GENERATED - DO NOT EDIT MANUALLY
 * Run: python3 generate_compose_data.py
 *
 * Source: Unexpected-Keyboard/srcs/compose/\*.json and compose/ directory
 * Generator: generate_compose_data.py (modified from compile.py)
 *
 * Contains 8659 states for 33 entry points.
 * Data loaded from assets/compose_data.bin at runtime to avoid JVM 64KB method limit.
 */
object ComposeKeyData {

    private var _states: CharArray? = null
    private var _edges: IntArray? = null

    /**
     * State array representing compose sequence states and transitions.
     * Loaded lazily from binary resource file.
     */
    val states: CharArray
        get() = _states ?: throw IllegalStateException("ComposeKeyData not initialized. Call initialize(context) first.")

    /**
     * Edge array representing transition states and state sizes.
     * Must have the same length as states array.
     */
    val edges: IntArray
        get() = _edges ?: throw IllegalStateException("ComposeKeyData not initialized. Call initialize(context) first.")

    /**
     * Initialize compose data from binary resource file.
     * Must be called before accessing states/edges arrays.
     */
    fun initialize(context: Context) {
        if (_states != null) return  // Already initialized

        try {
            context.assets.open("compose_data.bin").use { inputStream ->
                DataInputStream(inputStream).use { dis ->
                    // Read array size
                    val size = dis.readInt()

                    // Read states array (shorts → chars)
                    val statesArray = CharArray(size)
                    for (i in 0 until size) {
                        statesArray[i] = dis.readUnsignedShort().toChar()
                    }

                    // Read edges array (ints)
                    val edgesArray = IntArray(size)
                    for (i in 0 until size) {
                        edgesArray[i] = dis.readInt()
                    }

                    _states = statesArray
                    _edges = edgesArray
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to load compose data from assets", e)
        }
    }

    /**
     * Entry point constants for each compose mode
     */
    const val ACCENT_AIGU = 1
    const val ACCENT_ARROWS = 130
    const val ACCENT_BAR = 153
    const val ACCENT_BOX = 208
    const val ACCENT_CARON = 231
    const val ACCENT_CEDILLE = 304
    const val ACCENT_CIRCONFLEXE = 330
    const val ACCENT_DOT_ABOVE = 412
    const val ACCENT_DOT_BELOW = 541
    const val ACCENT_DOUBLE_AIGU = 596
    const val ACCENT_DOUBLE_GRAVE = 625
    const val ACCENT_GRAVE = 664
    const val ACCENT_HOOK_ABOVE = 730
    const val ACCENT_HORN = 752
    const val ACCENT_MACRON = 769
    const val ACCENT_OGONEK = 824
    const val ACCENT_ORDINAL = 836
    const val ACCENT_RING = 859
    const val ACCENT_SLASH = 871
    const val ACCENT_SUBSCRIPT = 911
    const val ACCENT_SUPERSCRIPT = 988
    const val ACCENT_TILDE = 1144
    const val ACCENT_TREMA = 1172
    const val compose = 1270
    const val fn = 7683
    const val NUMPAD_BENGALI = 8279
    const val NUMPAD_DEVANAGARI = 8300
    const val NUMPAD_GUJARATI = 8321
    const val NUMPAD_HINDU = 8342
    const val NUMPAD_KANNADA = 8363
    const val NUMPAD_PERSIAN = 8384
    const val NUMPAD_TAMIL = 8405
    const val shift = 8426

    /**
     * Validate the integrity of the compose key data.
     */
    fun validateData(): Boolean {
        try {
            if (states.size != edges.size) return false

            var i = 0
            while (i < states.size) {
                val header = states[i].code
                val length = edges[i]

                when {
                    header == 0 -> {
                        if (length < 1 || i + length > states.size) return false
                        i += length
                    }
                    header == 0xFFFF -> {
                        if (length < 2 || i + length > states.size) return false
                        i += length
                    }
                    header > 0 -> {
                        if (length != 1) return false
                        i++
                    }
                    else -> return false
                }
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Get statistics about the compose key data.
     */
    fun getDataStatistics(): ComposeDataStatistics {
        var intermediateStates = 0
        var characterFinalStates = 0
        var stringFinalStates = 0
        var totalTransitions = 0

        var i = 0
        while (i < states.size) {
            val header = states[i].code
            val length = edges[i]

            when {
                header == 0 -> {
                    intermediateStates++
                    totalTransitions += length - 1
                    i += length
                }
                header == 0xFFFF -> {
                    stringFinalStates++
                    i += length
                }
                header > 0 -> {
                    characterFinalStates++
                    i++
                }
                else -> i++
            }
        }

        return ComposeDataStatistics(
            totalStates = intermediateStates + characterFinalStates + stringFinalStates,
            intermediateStates = intermediateStates,
            characterFinalStates = characterFinalStates,
            stringFinalStates = stringFinalStates,
            totalTransitions = totalTransitions,
            dataSize = states.size
        )
    }

    data class ComposeDataStatistics(
        val totalStates: Int,
        val intermediateStates: Int,
        val characterFinalStates: Int,
        val stringFinalStates: Int,
        val totalTransitions: Int,
        val dataSize: Int
    )
}

