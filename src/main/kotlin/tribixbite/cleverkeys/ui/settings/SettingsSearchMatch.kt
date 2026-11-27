package tribixbite.cleverkeys.ui.settings

import java.text.Normalizer

/**
 * Pure matching rules for the settings search (unit-tested in `runPureTests`).
 *
 * Until 2026-09-30 the search matched only the English titles and keywords, and each control
 * registered its scroll position under a slug of its VISIBLE title (`[^a-z0-9]` → `_`), which is
 * empty or partial for Persian, Japanese, Russian, … titles. Search results under those UI
 * languages opened the right section but never scrolled to the control.
 *
 * Now a result matches the query against the title in the UI language first, then the English
 * title and the English keywords (users who know the English names still find settings), and
 * scroll targets are the locale-independent setting ids from the generated index (the title's
 * string-resource name), looked up by the control's visible title via [idsByTitle].
 */
internal object SettingsSearchMatch {

    /** Combining marks (accents, harakat, dakuten after NFD) — ignored when matching. */
    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /**
     * Folds text for matching: NFD without combining marks (so "rezges" finds "Rezgés"), lower
     * case, zero-width joiners removed (Persian users type ZWNJ inconsistently), and the Arabic
     * yeh/kaf that Arabic keyboard layouts produce mapped to the Persian letters.
     */
    fun fold(text: String): String =
        COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
            .lowercase()
            .replace("\u200C", "").replace("\u200D", "")
            .replace('\u064A', '\u06CC') // ARABIC YEH -> FARSI YEH
            .replace('\u0649', '\u06CC') // ALEF MAKSURA -> FARSI YEH
            .replace('\u0643', '\u06A9') // ARABIC KAF -> KEHEH
            .trim()

    /** True when [query] occurs in the localized [title], the [englishTitle] or a [keywords] entry. */
    fun matches(query: String, title: String, englishTitle: String, keywords: List<String>): Boolean {
        val q = fold(query)
        if (q.isEmpty()) return false
        return fold(title).contains(q) || fold(englishTitle).contains(q) ||
            keywords.any { fold(it).contains(q) }
    }

    /**
     * Visible (localized) title -> the setting ids of the index entries with that title. A control
     * registers its scroll position under every id its title maps to, so two settings whose
     * titles happen to translate identically both stay reachable.
     */
    fun idsByTitle(entries: List<Pair<String, String>>): Map<String, List<String>> =
        entries.groupBy({ it.first }, { it.second }).mapValues { (_, ids) -> ids.distinct() }

    /**
     * One poll of a search result's scroll wait: the id to scroll to, or null to keep waiting.
     * [previous] and [current] are the registered positions of the last two polls (absent = the
     * control is not composed).
     *
     * The [target] wins once its position is registered and unchanged across two polls (the
     * expand animation reports in-flight offsets until then). A control whose revealing state is
     * off is never composed, so while the target is absent the first REGISTERED of [gates] (the
     * controls that reveal it, innermost first) is chosen once it too is settled. The gates
     * compose in the same frame as the target would, so a settled gate with no target means
     * the target is hidden.
     */
    fun settledScrollTarget(
        target: String,
        gates: List<String>,
        previous: Map<String, Int>,
        current: Map<String, Int>,
    ): String? {
        val position = current[target]
        if (position != null) return target.takeIf { previous[target] == position }
        val gate = gates.firstOrNull { current[it] != null } ?: return null
        return gate.takeIf { previous[gate] == current[gate] }
    }

    /** Where to scroll when the wait times out: the target if registered, else its first
     *  registered gate, else the target (scrolling to an unregistered id does nothing). */
    fun timedOutScrollTarget(target: String, gates: List<String>, current: Map<String, Int>): String =
        if (current[target] != null) target else gates.firstOrNull { current[it] != null } ?: target

    /**
     * Revealing controls the generator cannot see, by setting id, innermost first. The generator
     * reads one `if` at a time in one file, so a control gets no generated gate when
     * - its `if` tests a LOCAL value no control sets (`germanSelected`), or
     * - it sits in a composable CALLED inside the `if` (the generator sees the call site's `if`
     *   only for controls written inline there), or in an `else` or `when` branch.
     * Such a result landed on the section and scrolled nowhere. Each list names the controls that
     * bring it on screen; `SettingsSearchReachabilityTest` walks the real nesting and fails for
     * any entry left without one.
     *
     * # TODO: teach scripts/generate_settings_search_index.py call-site, `else` and `when`
     * nesting; most of this table would then be generated.
     */
    private val CONDITION_GATES: Map<String, List<String>> = buildMap {
        // The Swiss spelling switch shows while German is selected (`germanSelected`); German is
        // chosen in Primary Language, which itself shows only with Multi-Language on (2026-11-26).
        put("multilang_swiss_spelling_title", listOf("multilang_primary_title", "multilang_enable_title"))
        // LearningDataManagerBlock (Max Learned Words) is called inside `if (wordPredictionEnabled)`
        // of InputBehaviorSection; its result also opens the Advanced panel (2026-11-26).
        put("input_personalization_max_words_title", listOf("input_enable_predictions_title"))
        // "Vertical Speed" is the `else` of `if (followFinger)`, a local value of the
        // Selection Delete mode dropdown.
        put("gesture_vertical_speed_title", listOf("gesture_selection_delete_mode_title"))
        // AutocorrectRankingAndTuning is called inside `if (autoCorrectEnabled)`.
        for (id in AUTOCORRECT_TUNING_IDS) put(id, listOf(AUTOCORRECT_SWITCH))
        // Band / channel controls are the `when (tuning.ranking)` branches of that composable:
        // the Correction Ranking dropdown picks the branch.
        for (id in AUTOCORRECT_RANKING_BRANCH_IDS) put(id, listOf(AUTOCORRECT_RANKING, AUTOCORRECT_SWITCH))
    }

