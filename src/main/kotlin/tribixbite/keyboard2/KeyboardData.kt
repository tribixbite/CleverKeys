package tribixbite.keyboard2

import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Keyboard layout data model.
 *
 * Represents a complete keyboard layout with rows, keys, and metadata.
 * Provides XML parsing functionality and layout manipulation methods.
 */
class KeyboardData private constructor(
    val rows: List<Row>,
    /** Total width of the keyboard. */
    val keysWidth: Float,
    /** Total height of the keyboard. */
    val keysHeight: Float,
    /** Might be null. */
    val modmap: Modmap?,
    /** Might be null. */
    val script: String?,
    /** Might be different from [script]. Might be null. */
    val numpad_script: String?,
    /** The [name] attribute. Might be null. */
    val name: String?,
    /** Whether the bottom row should be added. */
    val bottom_row: Boolean,
    /** Whether the number row is included in the layout and thus another one shouldn't be added. */
    val embedded_number_row: Boolean,
    /** Whether extra keys from [method.xml] should be added to this layout. */
    val locale_extra_keys: Boolean
) {
    /** Position of every keys on the layout, see [getKeys()]. */
    private var _key_pos: Map<KeyValue, KeyPos>? = null

    fun mapKeys(f: MapKey): KeyboardData {
        val rows_ = rows.map { it.mapKeys(f) }
        return KeyboardData(this, rows_)
    }

    /** Add keys from the given iterator into the keyboard. Preferred position is
        specified via [PreferredPos]. */
    fun addExtraKeys(extra_keys: Iterator<Map.Entry<KeyValue, PreferredPos>>): KeyboardData {
        // Keys that couldn't be placed at their preferred position.
        val unplaced_keys = ArrayList<KeyValue>()
        val rows = ArrayList(this.rows)
        while (extra_keys.hasNext()) {
            val kp = extra_keys.next()
            if (!add_key_to_preferred_pos(rows, kp.key, kp.value))
                unplaced_keys.add(kp.key)
        }
        for (kv in unplaced_keys)
            add_key_to_preferred_pos(rows, kv, PreferredPos.ANYWHERE)
        return KeyboardData(this, rows)
    }

    /** Place a key on the keyboard according to its preferred position. Mutates
        [rows]. Returns [false] if it couldn't be placed. */
    private fun add_key_to_preferred_pos(rows: MutableList<Row>, kv: KeyValue, pos: PreferredPos): Boolean {
        if (pos.next_to != null) {
            val next_to_pos = getKeys()[pos.next_to]
            // Use preferred direction if some preferred pos match
            if (next_to_pos != null) {
                for (p in pos.positions) {
                    if ((p.row == -1 || p.row == next_to_pos.row) &&
                        (p.col == -1 || p.col == next_to_pos.col) &&
                        add_key_to_pos(rows, kv, next_to_pos.with_dir(p.dir)))
                        return true
                }
                if (add_key_to_pos(rows, kv, next_to_pos.with_dir(-1)))
                    return true
            }
        }
        for (p in pos.positions) {
            if (add_key_to_pos(rows, kv, p))
                return true
        }
        return false
    }

    /** Place a key on the keyboard. A value of [-1] in one of the coordinate
        means that the key can be placed anywhere in that coordinate, see
        [PreferredPos]. Mutates [rows]. Returns [false] if it couldn't be placed. */
    private fun add_key_to_pos(rows: MutableList<Row>, kv: KeyValue, p: KeyPos): Boolean {
        var i_row = p.row
        var i_row_end = minOf(p.row, rows.size - 1)
        if (p.row == -1) {
            i_row = 0
            i_row_end = rows.size - 1
        }
        while (i_row <= i_row_end) {
            val row = rows[i_row]
            var i_col = p.col
            var i_col_end = minOf(p.col, row.keys.size - 1)
            if (p.col == -1) {
                i_col = 0
                i_col_end = row.keys.size - 1
            }
            while (i_col <= i_col_end) {
                val col = row.keys[i_col]
                var i_dir = p.dir
                var i_dir_end = p.dir
                if (p.dir == -1) {
                    i_dir = 1
                    i_dir_end = 4
                }
                while (i_dir <= i_dir_end) {
                    if (col.getKeyValue(i_dir) == null) {
                        rows[i_row] = row.copy(keys = row.keys.toMutableList().apply {
                            set(i_col, col.withKeyValue(i_dir, kv))
                        })
                        return true
                    }
                    i_dir++
                }
                i_col++
            }
            i_row++
        }
        return false
    }

    fun addNumPad(num_pad: KeyboardData): KeyboardData {
        val extendedRows = ArrayList<Row>()
        val iterNumPadRows = num_pad.rows.iterator()
        for (row in rows) {
            val keys = ArrayList(row.keys)
            if (iterNumPadRows.hasNext()) {
                val numPadRow = iterNumPadRows.next()
                val nps = numPadRow.keys
                if (nps.isNotEmpty()) {
                    val firstNumPadShift = 0.5f + keysWidth - row.keysWidth
                    keys.add(nps[0].withShift(firstNumPadShift))
                    for (i in 1 until nps.size)
                        keys.add(nps[i])
                }
            }
            extendedRows.add(Row(keys, row.height, row.shift))
        }
        return KeyboardData(this, extendedRows)
    }

    /** Insert the given row at the given indice. The row is scaled so that the
        keys already on the keyboard don't change width. */
    fun insert_row(row: Row, i: Int): KeyboardData {
        val rows_ = ArrayList(rows)
        rows_.add(i, row.updateWidth(keysWidth))
        return KeyboardData(this, rows_)
    }

    fun findKeyWithValue(kv: KeyValue): Key? {
        val pos = getKeys()[kv] ?: return null
        if (pos.row >= rows.size) return null
        return rows[pos.row].get_key_at_pos(pos)
    }

    /** This is computed once and cached. */
    fun getKeys(): Map<KeyValue, KeyPos> {
        if (_key_pos == null) {
            val map = HashMap<KeyValue, KeyPos>()
            for (r in rows.indices)
                rows[r].getKeys(map, r)
            _key_pos = map
        }
        return _key_pos!!
    }

    /** Copies the fields of a keyboard, with rows changed. */
    private constructor(src: KeyboardData, rows: List<Row>) : this(
        rows,
        compute_max_width(rows),
        compute_total_height(rows),  // FIXED: compute keysHeight properly
        src.modmap,
        src.script,
        src.numpad_script,
        src.name,
        src.bottom_row,
        src.embedded_number_row,
        src.locale_extra_keys
    )

    data class Row(
        val keys: List<Key>,
        /** Height of the row, without 'shift'. */
        val height: Float,
        /** Extra empty space on the top. */
        val shift: Float
    ) {
        /** Total width of the row. */
        val keysWidth: Float = run {
            var kw = 0f
            for (k in keys) kw += k.width + k.shift
            kw
        }

        fun getKeys(dst: MutableMap<KeyValue, KeyPos>, row: Int) {
            for (c in keys.indices)
                keys[c].getKeys(dst, row, c)
        }

        fun getKeys(row: Int): Map<KeyValue, KeyPos> {
            val dst = HashMap<KeyValue, KeyPos>()
            getKeys(dst, row)
            return dst
        }

        fun mapKeys(f: MapKey): Row {
            val keys_ = keys.map { f.apply(it) }
            return Row(keys_, height, shift)
        }

        /** Change the width of every keys so that the row is 's' units wide. */
        fun updateWidth(newWidth: Float): Row {
            val s = newWidth / keysWidth
            return mapKeys(object : MapKey {
                override fun apply(k: Key): Key = k.scaleWidth(s)
            })
        }

        fun get_key_at_pos(pos: KeyPos): Key? {
            if (pos.col >= keys.size) return null
            return keys[pos.col]
        }

        companion object {
            @JvmStatic
            @Throws(Exception::class)
            fun parse(parser: XmlPullParser): Row {
                val keys = ArrayList<Key>()
                val h = attribute_float(parser, "height", 1f)
                val shift = attribute_float(parser, "shift", 0f)
                val scale = attribute_float(parser, "scale", 0f)
                while (expect_tag(parser, "key"))
                    keys.add(Key.parse(parser))
                var row = Row(keys, maxOf(h, 0.5f), maxOf(shift, 0f))
                if (scale > 0f)
                    row = row.updateWidth(scale)
                return row
            }
        }
    }

    data class Key(
        /**
         *  1 7 2
         *  5 0 6
         *  3 8 4
         */
        val keys: List<KeyValue?>,
        /** Key accessed by the anti-clockwise circle gesture. */
        val anticircle: KeyValue?,
        /** Pack flags for every key values. Flags are: [F_LOC]. */
        internal val keysflags: Int,
        /** Key width in relative unit. */
        val width: Float,
        /** Extra empty space on the left of the key. */
        val shift: Float,
        /** String printed on the keys. It has no other effect. */
        val indication: String?
    ) {
        /** Whether key at [index] as [flag]. */
        fun keyHasFlag(index: Int, flag: Int): Boolean =
            (keysflags and (flag shl index)) != 0

        /** New key with the width multiplied by 's'. */
        fun scaleWidth(s: Float): Key =
            Key(keys, anticircle, keysflags, width * s, shift, indication)

        fun getKeys(dst: MutableMap<KeyValue, KeyPos>, row: Int, col: Int) {
            for (i in keys.indices) {
                val k = keys[i]
                if (k != null)
                    dst[k] = KeyPos(row, col, i)
            }
        }

        fun getKeyValue(i: Int): KeyValue? = keys[i]

        fun withKeyValue(i: Int, kv: KeyValue): Key {
            val ks = keys.toMutableList()
            ks[i] = kv
            val flags = keysflags and (ALL_FLAGS shl i).inv()
            return Key(ks, anticircle, flags, width, shift, indication)
        }

        fun withShift(s: Float): Key =
            Key(keys, anticircle, keysflags, width, s, indication)

        fun hasValue(kv: KeyValue): Boolean {
            for (i in keys.indices) {
                val k = keys[i]
                if (k != null && k == kv)
                    return true
            }
            return false
        }

        companion object {
            /** Whether a key was declared with the 'loc' prefix. */
            const val F_LOC = 1
            const val ALL_FLAGS = F_LOC

            @JvmField
            val EMPTY = Key(List(9) { null }, null, 0, 1f, 1f, null)

            /** Read a key value attribute that have a synonym. Having both synonyms
                present at the same time is an error.
                Returns [null] if the attributes are not present. */
            @JvmStatic
            @Throws(Exception::class)
            private fun get_key_attr(parser: XmlPullParser, syn1: String, syn2: String): String? {
                val name1 = parser.getAttributeValue(null, syn1)
                val name2 = parser.getAttributeValue(null, syn2)
                if (name1 != null && name2 != null)
                    throw error(parser,
                        "'$syn1' and '$syn2' are synonyms and cannot be passed at the same time.")
                return name1 ?: name2
            }

            /** Parse the key description [key_attr] and write into [ks] at [index].
                Returns flags that can be aggregated into the value for [keysflags].
                [key_attr] can be [null] for convenience. */
            @JvmStatic
            @Throws(Exception::class)
            private fun parse_key_attr(
                parser: XmlPullParser,
                key_val: String?,
                ks: Array<KeyValue?>,
                index: Int
            ): Int {
                if (key_val == null) return 0
                var flags = 0
                var key_val_actual = key_val
                val name_loc = stripPrefix(key_val, "loc ")
                if (name_loc != null) {
                    flags = flags or F_LOC
                    key_val_actual = name_loc
                }
                ks[index] = KeyValue.getKeyByName(key_val_actual)
                return flags shl index
            }

            @JvmStatic
            @Throws(Exception::class)
            private fun parse_nonloc_key_attr(parser: XmlPullParser, attr_name: String): KeyValue? {
                val name = parser.getAttributeValue(null, attr_name) ?: return null
                return KeyValue.getKeyByName(name)
            }

            @JvmStatic
            private fun stripPrefix(s: String, prefix: String): String? =
                if (s.startsWith(prefix)) s.substring(prefix.length) else null

            @JvmStatic
            @Throws(Exception::class)
            fun parse(parser: XmlPullParser): Key {
                val ks = arrayOfNulls<KeyValue>(9)
                var keysflags = 0
                keysflags = keysflags or parse_key_attr(parser, get_key_attr(parser, "key0", "c"), ks, 0)
                // Swipe gestures (key1-key8 diagram above), with compass-point synonyms.
                keysflags = keysflags or parse_key_attr(parser, get_key_attr(parser, "key1", "nw"), ks, 1)
                keysflags = keysflags or parse_key_attr(parser, get_key_attr(parser, "key2", "ne"), ks, 2)
                keysflags = keysflags or parse_key_attr(parser, get_key_attr(parser, "key3", "sw"), ks, 3)
                keysflags = keysflags or parse_key_attr(parser, get_key_attr(parser, "key4", "se"), ks, 4)
                keysflags = keysflags or parse_key_attr(parser, get_key_attr(parser, "key5", "w"), ks, 5)
                keysflags = keysflags or parse_key_attr(parser, get_key_attr(parser, "key6", "e"), ks, 6)
                keysflags = keysflags or parse_key_attr(parser, get_key_attr(parser, "key7", "n"), ks, 7)
                keysflags = keysflags or parse_key_attr(parser, get_key_attr(parser, "key8", "s"), ks, 8)
                // Other key attributes
                val anticircle = parse_nonloc_key_attr(parser, "anticircle")
                val width = attribute_float(parser, "width", 1f)
                val shift = attribute_float(parser, "shift", 0f)
                val indication = parser.getAttributeValue(null, "indication")
                while (parser.next() != XmlPullParser.END_TAG)
                    continue
                return Key(ks.toList(), anticircle, keysflags, maxOf(width, 0f), maxOf(shift, 0f), indication)
            }
        }
    }

    // Not using Function<KeyValue, KeyValue> to keep compatibility with Android 6.
    fun interface MapKey {
        fun apply(k: Key): Key
    }

    abstract class MapKeyValues : MapKey {
        abstract fun apply(c: KeyValue, localized: Boolean): KeyValue?

        override fun apply(k: Key): Key {
            val ks = List<KeyValue?>(k.keys.size) { i ->
                if (k.keys[i] != null)
                    apply(k.keys[i]!!, k.keyHasFlag(i, Key.F_LOC))
                else
                    null
            }
            return Key(ks, k.anticircle, k.keysflags, k.width, k.shift, k.indication)
        }
    }

    /** Position of a key on the layout. */
    data class KeyPos(
        val row: Int,
        val col: Int,
        val dir: Int
    ) {
        fun with_dir(d: Int): KeyPos = KeyPos(row, col, d)
    }

    /** See [addExtraKeys()]. */
    class PreferredPos {
        /** Prefer the free position on the same keyboard key as the specified key.
            Considered before [positions]. Might be [null]. */
        var next_to: KeyValue? = null

        /** Array of positions to try in order. The special value [-1] as [row],
            [col] or [dir] means that the field is unspecified. Every possible
            values are tried for unspecified fields. Unspecified fields are
            searched in this order: [dir], [col], [row]. */
        var positions: Array<KeyPos> = ANYWHERE_POSITIONS

        constructor()
        constructor(next_to_: KeyValue?) {
            next_to = next_to_
        }
        constructor(pos: Array<KeyPos>) {
            positions = pos
        }
        constructor(next_to_: KeyValue?, pos: Array<KeyPos>) {
            next_to = next_to_
            positions = pos
        }
        constructor(src: PreferredPos) {
            next_to = src.next_to
            positions = src.positions
        }

        companion object {
            /** Default position for extra keys. */
            @JvmField
            val DEFAULT: PreferredPos

            @JvmField
            val ANYWHERE: PreferredPos

            private val ANYWHERE_POSITIONS = arrayOf(KeyPos(-1, -1, -1))

            init {
                DEFAULT = PreferredPos(arrayOf(
                    KeyPos(1, -1, 4),
                    KeyPos(1, -1, 3),
                    KeyPos(2, -1, 2),
                    KeyPos(2, -1, 1)
                ))
                ANYWHERE = PreferredPos()
            }
        }
    }

    companion object {
        private val _layoutCache = HashMap<Int, KeyboardData?>()

        @JvmStatic
        @Throws(Exception::class)
        fun load_row(res: Resources, res_id: Int): Row {
            val parser = res.getXml(res_id)
            // Skip to the <row> tag
            if (!expect_tag(parser, "row"))
                throw error(parser, "Expected tag <row>")
            return Row.parse(parser)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun load_num_pad(res: Resources): KeyboardData =
            parse_keyboard(res.getXml(R.xml.numpad))

        /** Load a layout from a resource ID. Returns [null] on error. */
        @JvmStatic
        fun load(res: Resources, id: Int): KeyboardData? {
            if (_layoutCache.containsKey(id))
                return _layoutCache[id]
            var l: KeyboardData? = null
            var parser: XmlResourceParser? = null
            try {
                parser = res.getXml(id)
                l = parse_keyboard(parser)
            } catch (e: Exception) {
                Logs.exn("Failed to load layout id $id", e)
            }
            parser?.close()
            _layoutCache[id] = l
            return l
        }

        /** Load a layout from a string. Returns [null] on error. */
        @JvmStatic
        fun load_string(src: String): KeyboardData? {
            return try {
                load_string_exn(src)
            } catch (e: Exception) {
                null
            }
        }

        /** Like [load_string] but throws an exception on error and do not return [null]. */
        @JvmStatic
        @Throws(Exception::class)
        fun load_string_exn(src: String): KeyboardData {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(src))
            return parse_keyboard(parser)
        }

        @Throws(Exception::class)
        private fun parse_keyboard(parser: XmlPullParser): KeyboardData {
            if (!expect_tag(parser, "keyboard"))
                throw error(parser, "Expected tag <keyboard>")
            val bottom_row = attribute_bool(parser, "bottom_row", true)
            val embedded_number_row = attribute_bool(parser, "embedded_number_row", false)
            val locale_extra_keys = attribute_bool(parser, "locale_extra_keys", true)
            val specified_kw = attribute_float(parser, "width", 0f)
            var script = parser.getAttributeValue(null, "script")
            if (script != null && script.isEmpty())
                throw error(parser, "'script' attribute cannot be empty")
            var numpad_script = parser.getAttributeValue(null, "numpad_script")
            if (numpad_script == null)
                numpad_script = script
            else if (numpad_script.isEmpty())
                throw error(parser, "'numpad_script' attribute cannot be empty")
            val name = parser.getAttributeValue(null, "name")
            val rows = ArrayList<Row>()
            var modmap: Modmap? = null
            while (next_tag(parser)) {
                when (parser.name) {
                    "row" -> rows.add(Row.parse(parser))
                    "modmap" -> {
                        if (modmap != null)
                            throw error(parser, "Multiple '<modmap>' are not allowed")
                        modmap = parse_modmap(parser)
                    }
                    else -> throw error(parser, "Expecting tag <row>, got <${parser.name}>")
                }
            }
            val kw = if (specified_kw != 0f) specified_kw else compute_max_width(rows)
            var kh = 0f
            for (r in rows)
                kh += r.height + r.shift
            return KeyboardData(rows, maxOf(kw, 1f), kh, modmap, script, numpad_script, name,
                bottom_row, embedded_number_row, locale_extra_keys)
        }

        private fun compute_max_width(rows: List<Row>): Float {
            var w = 0f
            for (r in rows)
                w = maxOf(w, r.keysWidth)
            return w
        }

        /** Compute total keyboard height from rows. */
        private fun compute_total_height(rows: List<Row>): Float {
            var h = 0f
            for (r in rows)
                h += r.height + r.shift
            return h
        }

        @JvmStatic
        @Throws(Exception::class)
        fun parse_modmap(parser: XmlPullParser): Modmap {
            val mm = Modmap()
            while (next_tag(parser)) {
                val m = when (parser.name) {
                    "shift" -> Modmap.M.Shift
                    "fn" -> Modmap.M.Fn
                    "ctrl" -> Modmap.M.Ctrl
                    else -> throw error(parser, "Expecting tag <shift> or <fn>, got <${parser.name}>")
                }
                parse_modmap_mapping(parser, mm, m)
            }
            return mm
        }

        @Throws(Exception::class)
        private fun parse_modmap_mapping(parser: XmlPullParser, mm: Modmap, m: Modmap.M) {
            val a = KeyValue.getKeyByName(parser.getAttributeValue(null, "a"))
            val b = KeyValue.getKeyByName(parser.getAttributeValue(null, "b"))
            while (parser.next() != XmlPullParser.END_TAG)
                continue
            mm.add(m, a, b)
        }

        // Parsing utils

        /** Returns [false] on [END_DOCUMENT] or [END_TAG], [true] otherwise. */
        @Throws(Exception::class)
        private fun next_tag(parser: XmlPullParser): Boolean {
            var status: Int
            do {
                status = parser.next()
                if (status == XmlPullParser.END_DOCUMENT || status == XmlPullParser.END_TAG)
                    return false
            } while (status != XmlPullParser.START_TAG)
            return true
        }

        /** Returns [false] on [END_DOCUMENT] or [END_TAG], [true] otherwise. */
        @Throws(Exception::class)
        private fun expect_tag(parser: XmlPullParser, name: String): Boolean {
            if (!next_tag(parser))
                return false
            if (parser.name != name)
                throw error(parser, "Expecting tag <$name>, got <${parser.name}>")
            return true
        }

        private fun attribute_bool(parser: XmlPullParser, attr: String, default_val: Boolean): Boolean {
            val val_str = parser.getAttributeValue(null, attr) ?: return default_val
            return val_str == "true"
        }

        private fun attribute_float(parser: XmlPullParser, attr: String, default_val: Float): Float {
            val val_str = parser.getAttributeValue(null, attr) ?: return default_val
            return val_str.toFloat()
        }

        /** Construct a parsing error. */
        private fun error(parser: XmlPullParser, message: String): Exception =
            Exception("$message ${parser.positionDescription}")
    }
}
