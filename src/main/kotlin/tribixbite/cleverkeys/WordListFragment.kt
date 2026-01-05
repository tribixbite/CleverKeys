package tribixbite.cleverkeys

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

        initializeDataSource()
        setupAdapter()
        loadWords()
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

        lifecycleScope.launch {
            try {
                val words = dataSource.getAllWords()
                adapter.setWords(words)
                updateEmptyState()
                // Notify activity to update tab counts after load completes
                (activity as? DictionaryManagerActivity)?.onFragmentDataLoaded()
            } catch (e: Exception) {
                emptyText.text = "Error loading words: ${e.message}"
                emptyText.visibility = View.VISIBLE
            } finally {
                loadingProgress.visibility = View.GONE
            }
        }
    }

    private var currentSourceFilter: WordSource? = null

    fun filter(query: String, sourceFilter: WordSource? = null) {
        if (!::adapter.isInitialized) return
        currentSourceFilter = sourceFilter

        // Cancel previous search to prevent multiple concurrent operations
        searchJob?.cancel()

        // Use DictionaryDataSource.searchWords() which has prefix indexing
        // instead of in-memory filtering of 50k words on main thread
        searchJob = lifecycleScope.launch {
            try {
                // Normalize query: trim whitespace and treat pure whitespace as blank
                val normalizedQuery = query.trim()

                val words = if (normalizedQuery.isBlank() && sourceFilter == null) {
                    // No search, no filter - show all words from this tab's data source
                    dataSource.getAllWords()
                } else if (normalizedQuery.isBlank() && sourceFilter != null) {
                    // No search query, but source filter applied - get all and filter by source
                    dataSource.getAllWords().filter { it.source == sourceFilter }
                } else {
                    // Has search query - use prefix indexing
                    val searchResults = dataSource.searchWords(normalizedQuery)

                    // Apply source filter if needed
                    if (sourceFilter != null) {
                        searchResults.filter { it.source == sourceFilter }
                    } else {
                        searchResults
                    }
                }

                adapter.setWords(words)
                updateEmptyState()
                // Notify activity to update tab counts after filter completes
                (activity as? DictionaryManagerActivity)?.onFragmentDataLoaded()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Search was cancelled - this is expected, don't log as error
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    android.util.Log.d("WordListFragment", "Search cancelled")
                }
            } catch (e: Exception) {
                android.util.Log.e("WordListFragment", "Error filtering words", e)
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
                loadWords()  // Reload to reflect changes
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
                        loadWords()
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

    private fun showAddDialog() {
        // Create layout with word and frequency inputs
        val layout = android.widget.LinearLayout(requireContext())
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(60, 40, 60, 20)

        val wordInput = EditText(requireContext())
        wordInput.inputType = InputType.TYPE_CLASS_TEXT
        wordInput.hint = "Enter word"
        layout.addView(wordInput)

        val freqInput = EditText(requireContext())
        freqInput.inputType = InputType.TYPE_CLASS_NUMBER
        freqInput.hint = "Frequency (1-10000)"
        freqInput.setText("100")
        freqInput.selectAll()
        layout.addView(freqInput)

        AlertDialog.Builder(requireContext())
            .setTitle("Add Custom Word")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val word = wordInput.text.toString().trim()
                val freqText = freqInput.text.toString().trim()
                val frequency = freqText.toIntOrNull() ?: 100

                if (word.isNotBlank()) {
                    lifecycleScope.launch {
                        try {
                            dataSource.addWord(word, frequency.coerceIn(1, 10000))
                            loadWords()
                            // Notify parent activity to refresh predictions
                            (activity as? DictionaryManagerActivity)?.refreshAllTabs()
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

        val freqInput = EditText(requireContext())
        freqInput.inputType = InputType.TYPE_CLASS_NUMBER
        freqInput.hint = "Frequency (1-10000)"
        freqInput.setText(word.frequency.toString())
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
                            dataSource.updateWord(word.word, newWord, newFrequency.coerceIn(1, 10000))
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
        loadWords()
    }
}
