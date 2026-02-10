package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM unit tests for Modmap.kt modifier key mapping system.
 *
 * Modmap stores per-modifier (Shift, Fn, Ctrl) key remappings as a
 * sparse Map<KeyValue, KeyValue>. This test exercises add/get/lookup
 * logic using KeyValue.makeCharKey() which is pure JVM (no Android APIs
 * in its construction path).
 *
 * NOTE: KeyValue.kt imports android.view.KeyEvent, but the static
 * constants are only referenced in getKeyByName(). The class should
 * load on JVM with Robolectric's android-all stubs on the test
 * classpath (provided via testImplementation). If the class fails to
 * load at runtime, these tests will be skipped with an informative
 * error — they do NOT use @RunWith(RobolectricTestRunner).
 */
class ModmapTest {

    // =========================================================================
    // A. Modifier enum
    // =========================================================================

    @Test
    fun `M enum has exactly Shift Fn Ctrl`() {
        val values = Modmap.M.values()
        assertThat(values).hasLength(3)
        assertThat(values.map { it.name }).containsExactly("Shift", "Fn", "Ctrl")
    }

    @Test
    fun `M ordinals are 0 1 2`() {
        assertThat(Modmap.M.Shift.ordinal).isEqualTo(0)
        assertThat(Modmap.M.Fn.ordinal).isEqualTo(1)
        assertThat(Modmap.M.Ctrl.ordinal).isEqualTo(2)
    }

    // =========================================================================
    // B. Basic add/get operations
    // =========================================================================

    @Test
    fun `get on empty map returns null`() {
        val modmap = Modmap()
        val key = KeyValue.makeCharKey('a')
        assertThat(modmap.get(Modmap.M.Shift, key)).isNull()
    }

    @Test
    fun `add then get returns mapped value`() {
        val modmap = Modmap()
        val from = KeyValue.makeCharKey('a')
        val to = KeyValue.makeCharKey('A')
        modmap.add(Modmap.M.Shift, from, to)
        assertThat(modmap.get(Modmap.M.Shift, from)).isEqualTo(to)
    }

    @Test
    fun `get with wrong modifier returns null`() {
        val modmap = Modmap()
        val from = KeyValue.makeCharKey('a')
        val to = KeyValue.makeCharKey('A')
        modmap.add(Modmap.M.Shift, from, to)

        // Fn and Ctrl should have no mapping
        assertThat(modmap.get(Modmap.M.Fn, from)).isNull()
        assertThat(modmap.get(Modmap.M.Ctrl, from)).isNull()
    }

    @Test
    fun `get with unmapped key returns null`() {
        val modmap = Modmap()
        val from = KeyValue.makeCharKey('a')
        val to = KeyValue.makeCharKey('A')
        modmap.add(Modmap.M.Shift, from, to)

        val other = KeyValue.makeCharKey('b')
        assertThat(modmap.get(Modmap.M.Shift, other)).isNull()
    }

    // =========================================================================
    // C. Multiple modifiers on same key
    // =========================================================================

    @Test
    fun `same key can have different mappings per modifier`() {
        val modmap = Modmap()
        val from = KeyValue.makeCharKey('a')
        val shiftResult = KeyValue.makeCharKey('A')
        val fnResult = KeyValue.makeCharKey('1')
        val ctrlResult = KeyValue.makeCharKey('\u0001') // Ctrl+A

        modmap.add(Modmap.M.Shift, from, shiftResult)
        modmap.add(Modmap.M.Fn, from, fnResult)
        modmap.add(Modmap.M.Ctrl, from, ctrlResult)

        assertThat(modmap.get(Modmap.M.Shift, from)).isEqualTo(shiftResult)
        assertThat(modmap.get(Modmap.M.Fn, from)).isEqualTo(fnResult)
        assertThat(modmap.get(Modmap.M.Ctrl, from)).isEqualTo(ctrlResult)
    }

    // =========================================================================
    // D. Multiple keys under same modifier
    // =========================================================================

    @Test
    fun `multiple keys can be mapped under same modifier`() {
        val modmap = Modmap()
        val a = KeyValue.makeCharKey('a')
        val b = KeyValue.makeCharKey('b')
        val c = KeyValue.makeCharKey('c')
        val shiftA = KeyValue.makeCharKey('A')
        val shiftB = KeyValue.makeCharKey('B')
        val shiftC = KeyValue.makeCharKey('C')

        modmap.add(Modmap.M.Shift, a, shiftA)
        modmap.add(Modmap.M.Shift, b, shiftB)
        modmap.add(Modmap.M.Shift, c, shiftC)

        assertThat(modmap.get(Modmap.M.Shift, a)).isEqualTo(shiftA)
        assertThat(modmap.get(Modmap.M.Shift, b)).isEqualTo(shiftB)
        assertThat(modmap.get(Modmap.M.Shift, c)).isEqualTo(shiftC)
    }

    // =========================================================================
    // E. Overwrite behavior
    // =========================================================================

    @Test
    fun `adding same key twice overwrites previous mapping`() {
        val modmap = Modmap()
        val from = KeyValue.makeCharKey('a')
        val first = KeyValue.makeCharKey('X')
        val second = KeyValue.makeCharKey('Y')

        modmap.add(Modmap.M.Shift, from, first)
        assertThat(modmap.get(Modmap.M.Shift, from)).isEqualTo(first)

        modmap.add(Modmap.M.Shift, from, second)
        assertThat(modmap.get(Modmap.M.Shift, from)).isEqualTo(second)
    }

    // =========================================================================
    // F. Identity mapping
    // =========================================================================

    @Test
    fun `key can be mapped to itself`() {
        val modmap = Modmap()
        val key = KeyValue.makeCharKey('x')
        modmap.add(Modmap.M.Fn, key, key)
        assertThat(modmap.get(Modmap.M.Fn, key)).isEqualTo(key)
    }

    // =========================================================================
    // G. Unicode character keys
    // =========================================================================

    @Test
    fun `supports unicode character keys`() {
        val modmap = Modmap()
        val from = KeyValue.makeCharKey('\u00E9') // é
        val to = KeyValue.makeCharKey('\u00C9')   // É
        modmap.add(Modmap.M.Shift, from, to)
        assertThat(modmap.get(Modmap.M.Shift, from)).isEqualTo(to)
    }

    @Test
    fun `supports CJK character keys`() {
        val modmap = Modmap()
        val from = KeyValue.makeCharKey('\u4E00') // 一
        val to = KeyValue.makeCharKey('\u58F9')   // 壹
        modmap.add(Modmap.M.Shift, from, to)
        assertThat(modmap.get(Modmap.M.Shift, from)).isEqualTo(to)
    }

    // =========================================================================
    // H. Independent modifier maps
    // =========================================================================

    @Test
    fun `modifier maps are fully independent`() {
        val modmap = Modmap()
        val key = KeyValue.makeCharKey('z')

        // Only add a Ctrl mapping
        val ctrlZ = KeyValue.makeCharKey('\u001A')
        modmap.add(Modmap.M.Ctrl, key, ctrlZ)

        // Shift and Fn should remain null
        assertThat(modmap.get(Modmap.M.Shift, key)).isNull()
        assertThat(modmap.get(Modmap.M.Fn, key)).isNull()
        assertThat(modmap.get(Modmap.M.Ctrl, key)).isEqualTo(ctrlZ)
    }
}
