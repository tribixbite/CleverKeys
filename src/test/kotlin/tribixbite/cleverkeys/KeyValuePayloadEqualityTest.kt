package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Review 2026-11-14 (low): [KeyValue.equals] compares payloads through compareTo, but
 * [KeyValue.hashCode] uses the payload's hashCode — and the Macro / TimestampFormat /
 * TemplateFormat payloads overrode compareTo only, so two equal keys parsed separately (a layout
 * key and an extra key of the same definition) hashed differently: HashMap/HashSet lookups
 * missed, and a template extra key already in the layout was added a second time.
 */
class KeyValuePayloadEqualityTest {

    @Test
    fun equalTemplatePayloadsAreEqualAndHashAlike() {
        val a = KeyValue.TemplateFormat("{date}", "📅")
        val b = KeyValue.TemplateFormat("{date}", "📅")
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(setOf(a)).contains(b)
        assertThat(a).isNotEqualTo(KeyValue.TemplateFormat("{time}", "📅"))
    }

    @Test
    fun equalTimestampPayloadsAreEqualAndHashAlike() {
        val a = KeyValue.TimestampFormat("yyyy-MM-dd", "date")
        val b = KeyValue.TimestampFormat("yyyy-MM-dd", "date")
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun equalMacroPayloadsAreEqualAndHashAlike() {
        val keys = arrayOf(KeyValue.makeStringKey("a"), KeyValue.makeStringKey("b"))
        val a = KeyValue.Macro(keys, "ab")
        val b = KeyValue.Macro(arrayOf(KeyValue.makeStringKey("a"), KeyValue.makeStringKey("b")), "ab")
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }
}
