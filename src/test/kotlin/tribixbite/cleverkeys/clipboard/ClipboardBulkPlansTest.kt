package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.ClipboardEntry
import tribixbite.cleverkeys.ClipboardTab

/**
 * Planning for the selection's Merge, Clean, Add to Pinned and Add to Todos actions
 * (maintainer request 2026-10-07). The confirmation shows exactly these plans and the
 * service writes exactly them.
 */
class ClipboardBulkPlansTest {

    private fun text(content: String, ts: Long, id: Long, private: Boolean = false) =
        ClipboardEntry(content, ts, rowId = id, isPrivate = private)

    private fun media(id: Long, ts: Long = id) =
        ClipboardEntry("photo-$id.png", ts, mimeType = "image/png", mediaPath = "m/$id.png", rowId = id)

    @Test
    fun mergeJoinsTextOldestFirstWithOneNewlineRegardlessOfSelectionOrder() {
        val newest = text("third", ts = 300, id = 3)
        val oldest = text("first", ts = 100, id = 9)
        val tieLow = text("second-a", ts = 200, id = 4)
        val tieHigh = text("second-b", ts = 200, id = 7)
        val decision = ClipboardBulkPlans.planMerge(listOf(newest, tieHigh, oldest, tieLow), limitBytes = null)

        val plan = (decision as ClipboardBulkPlans.MergeDecision.Ready).plan
        assertThat(plan.text).isEqualTo("first\nsecond-a\nsecond-b\nthird")
        assertThat(plan.sources).isEqualTo(4)
        assertThat(plan.skippedMedia).isEqualTo(0)
        assertThat(plan.isPrivate).isFalse()
    }

    @Test
    fun mergeKeepsEachClippingVerbatim() {
        val plan = (ClipboardBulkPlans.planMerge(
            listOf(text("  a\n", 1, 1), text("b  ", 2, 2)), null
        ) as ClipboardBulkPlans.MergeDecision.Ready).plan
        assertThat(plan.text).isEqualTo("  a\n\nb  ")
    }

    @Test
    fun mergeSkipsAndCountsMediaAndNeedsTwoTextClippings() {
        val ready = ClipboardBulkPlans.planMerge(listOf(media(1), text("x", 2, 2), media(3), text("y", 4, 4)), null)
        assertThat((ready as ClipboardBulkPlans.MergeDecision.Ready).plan.skippedMedia).isEqualTo(2)
        assertThat(ready.plan.text).isEqualTo("x\ny")

        assertThat(ClipboardBulkPlans.planMerge(listOf(media(1), text("only", 2, 2)), null))
            .isEqualTo(ClipboardBulkPlans.MergeDecision.TooFewText(textCount = 1, skippedMedia = 1))
        assertThat(ClipboardBulkPlans.planMerge(listOf(media(1), media(2)), null))
            .isEqualTo(ClipboardBulkPlans.MergeDecision.TooFewText(textCount = 0, skippedMedia = 2))
    }

    @Test
    fun mergeOfAnyPrivateClippingIsPrivate() {
        val plan = (ClipboardBulkPlans.planMerge(
            listOf(text("public", 1, 1), text("secret", 2, 2, private = true)), null
        ) as ClipboardBulkPlans.MergeDecision.Ready).plan
        assertThat(plan.isPrivate).isTrue()
    }

    @Test
    fun mergeRespectsTheSizeLimitInUtf8Bytes() {
        val a = text("é".repeat(5), 1, 1)  // 10 bytes
        val b = text("abcd", 2, 2)          // 4 bytes, + 1 newline = 15 bytes
        assertThat(ClipboardBulkPlans.planMerge(listOf(a, b), limitBytes = 15))
            .isInstanceOf(ClipboardBulkPlans.MergeDecision.Ready::class.java)
        assertThat(ClipboardBulkPlans.planMerge(listOf(a, b), limitBytes = 14))
            .isEqualTo(ClipboardBulkPlans.MergeDecision.TooLarge(bytes = 15, limitBytes = 14, skippedMedia = 0))
        // A non-positive limit means "no limit", as the max-item-size setting does.
        assertThat(ClipboardBulkPlans.planMerge(listOf(a, b), limitBytes = 0))
            .isInstanceOf(ClipboardBulkPlans.MergeDecision.Ready::class.java)
    }

    @Test
    fun cleanPlansOnlyChangedTextRowsAndCountsTheRest() {
        val messy = text("Hello  \nWorld\t", 1, 1)
        val clean = text("Already clean", 2, 2)
        val plan = ClipboardBulkPlans.planClean(listOf(messy, clean, media(3)))
        assertThat(plan.edits).containsExactly(messy to "Hello\nWorld")
        assertThat(plan.unchanged).isEqualTo(1)
        assertThat(plan.skippedMedia).isEqualTo(1)
    }

    @Test
    fun actionsOfferOnlyOtherEnabledTabsAsTargets() {
        assertThat(ClipboardBulkPlans.actionsFor(ClipboardTab.HISTORY, pinnedEnabled = true, todosEnabled = true))
            .containsExactly(
                ClipboardSelectionAction.ADD_TO_PINNED, ClipboardSelectionAction.ADD_TO_TODOS,
                ClipboardSelectionAction.MERGE, ClipboardSelectionAction.CLEAN,
            ).inOrder()
        assertThat(ClipboardBulkPlans.actionsFor(ClipboardTab.PINNED, true, true))
            .doesNotContain(ClipboardSelectionAction.ADD_TO_PINNED)
        assertThat(ClipboardBulkPlans.actionsFor(ClipboardTab.TODOS, true, true))
            .doesNotContain(ClipboardSelectionAction.ADD_TO_TODOS)
        assertThat(ClipboardBulkPlans.actionsFor(ClipboardTab.HISTORY, pinnedEnabled = false, todosEnabled = false))
            .containsExactly(ClipboardSelectionAction.MERGE, ClipboardSelectionAction.CLEAN).inOrder()
    }

    @Test
    fun previewShowsLineBreaksAndIsBounded() {
        assertThat(ClipboardBulkPlans.preview("a\nb")).isEqualTo("a ↵ b")
        val long = ClipboardBulkPlans.preview("x".repeat(500), maxChars = 10)
        assertThat(long).isEqualTo("x".repeat(10) + "…")
    }
}
