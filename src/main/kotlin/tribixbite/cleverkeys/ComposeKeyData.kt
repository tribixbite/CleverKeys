package tribixbite.cleverkeys

import android.content.Context
import java.io.DataInputStream

/**
 * Generated compose key data for CleverKeys.
 *
 * THIS FILE IS AUTO-GENERATED - DO NOT EDIT MANUALLY
 * Run: python3 scripts/generate_compose_bin.py
 *
 * Source: srcs/compose/ (JSON files and compose/ directory)
 * Data loaded from assets/compose_data.bin at runtime to avoid JVM 64KB method limit.
 */
object ComposeKeyData {

    private var _states: CharArray? = null
    private var _edges: IntArray? = null

    val states: CharArray
        get() = _states ?: throw IllegalStateException("ComposeKeyData not initialized. Call initialize(context) first.")

    val edges: IntArray
        get() = _edges ?: throw IllegalStateException("ComposeKeyData not initialized. Call initialize(context) first.")

    fun initialize(context: Context) {
        if (_states != null) return

        try {
            context.assets.open("compose_data.bin").use { inputStream ->
                DataInputStream(inputStream).use { dis ->
                    val size = dis.readInt()
                    val statesArray = CharArray(size)
                    for (i in 0 until size) {
                        statesArray[i] = dis.readUnsignedShort().toChar()
                    }
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
    const val ACCENT_MACRON_BELOW = 824
    const val ACCENT_OGONEK = 843
    const val ACCENT_ORDINAL = 855
    const val ACCENT_RING = 878
    const val ACCENT_RING_BELOW = 890
    const val ACCENT_SLASH = 893
    const val ACCENT_SUBSCRIPT = 933
    const val ACCENT_SUPERSCRIPT = 1010
    const val ACCENT_TILDE = 1166
    const val ACCENT_TREMA = 1194
    const val compose = 1292
    const val fn = 7705
    const val NUMPAD_BENGALI = 8296
    const val NUMPAD_DEVANAGARI = 8317
    const val NUMPAD_GUJARATI = 8338
    const val NUMPAD_HINDU = 8359
    const val NUMPAD_KANNADA = 8380
    const val NUMPAD_PERSIAN = 8401
    const val NUMPAD_TAMIL = 8422
    const val shift = 8443
}