    /**
     * Revealing controls for a condition INSIDE the generated ones, so they go first: the result
     * lands on the first gate on screen ([settledScrollTarget]), and an outer gate is on screen
     * whenever the inner condition alone is off (Fable audit D5, 2026-11-26).
     *
     * - The Quick Toggle's alternate primary shows only while another language is available
     *   besides the primary (`altPrimaryOptions`); the primary is chosen in Primary Language.
     */
    private val INNER_GATES: Map<String, List<String>> = mapOf(
        "multilang_alternate_primary_title" to listOf("multilang_primary_title"),
    )

    /**
     * Generated gates that name a control which only READS the hiding state, by setting id: the
     * generator takes the nearest earlier control mentioning it ("Short swipes only", disabled by
     * the same switch), not the control that sets it ("Enable short gestures").
     *
     * # TODO: have the generator prefer the control whose callbacks assign the state.
     */
    private val GENERATED_GATE_FIXES: Map<String, Map<String, String>> = buildMap {
        val shortGestures = mapOf("gesture_short_swipes_only_title" to "gesture_enable_short_title")
        put("gesture_min_distance_title", shortGestures)
        put("gesture_max_distance_title", shortGestures)
    }

    /**
     * Rows hidden by a switch in ANOTHER section, by setting id, with that switch's gate key
     * ([SearchableSetting.gatedBy], resolved by `isGateEnabled`). A result opens only its own
     * section, so no gate there can reveal such a row; while the switch is off the result is
     * redirected to it instead (`executeSearchAction`), its section opened.
     *
     * - "Backspace Undo Swipe" (Input) shows only while swipe typing (Swipe Typing) is on.
     */
    private val CROSS_SECTION_GATES: Map<String, String> = mapOf(
        "input_backspace_undo_swipe_title" to "swipe_typing",
    )

    /** The gate key of [settingId] when another section's switch hides it, else null. */
    fun crossSectionGateFor(settingId: String): String? = CROSS_SECTION_GATES[settingId]

    /**
     * The gates of the setting [settingId], innermost first: the hand-kept inner ones, the
     * [generated] ones (corrected where they name the wrong control), then the hand-kept outer
     * ones. `SettingsSearchReachabilityTest` checks that, with any one hiding condition off, the
     * first gate on screen is the control that sets it.
     */
    fun gatesFor(settingId: String, generated: List<String>): List<String> {
        val fixes = GENERATED_GATE_FIXES[settingId]
        val corrected = if (fixes == null) generated else generated.map { fixes[it] ?: it }
        return (INNER_GATES[settingId].orEmpty() + corrected + CONDITION_GATES[settingId].orEmpty()).distinct()
    }
}

/** The "Enable Auto-Correction" switch and the "Correction Ranking" dropdown (setting ids). */
private const val AUTOCORRECT_SWITCH = "autocorrect_enable_title"
private const val AUTOCORRECT_RANKING = "autocorrect_ranking_title"

/** Controls written directly in AutoCorrectionSection's `AutocorrectRankingAndTuning`. */
private val AUTOCORRECT_TUNING_IDS = listOf(
    AUTOCORRECT_RANKING,
    "autocorrect_min_exact_ratio_title",
    "autocorrect_max_substitutions_title",
    "autocorrect_length_edit_budget_title",
    "autocorrect_elongation_collapse_title",
    "autocorrect_inflection_guard_title",
    "autocorrect_inflection_guard_min_stem_title",
    "autocorrect_channel_inflection_cost_title",
)

/** Controls of the `BandRankingControls` / `ChannelRankingControls` branches. */
private val AUTOCORRECT_RANKING_BRANCH_IDS = listOf(
    "autocorrect_band_gap_title",
    "autocorrect_transposition_penalty_title",
    "autocorrect_dropped_double_title",
    "autocorrect_dropped_double_margin_title",
    "autocorrect_channel_distance_cost_title",
    "autocorrect_channel_indel_cost_title",
    "autocorrect_channel_double_cost_title",
    "autocorrect_channel_transposition_cost_title",
    "autocorrect_channel_frequency_weight_title",
    "autocorrect_channel_alias_bonus_title",
    "autocorrect_channel_short_deletion_cost_title",
    "autocorrect_channel_max_correction_cost_title",
    "autocorrect_unfinished_word_mode_title",
    "autocorrect_channel_completion_cost_title",
    "autocorrect_short_deletion_scope_title",
)
