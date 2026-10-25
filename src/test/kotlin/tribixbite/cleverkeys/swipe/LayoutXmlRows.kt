package tribixbite.cleverkeys.swipe

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData

/**
 * A shipped layout XML parsed into [KeyboardData.Row]s with a plain JDK DOM, for pure-JVM tests
 * (the app's own parser needs Android's XmlPullParser). Mirrors `KeyboardData.parse`'s
 * width/shift defaults. Single-char centres become real char keys (including é, ü, ñ — the
 * CTC gate decides what they mean, not this helper); multi-char centres (shift, backspace)
 * become placeholder cells that advance the x cursor but produce no rect — their WIDTH still
 * displaces the letters after them, which is exactly why they cannot just be dropped.
 * Corner values are not parsed: `KeyboardGeometry.computeKeyRects` emits `keys[0]` only.
 */
internal object LayoutXmlRows {

    fun parse(file: File): List<KeyboardData.Row> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val rows = ArrayList<KeyboardData.Row>()
        val rowNodes = doc.documentElement.getElementsByTagName("row")
        for (r in 0 until rowNodes.length) {
            val rowEl = rowNodes.item(r) as Element
            val keys = ArrayList<KeyboardData.Key>()
            val keyNodes = rowEl.getElementsByTagName("key")
            for (k in 0 until keyNodes.length) {
                val keyEl = keyNodes.item(k) as Element
                val width = keyEl.getAttribute("width").ifEmpty { "1" }.toFloat()
                val shift = keyEl.getAttribute("shift").ifEmpty { "0" }.toFloat()
                val centre = keyEl.getAttribute("key0").ifEmpty { keyEl.getAttribute("c") }
                val key =
                    if (centre.length == 1) {
                        KeyboardData.Key.EMPTY
                            .withKeyValue(0, KeyValue.makeCharKey(centre[0]))
                            .let { KeyboardData.Key(it.keys, it.anticircle, 0, width, shift, null) }
                    } else {
                        KeyboardData.Key(List(9) { null }, null, 0, width, shift, null)
                    }
                keys.add(key)
            }
            val height = rowEl.getAttribute("height").ifEmpty { "1" }.toFloat()
            val rshift = rowEl.getAttribute("shift").ifEmpty { "0" }.toFloat()
            rows.add(KeyboardData.Row(keys, height, rshift))
        }
        return rows
    }
}
