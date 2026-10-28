package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.Suggestion
import kotlin.random.Random

/**
 * A keyboard look's suggestion-bar arrangement ([LookBarArrangement], 2026-10-27): three slots,
 * the best candidate (= what space commits) in the centre, the typed word left of it (quoted on
 * iOS), prompts and non-look themes unchanged. The arrangement is drawing only: every visual slot
 * names the ENTRY it shows, so a tap commits that entry's wire string and the bar's data (ranking
 * order, highlight index, space commit) never changes.
 */
class LookBarArrangementTest {

    private val gboard = LookBar(slots = 3)
    private val ios = LookBar(slots = 3, quoteTyped = true)

    private fun words(vararg w: String): List<Suggestion> = w.map { Suggestion.parse(it) }

    /** Visual texts: entry wire, quoted with “ ” when the slot says so, "_" for an empty slot. */
    private fun shown(entries: List<Suggestion>, slots: List<BarSlot>?): List<String> = slots!!.map { s ->
        val e = s.entry ?: return@map "_"
        LookBarArrangement.slotText(entries[e], s.quoted, "“", "”")
    }

    @Test
    fun theBestCandidateIsCentredAndTheRestFollowLeftThenRight() {
        val e = words("dinner", "dinners", "dinnertime", "sinners", "sinner")
        assertThat(shown(e, LookBarArrangement.arrange(e, 0, null, gboard)))
            .containsExactly("dinners", "dinner", "dinnertime").inOrder()
    }

    @Test
    fun theHighlightedEntryWhatSpaceCommitsIsTheCentreEvenWhenNotFirst() {
        // BarHighlightPlanner highlights the typed word wherever the ranker put it.
        val e = words("then", "they", "the", "them")
        val slots = LookBarArrangement.arrange(e, 2, "the", gboard)!!
        assertThat(slots[1].entry).isEqualTo(2)
        assertThat(shown(e, slots)).containsExactly("then", "the", "they").inOrder()
    }

    @Test
    fun iosQuotesTheTypedWordLeftOfTheCorrection() {
        // tomorr -> space commits "tomorrow" (moved to slot 0 and highlighted); typed word listed.
        val e = words("tomorrow", "tomorrows", "tomorr")
        val slots = LookBarArrangement.arrange(e, 0, "tomorr", ios)!!
        assertThat(shown(e, slots)).containsExactly("“tomorr”", "tomorrow", "tomorrows").inOrder()
        assertWithMessage("the same bar on a non-quoting look").that(shown(e, LookBarArrangement.arrange(e, 0, "tomorr", gboard)))
            .containsExactly("tomorr", "tomorrow", "tomorrows").inOrder()
    }

    @Test
    fun theTypedWordIsNotQuotedWhenItIsTheBest() {
        val e = words("cat", "cats", "catch")
        assertThat(shown(e, LookBarArrangement.arrange(e, 0, "cat", ios))).containsExactly("cats", "cat", "catch").inOrder()
    }

    @Test
    fun theTapToAddChipForAnUnknownWordAlwaysGetsASlot() {
        // "#42": the exact typed word, appended LAST by the handler; with three slots it would fall off.
        val e = words("cleverly", "clever", "cleverness", "clevers", Suggestion.ExactAdd("cleverkeys").wire)
        val slots = LookBarArrangement.arrange(e, null, "cleverkeys", ios)!!
        assertThat(shown(e, slots)).containsExactly("“cleverkeys”", "cleverly", "clever").inOrder()
        assertWithMessage("the slot still taps the tap-to-add entry").that(e[slots[0].entry!!]).isInstanceOf(Suggestion.ExactAdd::class.java)
        assertWithMessage("a look draws the typed word plain, as the real products do (Pixel 2026-10-27)")
            .that(shown(e, LookBarArrangement.arrange(e, null, "cleverkeys", gboard)))
            .containsExactly("cleverkeys", "cleverly", "clever").inOrder()
    }

