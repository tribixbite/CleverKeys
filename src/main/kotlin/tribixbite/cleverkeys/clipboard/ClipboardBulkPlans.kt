package tribixbite.cleverkeys.clipboard

import tribixbite.cleverkeys.ClipboardEntry
import tribixbite.cleverkeys.ClipboardSizePolicy
import tribixbite.cleverkeys.ClipboardTab

/** Bulk actions offered for a clipboard selection besides Delete (which has its own button). */
enum class ClipboardSelectionAction { ADD_TO_PINNED, ADD_TO_TODOS, MERGE, CLEAN }

/** Outcome counts of copying a selection into the Pinned or Todos table. */
data class ClipboardCopyResult(val added: Int, val alreadyPresent: Int, val failed: Int)

/** Outcome counts of an in-place Clean. */
data class ClipboardCleanResult(val cleaned: Int, val unchanged: Int, val skippedMedia: Int, val failed: Int)

/**
 * Pure planning for the selection's bulk actions: which actions a tab offers, what Merge would
 * create and what Clean would change. The confirmation dialogs show these plans and the
 * service executes exactly them, so what the user confirmed is what is written.
 */
object ClipboardBulkPlans {

    /**
     * Actions offered for a selection in [tab]. Adding to the tab the rows already live in
     * would only report duplicates, so it is not offered; disabled tabs are never a target.
     */
    fun actionsFor(tab: ClipboardTab, pinnedEnabled: Boolean, todosEnabled: Boolean): List<ClipboardSelectionAction> =
        buildList {
            if (pinnedEnabled && tab != ClipboardTab.PINNED) add(ClipboardSelectionAction.ADD_TO_PINNED)
            if (todosEnabled && tab != ClipboardTab.TODOS) add(ClipboardSelectionAction.ADD_TO_TODOS)
            add(ClipboardSelectionAction.MERGE)
            add(ClipboardSelectionAction.CLEAN)
        }

    /** A merge that can be stored: the text, how many clippings it joins, and its privacy. */
    data class MergePlan(
        val text: String,
        val sources: Int,
        val skippedMedia: Int,
        val isPrivate: Boolean,
    ) {
        val bytes: Long get() = ClipboardSizePolicy.utf8Bytes(text)
    }

    sealed class MergeDecision {
        data class Ready(val plan: MergePlan) : MergeDecision()
        /** Fewer than two text clippings are selected; merging would only copy one. */
        data class TooFewText(val textCount: Int, val skippedMedia: Int) : MergeDecision()
        /** The result would exceed the per-clipping size limit. */
        data class TooLarge(val bytes: Long, val limitBytes: Long, val skippedMedia: Int) : MergeDecision()
    }

    /**
     * Merge order: oldest first by the timestamp each row shows (capture time in History, pin
     * time in Pinned, add time in Todos), ties broken by row id — independent of the selection
     * order, the current search and the page, so the same selection always merges identically.
     */
    fun mergeOrder(entries: Collection<ClipboardEntry>): List<ClipboardEntry> =
        entries.filter { !it.isMedia }.sortedWith(compareBy<ClipboardEntry>({ it.timestamp }, { it.rowId }))

    /**
     * Plan Merge: the text clippings of [entries] in [mergeOrder], joined by a single newline.
     * Media clippings are skipped and counted. The result is private if any source is private,
     * so merging never strips a private clipping's protection. [limitBytes] is the per-clipping
     * size limit in UTF-8 bytes; null or non-positive means unlimited.
     */
    fun planMerge(entries: Collection<ClipboardEntry>, limitBytes: Long?): MergeDecision {
        val texts = mergeOrder(entries)
        val skippedMedia = entries.size - texts.size
        if (texts.size < 2) return MergeDecision.TooFewText(texts.size, skippedMedia)
        val plan = MergePlan(
            text = texts.joinToString("\n") { it.content },
            sources = texts.size,
            skippedMedia = skippedMedia,
            isPrivate = texts.any { it.isPrivate },
        )
        if (limitBytes != null && limitBytes > 0 && plan.bytes > limitBytes) {
            return MergeDecision.TooLarge(plan.bytes, limitBytes, skippedMedia)
        }
        return MergeDecision.Ready(plan)
    }

    /** Clean plan: the text rows whose content [ClipboardTextCleaner] changes, with the result. */
    data class CleanPlan(
        val edits: List<Pair<ClipboardEntry, String>>,
        val unchanged: Int,
        val skippedMedia: Int,
    )

    /** Plan Clean for [entries]; media rows are skipped, rows the transform keeps are unchanged. */
    fun planClean(entries: Collection<ClipboardEntry>): CleanPlan {
        val edits = ArrayList<Pair<ClipboardEntry, String>>()
        var unchanged = 0
        var media = 0
        for (entry in entries) {
            if (entry.isMedia) { media++; continue }
            val cleaned = ClipboardTextCleaner.clean(entry.content)
            // A row cleaned to nothing cannot be stored (blank edits are refused); keep it.
            if (cleaned == entry.content || cleaned.isBlank()) unchanged++ else edits += entry to cleaned
        }
        return CleanPlan(edits, unchanged, media)
    }

    /**
     * One-line preview for a confirmation: line breaks shown as " ↵ " so the joined/cleaned
     * shape is visible, cut to [maxChars] characters with an ellipsis.
     */
    fun preview(text: String, maxChars: Int = 120): String {
        val flat = text.replace("\n", " ↵ ")
        return if (flat.length <= maxChars) flat else flat.take(maxChars).trimEnd() + "…"
    }
}
