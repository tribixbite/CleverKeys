package tribixbite.cleverkeys

import tribixbite.cleverkeys.swipe.ctc.CtcDecodableLength

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/**
 * Fragment displaying a list of words
 */
class WordListFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var dataSource: DictionaryDataSource
    private lateinit var adapter: BaseWordAdapter

    private var tabType: TabType = TabType.ACTIVE
    private var languageCode: String? = null  // Language code for language-specific tabs (v1.1.86)
    private var searchJob: kotlinx.coroutines.Job? = null  // Track search coroutine for cancellation

    enum class TabType {
        ACTIVE, DISABLED, USER, CUSTOM
    }

    companion object {
        private const val ARG_TAB_TYPE = "tab_type"
        private const val ARG_LANGUAGE_CODE = "language_code"

        // #96: per-tab search context persisted across recreation (rotation, resize,
        // split-screen). The activity saves its own copy for the toolbar widgets, but the
        // fragment must self-restore: FragmentStateAdapter recreates it with default fields,
        // and the activity's delayed re-dispatch is a race the unfiltered initial load can win.
        private const val STATE_SEARCH_QUERY = "state_search_query"
        private const val STATE_SORT_TYPE = "state_sort_type"
        private const val STATE_LAYOUT_MANAGER = "state_layout_manager"

        /**
         * Create a new WordListFragment instance.
         *
         * @param tabType The type of word list to display
         * @param languageCode Optional language code for language-specific tabs (e.g., "en", "es").
         *                     If null, uses global/legacy storage.
         * @since v1.1.86 - Added languageCode parameter
         */
        fun newInstance(tabType: TabType, languageCode: String? = null): WordListFragment {
            val fragment = WordListFragment()
            val args = Bundle()
            args.putInt(ARG_TAB_TYPE, tabType.ordinal)
            languageCode?.let { args.putString(ARG_LANGUAGE_CODE, it) }
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tabType = TabType.values()[arguments?.getInt(ARG_TAB_TYPE) ?: 0]
        languageCode = arguments?.getString(ARG_LANGUAGE_CODE)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_word_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recycler_view)
        emptyText = view.findViewById(R.id.empty_text)
        loadingProgress = view.findViewById(R.id.loading_progress)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // #96: restore the saved search context BEFORE the initial load so the first list a
        // recreated fragment shows is already the filtered one. Restoring here (rather than
        // relying on DictionaryManagerActivity's delayed performSearch re-dispatch) closes the
        // race where an unfiltered initial load lands after the filtered re-dispatch and
        // clobbers it — the list would show every word frequency-sorted while the search box
        // still held the query.
        if (savedInstanceState != null) {
            currentSearchQuery = savedInstanceState.getString(STATE_SEARCH_QUERY, "") ?: ""
            currentSortType = DictionaryManagerActivity.SortType.values()
                .getOrElse(savedInstanceState.getInt(STATE_SORT_TYPE, 0)) {
                    DictionaryManagerActivity.SortType.FREQ
                }
            pendingLayoutManagerState = savedInstanceState.getParcelable(STATE_LAYOUT_MANAGER)
        }

        initializeDataSource()
        setupAdapter()
        loadWords()
    }

    // #96: the fragment owns its search context across recreation. The scroll position is
    // captured as the layout manager's own saved state; it cannot auto-restore through the
    // view hierarchy because the list content arrives asynchronously (the RecyclerView is
    // empty at view-state-restore time), so filter() re-applies it once data lands.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SEARCH_QUERY, currentSearchQuery)
        outState.putInt(STATE_SORT_TYPE, currentSortType.ordinal)
        if (::recyclerView.isInitialized) {
            outState.putParcelable(STATE_LAYOUT_MANAGER, recyclerView.layoutManager?.onSaveInstanceState())
        }
    }

    /**
     * Initialize the data source based on tab type and language code.
     *
     * @since v1.1.86 - Uses language-specific storage when languageCode is provided
     */
    private fun initializeDataSource() {
        val defaultPrefs = DirectBootAwarePreferences.get_shared_preferences(requireContext())
        // Use language-specific DisabledDictionarySource when languageCode is provided
        val disabledSource = DisabledDictionarySource(defaultPrefs, languageCode)

        dataSource = when (tabType) {
            // v1.1.89: Pass language code to load correct dictionary (not always English)
            TabType.ACTIVE -> MainDictionarySource(requireContext(), disabledSource, languageCode ?: "en")
            TabType.DISABLED -> disabledSource
            TabType.USER -> UserDictionarySource(requireContext(), requireContext().contentResolver)
            TabType.CUSTOM -> {
                // v1.1.87: Use language-specific custom words storage
                // This matches OptimizedVocabulary's storage format for swipe prediction
                val customPrefs = DirectBootAwarePreferences.get_shared_preferences(requireContext())
                CustomDictionarySource(customPrefs, languageCode ?: "en")
            }
        }
    }

    private fun setupAdapter() {
        adapter = when (tabType) {
            TabType.CUSTOM -> {
                WordEditableAdapter(
                    onEdit = { word -> showEditDialog(word) },
                    onDelete = { word -> deleteWord(word) },
                    onAdd = { showAddDialog() }
                )
            }
            else -> {
                WordToggleAdapter { word, enabled ->
                    toggleWord(word, enabled)
                }
            }
        }

        recyclerView.adapter = adapter
    }

    private fun loadWords() {
        // Guard against calling before view is created
        if (!::loadingProgress.isInitialized) return

        loadingProgress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE

        // #96: the load MUST go through filter() — never a private unfiltered coroutine.
        // filter() (a) applies the current (possibly restored) search context instead of
        // resetting to the full frequency-sorted list, and (b) tracks the load in searchJob,
        // so a later dispatch (typing, the activity's post-recreation re-dispatch) cancels
        // this one. Before this delegation the initial load raced those dispatches and,
        // landing last after a cold 50k-word parse, clobbered the filtered result.
        filter(currentSearchQuery, currentSortType)
    }

    private var currentSortType: DictionaryManagerActivity.SortType = DictionaryManagerActivity.SortType.FREQ
    private var currentSearchQuery: String = ""  // #96: Track search query for refresh()
    // #96: scroll position captured in onSaveInstanceState, re-applied by filter() once the
    // restored list content is actually set (see onSaveInstanceState doc).
    private var pendingLayoutManagerState: android.os.Parcelable? = null

    fun filter(query: String, sortType: DictionaryManagerActivity.SortType = DictionaryManagerActivity.SortType.FREQ) {
        if (!::adapter.isInitialized) return
        currentSearchQuery = query  // #96: Persist query so refresh() can reapply
        currentSortType = sortType

        // Cancel previous search to prevent multiple concurrent operations
        searchJob?.cancel()

        // Use DictionaryDataSource.searchWords() which has prefix indexing
        // instead of in-memory filtering of 50k words on main thread
        searchJob = lifecycleScope.launch {
            try {
                // Normalize query: trim whitespace and treat pure whitespace as blank
                val normalizedQuery = query.trim()

                val rawWords = if (normalizedQuery.isBlank()) {
                    // No search - show all words from this tab's data source
                    dataSource.getAllWords()
                } else {
                    // Has search query - use prefix indexing
                    dataSource.searchWords(normalizedQuery)
                }

                // ACTIVE tab should only show enabled words (disabled ones appear in DISABLED tab)
                val words = if (tabType == TabType.ACTIVE) {
                    rawWords.filter { it.enabled }
                } else {
                    rawWords
                }

                // v1.2.7: Apply sorting based on sort type
                val sortedWords = sortWordsForDisplay(words, sortType, normalizedQuery)

                adapter.setWords(sortedWords)
                updateEmptyState()

                // #96: re-apply the saved scroll position once the restored content is in the
                // adapter. One-shot: consumed on the first successful population after
                // recreation; later filters scroll naturally.
                pendingLayoutManagerState?.let { state ->
                    recyclerView.layoutManager?.onRestoreInstanceState(state)
                    pendingLayoutManagerState = null
                }
                loadingProgress.visibility = View.GONE

                // Notify activity to update tab counts after filter completes
                (activity as? DictionaryManagerActivity)?.onFragmentDataLoaded()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Search was cancelled - this is expected, don't log as error. The loading
                // indicator (if shown by loadWords) is left up: the superseding filter job
                // owns hiding it.
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    android.util.Log.d("WordListFragment", "Search cancelled")
                }
            } catch (e: Exception) {
                android.util.Log.e("WordListFragment", "Error filtering words", e)
                // Preserve loadWords()' historical error surface now that it delegates here.
                emptyText.text = getString(R.string.dict_error_loading, e.message ?: "")
                emptyText.visibility = View.VISIBLE
                loadingProgress.visibility = View.GONE
            }
        }
    }

    fun getFilteredCount(): Int {
        if (!::adapter.isInitialized) return 0
        return adapter.getFilteredCount()
    }

    private fun updateEmptyState() {
        if (!::adapter.isInitialized) return
        if (adapter.getFilteredCount() == 0) {
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun toggleWord(word: DictionaryWord, enabled: Boolean) {
        lifecycleScope.launch {
            try {
                dataSource.toggleWord(word.word, enabled)
                filter(currentSearchQuery, currentSortType)  // #96: Preserve search state
                // Notify parent activity to refresh other tabs
                (activity as? DictionaryManagerActivity)?.refreshAllTabs()
            } catch (e: Exception) {
                // Show error
                AlertDialog.Builder(requireContext())
                    .setTitle("Error")
                    .setMessage("Failed to toggle word: ${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun deleteWord(word: DictionaryWord) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Word")
            .setMessage("Delete '${word.word}'?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        dataSource.deleteWord(word.word)
                        filter(currentSearchQuery, currentSortType)  // #96: Preserve search state
                        // Notify parent activity to refresh predictions
                        (activity as? DictionaryManagerActivity)?.refreshAllTabs()
                    } catch (e: Exception) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Error")
                            .setMessage("Failed to delete word: ${e.message}")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // SetTextI18n: the frequency EditText is prefilled with a raw integer that is
    // parsed back via toIntOrNull(); locale-formatting it (grouping separators)
    // would break parsing, so the plain digit string is intentional.
    @SuppressLint("SetTextI18n")
    private fun showAddDialog() {
        // Create layout with word and frequency inputs
        val layout = android.widget.LinearLayout(requireContext())
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(60, 40, 60, 20)

        val wordInput = EditText(requireContext())
        wordInput.inputType = InputType.TYPE_CLASS_TEXT
        wordInput.hint = "Enter word"
        layout.addView(wordInput)

        // Wave U2: the stored scale is 1..255 (UserWordFrequency; AOSP user-dictionary
        // convention), and every ranking consumer calibrates it onto its own base scale.
        // The old "1-10000" hint was fiction — no consumer read that scale — and the old
        // default of 100 ranked a fresh custom word BELOW the entire base dictionary in
        // the swipe lexicon prior. Default 255: a user adding a word wants it to WIN;
        // they can lower it here to demote it.
        val freqInput = EditText(requireContext())
        freqInput.inputType = InputType.TYPE_CLASS_NUMBER
        freqInput.hint = "Frequency (1-255, higher wins)"
        freqInput.setText(UserWordFrequency.DEFAULT.toString())
        freqInput.selectAll()
        layout.addView(freqInput)

        AlertDialog.Builder(requireContext())
            .setTitle("Add Custom Word")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val word = wordInput.text.toString().trim()
                val freqText = freqInput.text.toString().trim()
                val frequency = freqText.toIntOrNull() ?: UserWordFrequency.DEFAULT

                if (word.isNotBlank()) {
                    lifecycleScope.launch {
                        try {
                            dataSource.addWord(
                                word,
                                frequency.coerceIn(UserWordFrequency.MIN, UserWordFrequency.MAX)
                            )
                            loadWords()
                            // Notify parent activity to refresh predictions
                            (activity as? DictionaryManagerActivity)?.refreshAllTabs()

                            // The word IS added — tap typing and the geometric engine handle any
                            // length. But the CTC encoder emits a fixed 32 frames, and a CTC path
                            // needs one frame per letter plus a blank between doubled letters, so
                            // past that budget NO alignment exists and the beam can never produce
                            // it. Without this the word would simply never appear on a swipe, with
                            // nothing to distinguish that from a bad gesture.
                            if (!CtcDecodableLength.isDecodable(word)) {
                                AlertDialog.Builder(requireContext())
                                    .setTitle(R.string.dict_word_too_long_for_swipe_title)
                                    .setMessage(
                                        resources.getQuantityString(
                                            R.plurals.dict_word_too_long_for_swipe_msg,
                                            CtcDecodableLength.EMISSION_FRAMES,
                                            word,
                                            CtcDecodableLength.framesRequired(word),
                                            CtcDecodableLength.EMISSION_FRAMES
                                        )
                                    )
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show()
                            }
                        } catch (e: Exception) {
                            AlertDialog.Builder(requireContext())
                                .setTitle("Error")
                                .setMessage("Failed to add word: ${e.message}")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // SetTextI18n: same as showAddDialog — the frequency field holds a raw integer
    // that must stay machine-parseable (toIntOrNull), so no locale formatting.
    @SuppressLint("SetTextI18n")
    private fun showEditDialog(word: DictionaryWord) {
        // Create layout with word and frequency inputs
        val layout = android.widget.LinearLayout(requireContext())
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(60, 40, 60, 20)

        val wordInput = EditText(requireContext())
        wordInput.inputType = InputType.TYPE_CLASS_TEXT
        wordInput.hint = "Word"
        wordInput.setText(word.word)
        wordInput.selectAll()
        layout.addView(wordInput)

        // Wave U2: same 1..255 stored scale as the Add dialog. Legacy stored values
        // above 255 (old 1-10000 dialog era) prefill coerced — saving writes the
        // calibrated-scale equivalent without touching words the user doesn't edit.
        val freqInput = EditText(requireContext())
        freqInput.inputType = InputType.TYPE_CLASS_NUMBER
        freqInput.hint = "Frequency (1-255, higher wins)"
        freqInput.setText(word.frequency.coerceIn(UserWordFrequency.MIN, UserWordFrequency.MAX).toString())
        layout.addView(freqInput)

        AlertDialog.Builder(requireContext())
            .setTitle("Edit Word")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newWord = wordInput.text.toString().trim()
                val freqText = freqInput.text.toString().trim()
                val newFrequency = freqText.toIntOrNull() ?: word.frequency

                if (newWord.isNotBlank()) {
                    lifecycleScope.launch {
                        try {
                            dataSource.updateWord(
                                word.word, newWord,
                                newFrequency.coerceIn(UserWordFrequency.MIN, UserWordFrequency.MAX)
                            )
                            loadWords()
                            // Notify parent activity to refresh predictions
                            (activity as? DictionaryManagerActivity)?.refreshAllTabs()
                        } catch (e: Exception) {
                            AlertDialog.Builder(requireContext())
                                .setTitle("Error")
                                .setMessage("Failed to update word: ${e.message}")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun refresh() {
        // Re-sync cached enabled states from persistent storage before re-filtering.
        // Fixes cross-source coherence: DisabledDictionarySource.toggleWord() updates
        // SharedPreferences but MainDictionarySource's cached DictionaryWord.enabled
        // flags remain stale until this call.
        dataSource.onRefresh()
        // #96: Reapply current search/sort state instead of loading unfiltered
        filter(currentSearchQuery, currentSortType)
    }
}

/**
 * Order [words] for display under [sortType].
 *
 * [normalizedQuery] is the trimmed contents of the Dictionary Manager search box; only
 * [DictionaryManagerActivity.SortType.MATCH] reads it, and with a blank query MATCH is defined
 * to fall back to frequency order (there is no relevance to rank by).
 *
 * Extracted VERBATIM from [WordListFragment.filter] (2026-09-03) so the four orderings the
 * v1.2.6 / v1.2.8 release notes promise — "Sort by Frequency/Match/A-Z/Z-A" — can be pinned
 * without a Fragment, a lifecycle scope or a data source. Pure: no Android types, no state,
 * no I/O. Behaviour is unchanged; `filter` now calls this and does nothing else differently.
 * Pinned by `DictionarySortOrderTest`.
 */
internal fun sortWordsForDisplay(
    words: List<DictionaryWord>,
    sortType: DictionaryManagerActivity.SortType,
    normalizedQuery: String,
): List<DictionaryWord> = when (sortType) {
    DictionaryManagerActivity.SortType.FREQ -> {
        // Sort by frequency (highest first) - default
        words.sortedByDescending { it.frequency }
    }
    DictionaryManagerActivity.SortType.MATCH -> {
        // Sort by match quality: exact match first, then prefix match, then by frequency
        if (normalizedQuery.isNotBlank()) {
            words.sortedWith(compareBy(
                // Exact match gets priority 0, prefix match gets 1, others get 2
                { word ->
                    when {
                        word.word.equals(normalizedQuery, ignoreCase = true) -> 0
                        word.word.startsWith(normalizedQuery, ignoreCase = true) -> 1
                        else -> 2
                    }
                },
                // Secondary sort by frequency (descending, so negate)
                { -it.frequency }
            ))
        } else {
            // No query, fall back to frequency sort
            words.sortedByDescending { it.frequency }
        }
    }
    DictionaryManagerActivity.SortType.A_Z -> {
        // Alphabetical ascending
        words.sortedBy { it.word.lowercase() }
    }
    DictionaryManagerActivity.SortType.Z_A -> {
        // Alphabetical descending
        words.sortedByDescending { it.word.lowercase() }
    }
}