    /**
     * Device references (Pixel 8 Pro, 2026-10-27 night, `build/oct27-night-pixel/`): for the
     * unknown prefix "tomorr" the real Gboard shows `tomorr | **tomorrow** | tomorrow's` and the
     * real SwiftKey `tomorr | tomorrow | tomorrow's` — the typed word as a plain candidate, no "+",
     * no italic. Tapping it still commits it and adds it to the dictionary (both products learn a
     * word tapped there), so only the drawing changes.
     */
    @Test
    fun aLooksTypedSlotIsThePlainWordNeverThePlusChip() {
        val add = Suggestion.ExactAdd("tomorr")
        assertThat(LookBarArrangement.slotText(add, quoted = false, open = "“", close = "”")).isEqualTo("tomorr")
        assertThat(LookBarArrangement.slotText(add, quoted = true, open = "“", close = "”")).isEqualTo("“tomorr”")
        assertThat(LookBarArrangement.slotText(Suggestion.Word("tomorrow"), quoted = false, open = "“", close = "”"))
            .isEqualTo("tomorrow")
        assertWithMessage("a tap on the slot still routes as the tap-to-add entry")
            .that(Suggestion.parse(add.wire)).isEqualTo(add)
    }

    /**
     * Gboard (and iOS, Samsung) emphasise the centre only while it is what space commits: bold for
     * a pending correction, plain for next-word predictions (`at | morning | and`, nothing bold).
     * SwiftKey always draws the centre in the text colour at regular weight and both sides dimmed
     * (dark #FFFFFF / #939393, light #292929 / #777777).
     */
    @Test
    fun slotPaintFollowsEachProduct() {
        val commit = LookBar(3)
        assertThat(LookBarArrangement.paint(commit, centre = true, commit = true)).isEqualTo(SlotPaint(SlotInk.HIGHLIGHT, bold = true))
        assertWithMessage("next-word centre").that(LookBarArrangement.paint(commit, centre = true, commit = false))
            .isEqualTo(SlotPaint(SlotInk.TEXT, bold = false))
        assertThat(LookBarArrangement.paint(commit, centre = false, commit = false)).isEqualTo(SlotPaint(SlotInk.TEXT, bold = false))
        val centre = LookBar(3, emphasis = BarEmphasis.CENTRE)
        for (c in listOf(true, false)) {
            assertThat(LookBarArrangement.paint(centre, centre = true, commit = c)).isEqualTo(SlotPaint(SlotInk.TEXT, bold = false))
            assertThat(LookBarArrangement.paint(centre, centre = false, commit = c)).isEqualTo(SlotPaint(SlotInk.SIDE, bold = false))
        }
    }

    @Test
    fun nextWordPredictionsHaveNoCommitEntry() {
        assertThat(LookBarArrangement.commitIndex(0, nextWordOnly = true)).isNull()
        assertThat(LookBarArrangement.commitIndex(2, nextWordOnly = false)).isEqualTo(2)
        assertThat(LookBarArrangement.commitIndex(null, nextWordOnly = false)).isNull()
    }

    @Test
    fun arrangeMarksTheCentreSlot() {
        val e = words("dinner", "dinners", "dinnertime")
        val slots = LookBarArrangement.arrange(e, 0, null, gboard)!!
        assertThat(slots.map { it.centre }).containsExactly(false, true, false).inOrder()
    }

    @Test
    fun swiftKeySideInkIsWithinAFewLevelsOfTheRealOne() {
        fun grey(c: Int) = c and 0xFF
        val dark = LookBarArrangement.sideColor(0xFFFFFFFF.toInt(), 0xFF141414.toInt())
        val light = LookBarArrangement.sideColor(0xFF292929.toInt(), 0xFFF1F1F1.toInt())
        assertThat(grey(dark)).isIn(0x93 - 10..0x93 + 10)
        assertThat(grey(light)).isIn(0x77 - 10..0x77 + 10)
        assertThat(dark ushr 24).isEqualTo(0xFF)
    }

