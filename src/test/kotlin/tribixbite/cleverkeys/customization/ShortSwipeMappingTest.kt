package tribixbite.cleverkeys.customization

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM unit tests for [ShortSwipeMapping] TIMESTAMP support (issue #141).
 *
 * Covers:
 * - [ActionType.TIMESTAMP] enum metadata
 * - [ShortSwipeMapping.isValidTimestampPattern] validator
 * - [ShortSwipeMapping] `init` validation for TIMESTAMP mappings
 * - [ShortSwipeMapping.timestamp] factory method
 * - [ShortSwipeMapping.getTimestampPattern] accessor
 * - JSON round-trip via [ShortSwipeCustomizations.fromMappingList] / [toMappingList]
 *
 * SimpleDateFormat is JVM-native so these tests run without any Android dependencies.
 */
class ShortSwipeMappingTest {

    // =========================================================================
    // ActionType.TIMESTAMP enum
    // =========================================================================

    @Test
    fun `ActionType enum includes TIMESTAMP`() {
        assertThat(ActionType.entries).contains(ActionType.TIMESTAMP)
    }

    @Test
    fun `ActionType TIMESTAMP has non-empty display fields`() {
        assertThat(ActionType.TIMESTAMP.displayName).isNotEmpty()
        assertThat(ActionType.TIMESTAMP.description).isNotEmpty()
    }

    @Test
    fun `ActionType fromString resolves TIMESTAMP case-insensitively`() {
        assertThat(ActionType.fromString("TIMESTAMP")).isEqualTo(ActionType.TIMESTAMP)
        assertThat(ActionType.fromString("timestamp")).isEqualTo(ActionType.TIMESTAMP)
        assertThat(ActionType.fromString("Timestamp")).isEqualTo(ActionType.TIMESTAMP)
    }

    // =========================================================================
    // isValidTimestampPattern — accepted patterns
    // =========================================================================

    @Test
    fun `isValidTimestampPattern accepts yyyy-MM-dd`() {
        assertThat(ShortSwipeMapping.isValidTimestampPattern("yyyy-MM-dd")).isTrue()
    }

    @Test
    fun `isValidTimestampPattern accepts HH colon mm`() {
        assertThat(ShortSwipeMapping.isValidTimestampPattern("HH:mm")).isTrue()
    }

    @Test
    fun `isValidTimestampPattern accepts EEE MMM d yyyy`() {
        assertThat(ShortSwipeMapping.isValidTimestampPattern("EEE MMM d yyyy")).isTrue()
    }

    @Test
    fun `isValidTimestampPattern accepts ISO 8601 with literal T`() {
        // Single-quoted literal inside pattern — common ISO 8601 shape.
        assertThat(ShortSwipeMapping.isValidTimestampPattern("yyyy-MM-dd'T'HH:mm:ss")).isTrue()
    }

    @Test
    fun `isValidTimestampPattern accepts 12-hour time with AM-PM marker`() {
        assertThat(ShortSwipeMapping.isValidTimestampPattern("h:mm a")).isTrue()
    }

    @Test
    fun `isValidTimestampPattern accepts long date pattern`() {
        assertThat(ShortSwipeMapping.isValidTimestampPattern("EEEE, MMMM d, yyyy")).isTrue()
    }

    // =========================================================================
    // isValidTimestampPattern — rejected patterns
    // =========================================================================

    @Test
    fun `isValidTimestampPattern rejects blank pattern`() {
        assertThat(ShortSwipeMapping.isValidTimestampPattern("")).isFalse()
        assertThat(ShortSwipeMapping.isValidTimestampPattern("   ")).isFalse()
    }

    @Test
    fun `isValidTimestampPattern rejects unknown pattern letters`() {
        // 'x' is not a SimpleDateFormat pattern letter — constructor throws.
        assertThat(ShortSwipeMapping.isValidTimestampPattern("xxxxx")).isFalse()
    }

    @Test
    fun `isValidTimestampPattern rejects pattern with illegal letter b`() {
        // 'b' is not a SimpleDateFormat pattern letter on JDK.
        assertThat(ShortSwipeMapping.isValidTimestampPattern("abc")).isFalse()
    }

    @Test
    fun `isValidTimestampPattern rejects unterminated quote`() {
        assertThat(ShortSwipeMapping.isValidTimestampPattern("yyyy'unclosed")).isFalse()
    }

    // =========================================================================
    // init validation — TIMESTAMP mappings
    // =========================================================================

    @Test
    fun `ShortSwipeMapping accepts well-formed TIMESTAMP pattern`() {
        val mapping = ShortSwipeMapping(
            keyCode = "t",
            direction = SwipeDirection.N,
            displayText = "TS",
            actionType = ActionType.TIMESTAMP,
            actionValue = "yyyy-MM-dd"
        )
        assertThat(mapping.actionType).isEqualTo(ActionType.TIMESTAMP)
        assertThat(mapping.actionValue).isEqualTo("yyyy-MM-dd")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ShortSwipeMapping rejects malformed TIMESTAMP pattern`() {
        ShortSwipeMapping(
            keyCode = "t",
            direction = SwipeDirection.N,
            displayText = "TS",
            actionType = ActionType.TIMESTAMP,
            actionValue = "xxxxx" // 'x' is not a SimpleDateFormat pattern letter
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ShortSwipeMapping rejects blank TIMESTAMP pattern`() {
        ShortSwipeMapping(
            keyCode = "t",
            direction = SwipeDirection.N,
            displayText = "TS",
            actionType = ActionType.TIMESTAMP,
            actionValue = ""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ShortSwipeMapping rejects TIMESTAMP with unterminated quote`() {
        ShortSwipeMapping(
            keyCode = "t",
            direction = SwipeDirection.N,
            displayText = "TS",
            actionType = ActionType.TIMESTAMP,
            actionValue = "yyyy'open"
        )
    }

    // =========================================================================
    // timestamp() factory
    // =========================================================================

    @Test
    fun `timestamp factory creates TIMESTAMP mapping`() {
        val mapping = ShortSwipeMapping.timestamp(
            keyCode = "T",
            direction = SwipeDirection.E,
            displayText = "TS",
            pattern = "yyyy-MM-dd HH:mm"
        )
        assertThat(mapping.actionType).isEqualTo(ActionType.TIMESTAMP)
        assertThat(mapping.actionValue).isEqualTo("yyyy-MM-dd HH:mm")
        assertThat(mapping.keyCode).isEqualTo("t") // normalized to lowercase
        assertThat(mapping.direction).isEqualTo(SwipeDirection.E)
        assertThat(mapping.displayText).isEqualTo("TS")
        assertThat(mapping.useKeyFont).isFalse()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `timestamp factory rejects invalid pattern`() {
        ShortSwipeMapping.timestamp(
            keyCode = "t",
            direction = SwipeDirection.N,
            displayText = "TS",
            pattern = "xxxxx"
        )
    }

    @Test
    fun `timestamp factory truncates display text to MAX_DISPLAY_LENGTH`() {
        val mapping = ShortSwipeMapping.timestamp(
            keyCode = "t",
            direction = SwipeDirection.N,
            displayText = "VeryLongLabel",
            pattern = "HH:mm"
        )
        assertThat(mapping.displayText).hasLength(ShortSwipeMapping.MAX_DISPLAY_LENGTH)
        assertThat(mapping.displayText).isEqualTo("Very")
    }

    // =========================================================================
    // getTimestampPattern accessor
    // =========================================================================

    @Test
    fun `getTimestampPattern returns pattern when actionType is TIMESTAMP`() {
        val mapping = ShortSwipeMapping.timestamp(
            keyCode = "t",
            direction = SwipeDirection.N,
            displayText = "TS",
            pattern = "yyyy-MM-dd"
        )
        assertThat(mapping.getTimestampPattern()).isEqualTo("yyyy-MM-dd")
    }

    @Test
    fun `getTimestampPattern returns null for non-TIMESTAMP mappings`() {
        val textMapping = ShortSwipeMapping.textInput(
            keyCode = "a",
            direction = SwipeDirection.N,
            displayText = "T",
            text = "hello"
        )
        assertThat(textMapping.getTimestampPattern()).isNull()

        val cmdMapping = ShortSwipeMapping.command(
            keyCode = "a",
            direction = SwipeDirection.N,
            displayText = "C",
            command = AvailableCommand.COPY
        )
        assertThat(cmdMapping.getTimestampPattern()).isNull()
    }

    // =========================================================================
    // Storage round-trip — ShortSwipeCustomizations.fromMappingList / toMappingList
    // =========================================================================

    @Test
    fun `TIMESTAMP mapping survives round-trip through storage format`() {
        val original = ShortSwipeMapping.timestamp(
            keyCode = "d",
            direction = SwipeDirection.SE,
            displayText = "now",
            pattern = "yyyy-MM-dd HH:mm:ss"
        )

        val storage = ShortSwipeCustomizations.fromMappingList(listOf(original))
        val restored = storage.toMappingList().single()

        assertThat(restored.actionType).isEqualTo(ActionType.TIMESTAMP)
        assertThat(restored.actionValue).isEqualTo("yyyy-MM-dd HH:mm:ss")
        assertThat(restored.keyCode).isEqualTo("d")
        assertThat(restored.direction).isEqualTo(SwipeDirection.SE)
        assertThat(restored.displayText).isEqualTo("now")
    }

    @Test
    fun `mixed mapping list including TIMESTAMP round-trips all types`() {
        val original = listOf(
            ShortSwipeMapping.textInput("a", SwipeDirection.N, "T", "north"),
            ShortSwipeMapping.command("b", SwipeDirection.E, "CPY", AvailableCommand.COPY),
            ShortSwipeMapping.intent("c", SwipeDirection.W, "INT", """{"name":"X"}"""),
            ShortSwipeMapping.timestamp("d", SwipeDirection.S, "TS", "HH:mm")
        )

        val restored = ShortSwipeCustomizations.fromMappingList(original).toMappingList()
        assertThat(restored).hasSize(4)

        val ts = restored.single { it.keyCode == "d" }
        assertThat(ts.actionType).isEqualTo(ActionType.TIMESTAMP)
        assertThat(ts.actionValue).isEqualTo("HH:mm")
    }

    // =========================================================================
    // XmlAttributeMapper round-trip for TIMESTAMP
    // =========================================================================

    @Test
    fun `XmlAttributeMapper toXmlValue emits timestamp prefix for TIMESTAMP`() {
        val mapping = ShortSwipeMapping.timestamp(
            keyCode = "t",
            direction = SwipeDirection.N,
            displayText = "TS",
            pattern = "yyyy-MM-dd"
        )
        val xml = XmlAttributeMapper.toXmlValue(mapping)
        assertThat(xml).isEqualTo("timestamp:'yyyy-MM-dd'")
    }

    @Test
    fun `XmlAttributeMapper toXmlValue escapes single quotes in TIMESTAMP pattern`() {
        // While rare, single quotes in a pattern are legal (e.g. "yyyy''" for literal apostrophe).
        // The XML attribute syntax uses single-quoted strings, so we must escape them.
        val mapping = ShortSwipeMapping.timestamp(
            keyCode = "t",
            direction = SwipeDirection.N,
            displayText = "TS",
            pattern = "yyyy''"
        )
        val xml = XmlAttributeMapper.toXmlValue(mapping)
        assertThat(xml).startsWith("timestamp:'")
        assertThat(xml).endsWith("'")
        // The two literal apostrophes in the SDF pattern are escaped to backslash-apostrophe
        // for the KeyValueParser quoted-string grammar.
        assertThat(xml).contains("\\'\\'")
    }
}
