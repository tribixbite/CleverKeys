package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.sqrt

/**
 * Pop-ups and hint style of the keyboard looks (2026-10-28, maintainer: "fix pop-ups for custom
 * looks", "gesture hints appearance"). References: Pixel 8 Pro captures of the real Gboard and
 * SwiftKey (`build/oct28-looks2/`, not in git) and research §1–§4.
 */
class LookPopupsTest {

    // A middle-row key: 100 × 150 px at (300, 400), 16 px corners, in a 1000 px wide view.
    private val kx = 300f
    private val ky = 400f
    private val kw = 100f
    private val kh = 150f
    private val kr = 16f
    private val viewW = 1000f
    private val label = 60f

    private fun frame(p: LookPreview, x: Float = kx, y: Float = ky) =
        LookPreviewPolicy.frame(p, x, y, kw, kh, kr, viewW, label)

    @Test
    fun eachLookShowsItsProductsPreviewShape() {
        assertThat(KeyboardLooks.iosLook.preview.shape).isEqualTo(LookPreviewShape.BALLOON)
        assertThat(KeyboardLooks.ios18Look.preview.shape).isEqualTo(LookPreviewShape.BALLOON)
        assertThat(KeyboardLooks.gboardLook.preview.shape).isEqualTo(LookPreviewShape.BUBBLE)
        assertThat(KeyboardLooks.swiftkeyLook.preview.shape).isEqualTo(LookPreviewShape.COLUMN)
        assertThat(KeyboardLooks.samsungLook.preview.shape).isEqualTo(LookPreviewShape.PLATE)
        for (look in KeyboardLooks.ALL) {
            assertWithMessage("${look.id}: the preview label is larger than the key's")
                .that(look.preview.labelScale).isGreaterThan(1f)
        }
    }

    @Test
    fun previewsAreDrawnOnlyForCharactersOnALookWithThePreferenceOn() {
        val p = KeyboardLooks.gboardLook.preview
        assertThat(LookPreviewPolicy.shows(p, prefOn = true, isCharacter = true, isSpace = false)).isTrue()
        assertWithMessage("the user turned previews off").that(LookPreviewPolicy.shows(p, false, true, false)).isFalse()
        assertWithMessage("function keys (shift, backspace, Enter) get none").that(LookPreviewPolicy.shows(p, true, false, false)).isFalse()
        assertWithMessage("space gets none").that(LookPreviewPolicy.shows(p, true, true, true)).isFalse()
        assertWithMessage("non-look themes: no preview, as before").that(LookPreviewPolicy.shows(null, true, true, false)).isFalse()
        assertThat(LookPreviewPolicy.shows(LookPreview.NONE, true, true, false)).isFalse()
    }

    @Test
    fun gboardsBubbleIsAStadiumRisingFromTheKeysTopEdge() {
        val p = KeyboardLooks.gboardLook.preview
        val f = frame(p)
        assertThat(f.headRight - f.headLeft).isWithin(0.01f).of(kw * p.widthScale)
        assertThat(f.headBottom - f.headTop).isWithin(0.01f).of(kh * p.heightScale)
        assertWithMessage("centred on the key").that((f.headLeft + f.headRight) / 2f).isWithin(0.01f).of(kx + kw / 2f)
        assertWithMessage("its foot overlaps the key's top edge").that(f.headBottom).isGreaterThan(ky)
        assertThat(f.headBottom).isLessThan(ky + kh / 2f)
        assertWithMessage("stadium: radius = half the shorter side")
            .that(f.headRadius).isWithin(0.01f).of(minOf(f.headRight - f.headLeft, f.headBottom - f.headTop) / 2f)
        assertThat(f.hasNeck).isFalse()
        assertThat(f.labelSize).isWithin(0.01f).of(label * p.labelScale)
    }

    @Test
    fun iosBalloonHasAWideHeadAboveTheKeyJoinedByANeck() {
        val p = KeyboardLooks.iosLook.preview
        val f = frame(p)
        assertWithMessage("head wider than the key on both sides").that(f.headLeft).isLessThan(kx)
        assertThat(f.headRight).isGreaterThan(kx + kw)
        assertWithMessage("head entirely above the key").that(f.headBottom).isLessThan(ky)
        assertThat(f.hasNeck).isTrue()
        assertWithMessage("the neck is the key's width").that(f.neckRight - f.neckLeft).isEqualTo(kw)
        assertWithMessage("…reaching from inside the head to the key's bottom")
            .that(f.neckTop).isLessThan(f.headBottom)
        assertThat(f.neckBottom).isEqualTo(ky + kh)
        assertThat(f.neckRadius).isEqualTo(kr)
    }