    @Test
    fun fewerCandidatesThanSlotsLeaveEmptySlotsAndSeparatorsOnlyBetweenFilledOnes() {
        val one = words("hello")
        val s1 = LookBarArrangement.arrange(one, 0, null, gboard)!!
        assertThat(shown(one, s1)).containsExactly("_", "hello", "_").inOrder()
        assertThat(LookBarArrangement.separatorBetween(s1[0], s1[1])).isFalse()
        val two = words("hello", "help")
        val s2 = LookBarArrangement.arrange(two, 0, null, gboard)!!
        assertThat(shown(two, s2)).containsExactly("help", "hello", "_").inOrder()
        assertThat(LookBarArrangement.separatorBetween(s2[0], s2[1])).isTrue()
        assertThat(LookBarArrangement.separatorBetween(s2[1], s2[2])).isFalse()
    }

    @Test
    fun promptsAndNonLookThemesKeepThePlainBar() {
        assertThat(LookBarArrangement.arrange(words("a", "b"), 0, null, null)).isNull()
        assertThat(LookBarArrangement.arrange(emptyList(), 0, null, gboard)).isNull()
        val prefer = listOf(Suggestion.PreferSwipeWord("wet").wire, Suggestion.DeclineSwipePreference("wet").wire)
        assertWithMessage("the 'Prefer … when swiping?' offer").that(LookBarArrangement.arrange(prefer.map(Suggestion::parse), 0, null, ios)).isNull()
        val add = listOf(Suggestion.AddToDictionary("gitk").wire)
        assertWithMessage("the add-to-dictionary prompt").that(LookBarArrangement.arrange(add.map(Suggestion::parse), 0, null, ios)).isNull()
    }

    @Test
    fun aNullHighlightCentresTheTopEntryWithoutChangingTheHighlight() {
        // Typed "hel": space keeps "hel", which is not listed -> no highlight; the bar still has a centre.
        val e = words("hello", "help", "helmet")
        assertThat(shown(e, LookBarArrangement.arrange(e, null, "hel", gboard))).containsExactly("help", "hello", "helmet").inOrder()
    }

    @Test
    fun positionsWorkForEveryAllowedSlotCount() {
        assertThat(LookBarArrangement.positions(LookBar(1))).containsExactly(0)
        assertThat(LookBarArrangement.positions(LookBar(3))).containsExactly(1, 0, 2).inOrder()
        assertThat(LookBarArrangement.positions(LookBar(4))).containsExactly(1, 0, 2, 3).inOrder()
        assertThat(LookBarArrangement.positions(LookBar(5))).containsExactly(2, 1, 3, 0, 4).inOrder()
        assertThat(LookBarArrangement.positions(LookBar(5, centreBest = false))).containsExactly(0, 1, 2, 3, 4).inOrder()
    }

    /**
     * Property over random bars: each entry is shown at most once, every visible slot names a real
     * entry (so a tap commits exactly that entry's wire string), the highlighted word is always
     * visible in the centre, and a quoted slot is never the best.
     */
    @Test
    fun everySlotTapsItsOwnEntryAndTheSpaceCommitIsAlwaysCentred() {
        val rnd = Random(20261027)
        repeat(2000) {
            val n = rnd.nextInt(1, 7)
            val e = (0 until n).map { i ->
                if (i == n - 1 && rnd.nextInt(4) == 0) Suggestion.ExactAdd("typ") else Suggestion.Word("w$i")
            }
            val typed = if (rnd.nextBoolean()) "w${rnd.nextInt(n)}" else null
            val hl = if (rnd.nextInt(3) == 0) null else rnd.nextInt(-1, n + 1)
            val bar = LookBar(rnd.nextInt(1, 6), rnd.nextBoolean(), rnd.nextBoolean())
            val slots = LookBarArrangement.arrange(e, hl, typed, bar)!!
            assertThat(slots).hasSize(bar.slots)
            val shownEntries = slots.mapNotNull { it.entry }
            assertThat(shownEntries).containsNoDuplicates()
            assertThat(shownEntries.all { it in e.indices }).isTrue()
            assertThat(shownEntries.size).isEqualTo(minOf(n, bar.slots))
            if (hl != null && hl in e.indices && e[hl] is Suggestion.Word) {
                val centre = LookBarArrangement.positions(bar).first()
                assertWithMessage("bar=$bar hl=$hl").that(slots[centre].entry).isEqualTo(hl)
                assertThat(slots[centre].quoted).isFalse()
            }
        }
    }

