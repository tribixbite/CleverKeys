package tribixbite.keyboard2

import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.math.max
import tribixbite.keyboard2.R

/**
 * Modern Kotlin representation of keyboard layout data
 * Replaces complex Java XML parsing system with type-safe data classes
 */
data class KeyboardData(
    val rows: List<Row>,
    val keysWidth: Float,
    val keysHeight: Float,
    val modmap: Modmap? = null,
    val script: String? = null,
    val numpadScript: String? = null,
    val name: String? = null,
    val bottomRow: Boolean = true,
    val embeddedNumberRow: Boolean = false,
    val localeExtraKeys: Boolean = true
) {

    // Cached key position mapping
    private var _keyPositions: Map<KeyValue, KeyPos>? = null

    /**
     * Apply a transformation to all keys in the keyboard
     */
    fun mapKeys(transform: (Key) -> Key): KeyboardData {
        val newRows = rows.map { row -> row.mapKeys(transform) }
        return copy(rows = newRows, keysWidth = computeMaxWidth(newRows))
    }

    /**
     * Add extra keys to the keyboard at preferred positions
     */
    fun addExtraKeys(extraKeys: Map<KeyValue, PreferredPos>): KeyboardData {
        val unplacedKeys = mutableListOf<KeyValue>()
        val mutableRows = rows.map { it.copy() }.toMutableList()

        for ((keyValue, preferredPos) in extraKeys) {
            if (!addKeyToPreferredPos(mutableRows, keyValue, preferredPos)) {
                unplacedKeys.add(keyValue)
            }
        }

        // Place remaining keys anywhere possible
        for (keyValue in unplacedKeys) {
            addKeyToPreferredPos(mutableRows, keyValue, PreferredPos.ANYWHERE)
        }

        return copy(rows = mutableRows, keysWidth = computeMaxWidth(mutableRows))
    }

    /**
     * Add a number pad to the keyboard
     */
    fun addNumPad(numPad: KeyboardData): KeyboardData {
        val extendedRows = mutableListOf<Row>()
        val numPadIterator = numPad.rows.iterator()

        for (row in rows) {
            val keys = row.keys.toMutableList()
            if (numPadIterator.hasNext()) {
                val numPadRow = numPadIterator.next()
                if (numPadRow.keys.isNotEmpty()) {
                    val firstNumPadShift = 0.5f + keysWidth - row.keysWidth
                    keys.add(numPadRow.keys[0].withShift(firstNumPadShift))
                    keys.addAll(numPadRow.keys.drop(1))
                }
            }
            extendedRows.add(Row(keys, row.height, row.shift))
        }

        return copy(rows = extendedRows)
    }

    /**
     * Insert a row at the specified index
     */
    fun insertRow(row: Row, index: Int): KeyboardData {
        val newRows = rows.toMutableList()
        newRows.add(index, row.updateWidth(keysWidth))
        return copy(rows = newRows)
    }

    /**
     * Find a key containing the specified value
     */
    fun findKeyWithValue(keyValue: KeyValue): Key? {
        val pos = getKeys()[keyValue] ?: return null
        if (pos.row >= rows.size) return null
        return rows[pos.row].getKeyAtPos(pos)
    }

    /**
     * Get cached mapping of all key values to their positions
     */
    fun getKeys(): Map<KeyValue, KeyPos> {
        return _keyPositions ?: run {
            val positions = buildMap {
                rows.forEachIndexed { rowIndex, row ->
                    row.getKeys(this, rowIndex)
                }
            }
            _keyPositions = positions
            positions
        }
    }

    private fun addKeyToPreferredPos(rows: MutableList<Row>, keyValue: KeyValue, pos: PreferredPos): Boolean {
        // Try to place next to a specific key
        pos.nextTo?.let { nextToKey ->
            val nextToPos = getKeys()[nextToKey]
            if (nextToPos != null) {
                for (position in pos.positions) {
                    if ((position.row == -1 || position.row == nextToPos.row) &&
                        (position.col == -1 || position.col == nextToPos.col) &&
                        addKeyToPos(rows, keyValue, nextToPos.withDir(position.dir))) {
                        return true
                    }
                }
                if (addKeyToPos(rows, keyValue, nextToPos.withDir(-1))) {
                    return true
                }
            }
        }

        // Try preferred positions
        for (position in pos.positions) {
            if (addKeyToPos(rows, keyValue, position)) {
                return true
            }
        }

        return false
    }

    private fun addKeyToPos(rows: MutableList<Row>, keyValue: KeyValue, pos: KeyPos): Boolean {
        val rowRange = if (pos.row == -1) 0 until rows.size else pos.row..minOf(pos.row, rows.size - 1)

        for (rowIndex in rowRange) {
            val row = rows[rowIndex]
            val colRange = if (pos.col == -1) 0 until row.keys.size else pos.col..minOf(pos.col, row.keys.size - 1)

            for (colIndex in colRange) {
                val key = row.keys[colIndex]
                val dirRange = if (pos.dir == -1) 1..8 else pos.dir..pos.dir

                for (dirIndex in dirRange) {
                    if (key.getKeyValue(dirIndex) == null) {
                        val newKey = key.withKeyValue(dirIndex, keyValue)
                        val newKeys = row.keys.toMutableList()
                        newKeys[colIndex] = newKey
                        rows[rowIndex] = row.copy(keys = newKeys)
                        return true
                    }
                }
            }
        }

        return false
    }

    /**
     * Row of keys in the keyboard
     */
    data class Row(
        val keys: List<Key>,
        val height: Float,
        val shift: Float
    ) {
        val keysWidth: Float = keys.sumOf { (it.width + it.shift).toDouble() }.toFloat()

        fun copy(): Row = Row(keys.map { it.copy() }, height, shift)

        fun getKeys(destination: MutableMap<KeyValue, KeyPos>, rowIndex: Int) {
            keys.forEachIndexed { colIndex, key ->
                key.getKeys(destination, rowIndex, colIndex)
            }
        }

        fun mapKeys(transform: (Key) -> Key): Row {
            return copy(keys = keys.map(transform))
        }

        /**
         * Scale row width to match target width
         */
        fun updateWidth(newWidth: Float): Row {
            val scale = newWidth / keysWidth
            return mapKeys { it.scaleWidth(scale) }
        }

        fun getKeyAtPos(pos: KeyPos): Key? {
            return keys.getOrNull(pos.col)
        }
    }

    /**
     * Individual key in the keyboard supporting multiple key values per direction
     */
    data class Key(
        val keys: Array<KeyValue?>, // Index 0-8 for 9 directional positions
        val anticircle: KeyValue? = null,
        val keysFlags: Int = 0,
        val width: Float,
        val shift: Float,
        val indication: String? = null
    ) {

        companion object {
            const val F_LOC = 1
            const val ALL_FLAGS = F_LOC

            val EMPTY = Key(
                keys = Array(9) { null },
                anticircle = null,
                keysFlags = 0,
                width = 1f,
                shift = 1f,
                indication = null
            )
        }

        init {
            require(keys.size == 9) { "Key must have exactly 9 directional positions" }
        }

        /**
         * Key layout:
         *  1 7 2
         *  5 0 6
         *  3 8 4
         */
        fun getKeyValue(index: Int): KeyValue? = keys.getOrNull(index)

        fun withKeyValue(index: Int, keyValue: KeyValue): Key {
            val newKeys = keys.copyOf()
            newKeys[index] = keyValue
            val newFlags = keysFlags and (ALL_FLAGS shl index).inv()
            return copy(keys = newKeys, keysFlags = newFlags)
        }

        fun withShift(newShift: Float): Key = copy(shift = newShift)

        fun scaleWidth(scale: Float): Key = copy(width = width * scale)

        fun hasValue(keyValue: KeyValue): Boolean = keys.any { it == keyValue }

        fun keyHasFlag(index: Int, flag: Int): Boolean {
            return (keysFlags and (flag shl index)) != 0
        }

        fun getKeys(destination: MutableMap<KeyValue, KeyPos>, row: Int, col: Int) {
            keys.forEachIndexed { index, keyValue ->
                keyValue?.let { destination[it] = KeyPos(row, col, index) }
            }
        }

        fun copy(): Key = Key(keys.copyOf(), anticircle, keysFlags, width, shift, indication)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key) return false
            return keys.contentEquals(other.keys) &&
                   anticircle == other.anticircle &&
                   keysFlags == other.keysFlags &&
                   width == other.width &&
                   shift == other.shift &&
                   indication == other.indication
        }

        override fun hashCode(): Int {
            var result = keys.contentHashCode()
            result = 31 * result + (anticircle?.hashCode() ?: 0)
            result = 31 * result + keysFlags
            result = 31 * result + width.hashCode()
            result = 31 * result + shift.hashCode()
            result = 31 * result + (indication?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * Position of a key in the layout
     */
    data class KeyPos(
        val row: Int,
        val col: Int,
        val dir: Int
    ) {
        fun withDir(newDir: Int): KeyPos = copy(dir = newDir)
    }

    /**
     * Preferred position specification for dynamic key placement
     */
    data class PreferredPos(
        val nextTo: KeyValue? = null,
        val positions: Array<KeyPos> = ANYWHERE_POSITIONS
    ) {

        companion object {
            private val ANYWHERE_POSITIONS = arrayOf(KeyPos(-1, -1, -1))

            val DEFAULT = PreferredPos(
                positions = arrayOf(
                    KeyPos(1, -1, 4),
                    KeyPos(1, -1, 3),
                    KeyPos(2, -1, 2),
                    KeyPos(2, -1, 1)
                )
            )

            val ANYWHERE = PreferredPos()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PreferredPos) return false
            return nextTo == other.nextTo && positions.contentEquals(other.positions)
        }

        override fun hashCode(): Int {
            var result = nextTo?.hashCode() ?: 0
            result = 31 * result + positions.contentHashCode()
            return result
        }
    }

    companion object {
        // Layout cache for performance
        private val layoutCache = mutableMapOf<Int, KeyboardData?>()

        /**
         * Load keyboard layout from XML resource
         */
        fun load(resources: Resources, resourceId: Int): KeyboardData? {
            return layoutCache.getOrPut(resourceId) {
                try {
                    val parser = resources.getXml(resourceId)
                    parseKeyboard(parser).also { parser.close() }
                } catch (e: Exception) {
                    Logs.e("KeyboardData", "Failed to load layout id $resourceId", e)
                    null
                }
            }
        }

        /**
         * Load keyboard layout from XML string
         */
        fun loadString(source: String): KeyboardData? {
            return try {
                loadStringExn(source)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Load keyboard layout from XML string with exception on error
         */
        fun loadStringExn(source: String): KeyboardData {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(source))
            return parseKeyboard(parser)
        }

        /**
         * Load a row from XML resource
         */
        fun loadRow(resources: Resources, resourceId: Int): Row {
            return parseRow(resources.getXml(resourceId))
        }

        /**
         * Load number pad layout
         */
        fun loadNumPad(resources: Resources): KeyboardData {
            val resourceId = resources.getIdentifier("numpad", "xml", "tribixbite.keyboard2")
            return parseKeyboard(resources.getXml(resourceId))
        }

        private fun parseKeyboard(parser: XmlPullParser): KeyboardData {
            if (!expectTag(parser, "keyboard")) {
                throw parseError(parser, "Expected tag <keyboard>")
            }

            val bottomRow = attributeBool(parser, "bottom_row", true)
            val embeddedNumberRow = attributeBool(parser, "embedded_number_row", false)
            val localeExtraKeys = attributeBool(parser, "locale_extra_keys", true)
            val specifiedWidth = attributeFloat(parser, "width", 0f)
            val script = parser.getAttributeValue(null, "script")?.takeIf { it.isNotEmpty() }
            val numpadScript = parser.getAttributeValue(null, "numpad_script")?.takeIf { it.isNotEmpty() } ?: script
            val name = parser.getAttributeValue(null, "name")

            if (script?.isEmpty() == true) {
                throw parseError(parser, "'script' attribute cannot be empty")
            }
            if (parser.getAttributeValue(null, "numpad_script")?.isEmpty() == true) {
                throw parseError(parser, "'numpad_script' attribute cannot be empty")
            }

            val rows = mutableListOf<Row>()
            var modmap: Modmap? = null

            while (nextTag(parser)) {
                when (parser.name) {
                    "row" -> rows.add(parseRow(parser))
                    "modmap" -> modmap = parseModmap(parser)
                    else -> throw parseError(parser, "Unexpected tag <${parser.name}>")
                }
            }

            val keysWidth = if (specifiedWidth > 0f) specifiedWidth else computeMaxWidth(rows)
            val keysHeight = rows.sumOf { it.height.toDouble() }.toFloat()

            return KeyboardData(
                rows = rows,
                keysWidth = keysWidth,
                keysHeight = keysHeight,
                modmap = modmap,
                script = script,
                numpadScript = numpadScript,
                name = name,
                bottomRow = bottomRow,
                embeddedNumberRow = embeddedNumberRow,
                localeExtraKeys = localeExtraKeys
            )
        }

        private fun parseRow(parser: XmlPullParser): Row {
            val keys = mutableListOf<Key>()
            val height = attributeFloat(parser, "height", 1f)
            val shift = attributeFloat(parser, "shift", 0f)
            val scale = attributeFloat(parser, "scale", 0f)

            while (expectTag(parser, "key")) {
                keys.add(parseKey(parser))
            }

            val row = Row(keys, height, shift)
            return if (scale > 0f) row.updateWidth(scale) else row
        }

        private fun parseKey(parser: XmlPullParser): Key {
            val keys = Array<KeyValue?>(9) { null }
            var keysFlags = 0

            // Parse key values for all 9 positions
            keysFlags = keysFlags or parseKeyAttr(parser, getKeyAttr(parser, "key0", "c"), keys, 0)
            keysFlags = keysFlags or parseKeyAttr(parser, getKeyAttr(parser, "key1", "nw"), keys, 1)
            keysFlags = keysFlags or parseKeyAttr(parser, getKeyAttr(parser, "key2", "ne"), keys, 2)
            keysFlags = keysFlags or parseKeyAttr(parser, getKeyAttr(parser, "key3", "sw"), keys, 3)
            keysFlags = keysFlags or parseKeyAttr(parser, getKeyAttr(parser, "key4", "se"), keys, 4)
            keysFlags = keysFlags or parseKeyAttr(parser, getKeyAttr(parser, "key5", "w"), keys, 5)
            keysFlags = keysFlags or parseKeyAttr(parser, getKeyAttr(parser, "key6", "e"), keys, 6)
            keysFlags = keysFlags or parseKeyAttr(parser, getKeyAttr(parser, "key7", "n"), keys, 7)
            keysFlags = keysFlags or parseKeyAttr(parser, getKeyAttr(parser, "key8", "s"), keys, 8)

            val anticircle = parseNonlocKeyAttr(parser, "anticircle")
            val width = attributeFloat(parser, "width", 1f)
            val shift = attributeFloat(parser, "shift", 0f)
            val indication = parser.getAttributeValue(null, "indication")

            // Skip to end tag
            while (parser.next() != XmlPullParser.END_TAG) {
                // Skip content
            }

            return Key(keys, anticircle, keysFlags, width, shift, indication)
        }

        private fun parseModmap(parser: XmlPullParser): Modmap {
            val modmap = Modmap()

            while (nextTag(parser)) {
                val modifier = when (parser.name) {
                    "shift" -> Modmap.Modifier.SHIFT
                    "fn" -> Modmap.Modifier.FN
                    "ctrl" -> Modmap.Modifier.CTRL
                    else -> throw parseError(parser, "Expecting tag <shift>, <fn>, or <ctrl>, got <${parser.name}>")
                }
                parseModmapMapping(parser, modmap, modifier)
            }

            return modmap
        }

        private fun parseModmapMapping(parser: XmlPullParser, modmap: Modmap, modifier: Modmap.Modifier) {
            val keyA = KeyValue.getKeyByName(parser.getAttributeValue(null, "a"))
            val keyB = KeyValue.getKeyByName(parser.getAttributeValue(null, "b"))

            while (parser.next() != XmlPullParser.END_TAG) {
                // Skip content
            }

            modmap.addMapping(modifier, keyA, keyB)
        }

        private fun getKeyAttr(parser: XmlPullParser, synonym1: String, synonym2: String): String? {
            val value1 = parser.getAttributeValue(null, synonym1)
            val value2 = parser.getAttributeValue(null, synonym2)

            if (value1 != null && value2 != null) {
                throw parseError(parser, "'$synonym1' and '$synonym2' are synonyms and cannot be used together")
            }

            return value1 ?: value2
        }

        private fun parseKeyAttr(parser: XmlPullParser, keyValue: String?, keys: Array<KeyValue?>, index: Int): Int {
            if (keyValue == null) return 0

            var flags = 0
            var processedValue = keyValue

            if (keyValue.startsWith("loc ")) {
                flags = flags or Key.F_LOC
                processedValue = keyValue.substring(4)
            }

            keys[index] = KeyValue.getKeyByName(processedValue)
            return flags shl index
        }

        private fun parseNonlocKeyAttr(parser: XmlPullParser, attrName: String): KeyValue? {
            val name = parser.getAttributeValue(null, attrName) ?: return null
            return KeyValue.getKeyByName(name)
        }

        private fun computeMaxWidth(rows: List<Row>): Float {
            return rows.maxOfOrNull { it.keysWidth } ?: 0f
        }

        // XML parsing utilities
        private fun nextTag(parser: XmlPullParser): Boolean {
            var status: Int
            do {
                status = parser.next()
                if (status == XmlPullParser.END_DOCUMENT || status == XmlPullParser.END_TAG) {
                    return false
                }
            } while (status != XmlPullParser.START_TAG)
            return true
        }

        private fun expectTag(parser: XmlPullParser, name: String): Boolean {
            if (!nextTag(parser)) return false

            if (parser.name != name) {
                throw parseError(parser, "Expecting tag <$name>, got <${parser.name}>")
            }

            return true
        }

        private fun attributeBool(parser: XmlPullParser, attr: String, defaultValue: Boolean): Boolean {
            val value = parser.getAttributeValue(null, attr) ?: return defaultValue
            return value == "true"
        }

        private fun attributeFloat(parser: XmlPullParser, attr: String, defaultValue: Float): Float {
            val value = parser.getAttributeValue(null, attr) ?: return defaultValue
            return value.toFloat()
        }

        private fun parseError(parser: XmlPullParser, message: String): Exception {
            return Exception("Parse error at line ${parser.lineNumber}: $message")
        }

        /**
         * Create a simple QWERTY layout for testing
         */
        fun createDefaultQwerty(): KeyboardData {
            val qwertyRows = listOf(
                listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                listOf("z", "x", "c", "v", "b", "n", "m")
            )

            val rows = qwertyRows.map { rowChars ->
                val keys = rowChars.map { char ->
                    val keyValues = Array<KeyValue?>(9) { null }
                    keyValues[0] = KeyValue.makeCharKey(char[0])
                    Key(keyValues, width = 1f, shift = 0f)
                }
                Row(keys, height = 1f, shift = 0f)
            }

            return KeyboardData(
                rows = rows,
                keysWidth = computeMaxWidth(rows),
                keysHeight = rows.sumOf { it.height.toDouble() }.toFloat(),
                name = "qwerty"
            )
        }
    }
}

/**
 * Preferred position for placing extra keys on the keyboard.
 *
 * Used by ExtraKeys system to specify where dynamically added keys should appear.
 */
data class PreferredPos(
    /** Place this key next to the specified key (null = no preference) */
    var nextTo: KeyValue? = null
) {
    companion object {
        /** Default position (no specific preference) */
        val DEFAULT = PreferredPos(nextTo = null)

        /** Place anywhere possible */
        val ANYWHERE = PreferredPos(nextTo = null)
    }
}

/**
 * Interface for key transformation operations
 */
fun interface MapKey {
    fun apply(key: KeyboardData.Key): KeyboardData.Key
}

/**
 * Abstract interface for mapping key values with localization support
 */
abstract class MapKeyValues : MapKey {
    abstract fun apply(keyValue: KeyValue, localized: Boolean): KeyValue

    override fun apply(key: KeyboardData.Key): KeyboardData.Key {
        val newKeys = Array<KeyValue?>(key.keys.size) { index ->
            key.keys[index]?.let { keyValue ->
                apply(keyValue, key.keyHasFlag(index, KeyboardData.Key.F_LOC))
            }
        }
        return key.copy(keys = newKeys)
    }
}