    @Test
    fun swiftkeysColumnIsKeyWideAndRisesAboutARow() {
        val f = frame(KeyboardLooks.swiftkeyLook.preview)
        assertThat(f.headLeft).isEqualTo(kx)
        assertThat(f.headRight).isEqualTo(kx + kw)
        assertThat(f.headBottom).isEqualTo(ky + kh)
        assertWithMessage("rises at least one key height above the key").that(f.headTop).isAtMost(ky - kh)
        assertWithMessage("label in the column's top key-height").that(f.labelCenterY).isLessThan(ky)
        assertThat(f.headRadius).isEqualTo(kr)
    }

    @Test
    fun samsungsPlateSitsAboveTheKey() {
        val f = frame(KeyboardLooks.samsungLook.preview)
        assertThat(f.headBottom).isLessThan(ky)
        assertThat(f.headRight - f.headLeft).isGreaterThan(kw)
    }

    @Test
    fun aTopRowPreviewMovesDownInsideTheWindowAndEdgeKeysMoveInside() {
        for (look in KeyboardLooks.ALL) {
            val top = frame(look.preview, y = 0f)
            assertWithMessage("${look.id}: an IME cannot draw above its window").that(top.headTop).isAtLeast(0f)
            assertThat(top.labelCenterY).isGreaterThan(top.headTop)
            val left = frame(look.preview, x = 0f)
            assertWithMessage("${look.id} left edge").that(left.headLeft).isAtLeast(0f)
            val right = frame(look.preview, x = viewW - kw)
            assertWithMessage("${look.id} right edge").that(right.headRight).isAtMost(viewW)
            assertWithMessage("${look.id}: the label stays inside the moved head")
                .that(right.labelCenterX).isIn(com.google.common.collect.Range.closed(right.headLeft, right.headRight))
        }
    }

    @Test
    fun popupColoursDefaultToTheKeyAndTheCapsLockRoles() {
        val p = KeyboardLooks.gboardLook.light
        assertThat(LookPopupColors.of(p)).isEqualTo(LookPopupColors(p.key, p.label, p.keyLocked, p.lockedLabel))
        val s = KeyboardLooks.swiftkeyLook.dark
        assertWithMessage("SwiftKey's preview is its press colour #404040")
            .that(LookPopupColors.of(s).fill).isEqualTo(0xFF404040.toInt())
    }

    @Test
    fun everyLookPaletteMeetsTheSharedContrastFloors() {
        for (look in KeyboardLooks.ALL) for (light in listOf(true, false)) {
            val p = look.palette(light)
            assertWithMessage("${look.id} ${if (light) "light" else "dark"}").that(LookContrast.failures(p)).isEmpty()
        }
    }

    @Test
    fun gboardDrawsItsEmojiKeyAsALetterKey() {
        assertThat(KeyboardLooks.gboardLook.letterKeys).containsExactly(LookLetterKey.EMOJI)
        for (look in KeyboardLooks.ALL - KeyboardLooks.gboardLook) assertThat(look.letterKeys).isEmpty()
    }

    @Test
    fun lookHintsAreAFractionOfTheLooksLabelAndClearOfRoundedCorners() {
        val style = KeyboardLooks.gboardLook.hintStyle
        val m = LookHintLayout.resolve(style, mainLabelSize = 60f, userScale = 1f, basePadding = 4f, radius = 75f, keyH = 150f)
        assertThat(m.size).isWithin(0.01f).of(60f * style.sizeFraction)
        assertWithMessage("edge slots keep the user's padding").that(m.edgeInset).isEqualTo(4f)
        assertWithMessage("a pill's corner label is inset past the arc")
            .that(m.cornerInset).isWithin(0.01f).of(75f * (1f - 1f / sqrt(2f)))
        assertWithMessage("square corners: the user's padding")
            .that(LookHintLayout.cornerInset(4f, 0f)).isEqualTo(4f)
        assertWithMessage("the user's secondary-size setting still scales the hint")
            .that(LookHintLayout.size(style, 60f, 1.5f, 150f)).isWithin(0.01f).of(60f * style.sizeFraction * 1.5f)
        assertWithMessage("never taller than 30 % of the key")
            .that(LookHintLayout.size(style, 60f, 10f, 150f)).isWithin(0.01f).of(45f)
        // Fluent's hints are lighter than its labels; every look's hint is smaller than its label.
        assertThat(KeyboardLooks.swiftkeyLook.hintStyle.weight).isLessThan(400)
        for (look in KeyboardLooks.ALL) assertThat(look.hintStyle.sizeFraction).isLessThan(0.5f)
    }
}