    @Test
    fun everyLookHasThreeCentredSlotsAndOnlyTheIosLooksQuote() {
        for (look in KeyboardLooks.ALL) {
            val bar = look.bar
            assertWithMessage(look.id).that(bar).isNotNull()
            assertThat(bar!!.slots).isEqualTo(3)
            assertThat(bar.centreBest).isTrue()
            assertWithMessage("${look.id} quoting").that(bar.quoteTyped)
                .isEqualTo(look.id == KeyboardLooks.IOS || look.id == KeyboardLooks.IOS18)
            assertWithMessage("${look.id} emphasis").that(bar.emphasis)
                .isEqualTo(if (look.id == KeyboardLooks.SWIFTKEY) BarEmphasis.CENTRE else BarEmphasis.COMMIT)
        }
    }

    @Test
    fun theArrangementTravelsWithAThemeSpecThroughXmlAndJson() {
        val problems = ArrayList<String>()
        val spec = ThemeSpecXml.parse(mapOf("bar_slots" to "5", "bar_quote_typed" to "true"), null, null, problems)
        assertThat(problems).isEmpty()
        assertThat(spec.bar).isEqualTo(LookBar(slots = 5, centreBest = true, quoteTyped = true))
        assertWithMessage("a look saved as a custom theme keeps its bar")
            .that(ThemeSpecs.ofLook(KeyboardLooks.byId(KeyboardLooks.IOS)!!).bar).isEqualTo(LookBar(3, quoteTyped = true))
        val json = org.json.JSONObject(ThemeSpecJson.toJson(spec).toString())
        assertThat(ThemeSpecJson.fromJson(json).bar).isEqualTo(spec.bar)
        val centred = ThemeSpecXml.parse(mapOf("bar_emphasis" to "centre"), null, null, problems)
        assertThat(problems).isEmpty()
        assertThat(centred.bar).isEqualTo(LookBar(emphasis = BarEmphasis.CENTRE))
        assertThat(ThemeSpecJson.fromJson(org.json.JSONObject(ThemeSpecJson.toJson(centred).toString())).bar)
            .isEqualTo(centred.bar)
        assertWithMessage("SwiftKey saved as a custom theme keeps its emphasis")
            .that(ThemeSpecs.ofLook(KeyboardLooks.byId(KeyboardLooks.SWIFTKEY)!!).bar?.emphasis).isEqualTo(BarEmphasis.CENTRE)
        assertWithMessage("an impossible slot count from a hand-edited backup drops the arrangement")
            .that(ThemeSpecJson.fromJson(org.json.JSONObject("""{"bar":{"slots":9}}""")).bar).isNull()
        val bad = ArrayList<String>()
        assertThat(ThemeSpecXml.parse(mapOf("bar_slots" to "0"), null, null, bad).bar).isNull()
        assertThat(bad).containsExactly("<theme> bar_slots=\"0\"")
        assertWithMessage("a spec without the attributes defines no bar (scrolling chips)")
            .that(ThemeSpecXml.parse(mapOf("key" to "#FFFFFF"), null, null, ArrayList()).bar).isNull()
    }
}
