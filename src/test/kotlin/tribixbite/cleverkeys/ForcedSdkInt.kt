package tribixbite.cleverkeys

import android.os.Build

/**
 * Forces `Build.VERSION.SDK_INT` (android.jar stubs leave it 0) for host tests that cannot use a
 * platform seam, and CHECKS that the write took (test-gap audit 2026-11-28, item 6). The ten
 * copies of this helper swallowed a failed Unsafe write, so a test meant for API 34 could run the
 * API 0 branch and still pass.
 *
 * The readback is by reflection, which reads the static field itself. It cannot see the other
 * failure mode (CLAUDE.md "Forcing SDK_INT with Unsafe is unreliable"): once the JIT compiled the
 * code under test it may fold the old value as a constant. Code whose branches matter gets a
 * platform seam instead (`EditorSelectionReader.platformSdk`, `ClipboardHistoryService.platformSdk`).
 */
object ForcedSdkInt {

    /** Sets SDK_INT to [value] via sun.misc.Unsafe and fails if the field does not read it back. */
    fun set(value: Int) {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val unsafeClass = unsafe.javaClass
        val field = Build.VERSION::class.java.getField("SDK_INT")
        val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)
        val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field) as Long
        unsafeClass.getMethod("putInt", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(unsafe, base, offset, value)
        val now = field.getInt(null)
        check(now == value) { "Build.VERSION.SDK_INT did not take $value (reads $now): the test would run another branch" }
    }
}
