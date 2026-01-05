package tribixbite.cleverkeys

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import android.widget.Button
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tribixbite.cleverkeys.onnx.SwipePredictorOrchestrator

/**
 * Dictionary Manager Activity
 * Provides UI for managing dictionary words across multiple sources
 */
class DictionaryManagerActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var searchInput: EditText
    private lateinit var filterSpinner: Spinner
    private lateinit var resetButton: Button
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2

    private lateinit var fragments: List<WordListFragment>
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private var currentSearchQuery = ""

    private val dictionaryImportReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BackupRestoreActivity.ACTION_DICTIONARY_IMPORTED) {
                android.util.Log.d(TAG, "Received dictionary import broadcast, refreshing tabs...")
                refreshAllTabs()
            }
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val COUNT_UPDATE_DELAY_MS = 100L // Delay to ensure fragments have updated
        private const val TAG = "DictionaryManagerActivity"

        // Language display names for tabs
        private val LANGUAGE_NAMES = mapOf(
            "en" to "EN",
            "es" to "ES",
            "fr" to "FR",
            "pt" to "PT",
            "it" to "IT",
            "de" to "DE",
            "nl" to "NL",
            "id" to "ID",
            "ms" to "MS",
            "sw" to "SW",
            "tl" to "TL"
        )
    }

    // Dynamic tab titles based on active languages
    private var tabTitles = mutableListOf<String>()

    enum class FilterType {
        ALL, MAIN, USER, CUSTOM
    }

    private var currentFilter: FilterType = FilterType.ALL

    // Active languages for tab generation
    private var primaryLanguage = "en"
    private var secondaryLanguage: String? = null
    private var multiLangEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dictionary_manager)

        // Read language preferences
        loadLanguagePreferences()

        initializeViews()
        setupToolbar()
        setupViewPager()
        setupSearch()
        setupFilter()
        setupResetButton()

        // Restore state after configuration change (e.g., rotation)
        if (savedInstanceState != null) {
            currentSearchQuery = savedInstanceState.getString("searchQuery", "")
            currentFilter = FilterType.values()[savedInstanceState.getInt("filterType", 0)]
            searchInput.setText(currentSearchQuery)
            filterSpinner.setSelection(currentFilter.ordinal)

            // Reapply search/filter after all fragments load
            searchHandler.postDelayed({
                performSearch(currentSearchQuery)
            }, 400)
        }
    }

    /**
     * Load language preferences from SharedPreferences.
     * Determines which languages are active for tab generation.
     */
    private fun loadLanguagePreferences() {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        primaryLanguage = prefs.getString("pref_primary_language", "en") ?: "en"
        multiLangEnabled = prefs.getBoolean("pref_enable_multilang", false)
        secondaryLanguage = if (multiLangEnabled) {
            val secondary = prefs.getString("pref_secondary_language", "none") ?: "none"
            if (secondary != "none") secondary else null
        } else {
            null
        }

        android.util.Log.d(TAG, "Language prefs: primary=$primaryLanguage, secondary=$secondaryLanguage, multilang=$multiLangEnabled")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("searchQuery", currentSearchQuery)
        outState.putInt("filterType", currentFilter.ordinal)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        searchInput = findViewById(R.id.search_input)
        filterSpinner = findViewById(R.id.filter_spinner)
        resetButton = findViewById(R.id.reset_button)
        tabLayout = findViewById(R.id.tab_layout)
        viewPager = findViewById(R.id.view_pager)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            title = "Dictionary Manager"
            setDisplayHomeAsUpEnabled(true)
        }
    }

    /**
     * Setup ViewPager with language-specific tabs.
     *
     * Tab structure:
     * - Single language mode: Active, Disabled, User Dict, Custom (all for primary language)
     * - Multilang mode: Active [P], Disabled [P], Custom [P], User Dict, Active [S], Disabled [S], Custom [S]
     *
     * Where [P] = primary language code, [S] = secondary language code
     *
     * @since v1.1.86 - Added language-specific tab generation
     */
    private fun setupViewPager() {
        val fragmentList = mutableListOf<WordListFragment>()
        tabTitles.clear()

        val primaryLangLabel = LANGUAGE_NAMES[primaryLanguage] ?: primaryLanguage.uppercase()

        if (secondaryLanguage != null) {
            // Multilang mode: Show language-specific tabs for each language
            val secondaryLangLabel = LANGUAGE_NAMES[secondaryLanguage] ?: secondaryLanguage!!.uppercase()

            // Primary language tabs
            fragmentList.add(WordListFragment.newInstance(WordListFragment.TabType.ACTIVE, primaryLanguage))
            tabTitles.add("Active [$primaryLangLabel]")

            fragmentList.add(WordListFragment.newInstance(WordListFragment.TabType.DISABLED, primaryLanguage))
            tabTitles.add("Disabled [$primaryLangLabel]")

            fragmentList.add(WordListFragment.newInstance(WordListFragment.TabType.CUSTOM, primaryLanguage))
            tabTitles.add("Custom [$primaryLangLabel]")

            // User Dict (global - Android system dictionary is not language-specific)
            fragmentList.add(WordListFragment.newInstance(WordListFragment.TabType.USER))
            tabTitles.add("User Dict")

            // Secondary language tabs
            fragmentList.add(WordListFragment.newInstance(WordListFragment.TabType.ACTIVE, secondaryLanguage))
            tabTitles.add("Active [$secondaryLangLabel]")

            fragmentList.add(WordListFragment.newInstance(WordListFragment.TabType.DISABLED, secondaryLanguage))
            tabTitles.add("Disabled [$secondaryLangLabel]")

            fragmentList.add(WordListFragment.newInstance(WordListFragment.TabType.CUSTOM, secondaryLanguage))
            tabTitles.add("Custom [$secondaryLangLabel]")

        } else {
            // Single language mode: Standard tabs with primary language
            fragmentList.add(WordListFragment.newInstance(WordListFragment.TabType.ACTIVE, primaryLanguage))
            tabTitles.add(if (primaryLanguage != "en") "Active [$primaryLangLabel]" else "Active")

            fragmentList.add(WordListFragment.newInstance(WordListFragment.TabType.DISABLED, primaryLanguage))
            tabTitles.add(if (primaryLanguage != "en") "Disabled [$primaryLangLabel]" else "Disabled")

            fragmentList.add(WordListFragment.newInstance(WordListFragment.TabType.USER))
            tabTitles.add("User Dict")

            fragmentList.add(WordListFragment.newInstance(WordListFragment.TabType.CUSTOM, primaryLanguage))
            tabTitles.add(if (primaryLanguage != "en") "Custom [$primaryLangLabel]" else "Custom")
        }

        fragments = fragmentList

        // Setup ViewPager2 adapter
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = fragments.size
            override fun createFragment(position: Int) = fragments[position]
        }

        // CRITICAL: Set offscreenPageLimit to keep all fragments in memory
        // Without this, ViewPager2 only loads visible tab + 1 adjacent tab
        // This causes counts to show 0 for unvisited tabs after rotation
        viewPager.offscreenPageLimit = fragments.size - 1

        // Connect TabLayout with ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()

        // Enable tab scrolling for multilang mode with many tabs
        tabLayout.tabMode = if (fragments.size > 4) TabLayout.MODE_SCROLLABLE else TabLayout.MODE_FIXED
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""

                // Cancel previous search
                searchRunnable?.let { searchHandler.removeCallbacks(it) }

                // Schedule new search with debounce
                searchRunnable = Runnable {
                    currentSearchQuery = query
                    performSearch(query)
                }.also {
                    searchHandler.postDelayed(it, SEARCH_DEBOUNCE_MS)
                }
            }
        })
    }

    private fun setupFilter() {
        val filterOptions = FilterType.values().map { it.name.lowercase().capitalize() }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, filterOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        filterSpinner.adapter = adapter

        filterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyFilter(FilterType.values()[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupResetButton() {
        resetButton.setOnClickListener {
            resetSearch()
        }
    }

    private fun performSearch(query: String) {
        val sourceFilter = when (currentFilter) {
            FilterType.ALL -> null
            FilterType.MAIN -> WordSource.MAIN
            FilterType.USER -> WordSource.USER
            FilterType.CUSTOM -> WordSource.CUSTOM
        }

        // Apply search to all fragments with source filter
        fragments.forEach { it.filter(query, sourceFilter) }

        // Update tab counts after search completes
        // Small delay to ensure fragments have updated their counts
        searchHandler.postDelayed({
            updateTabCounts()
        }, COUNT_UPDATE_DELAY_MS)
    }

    /**
     * Update tab counts to show result numbers
     * Modular design: automatically works with any number of tabs
     */
    private fun updateTabCounts() {
        for (i in fragments.indices) {
            val tab = tabLayout.getTabAt(i) ?: continue
            val count = fragments[i].getFilteredCount()
            val title = tabTitles.getOrElse(i) { "Tab $i" }
            tab.text = "$title\n($count)"
        }
    }

    /**
     * Called by fragments when they finish loading or filtering data
     * Updates tab counts to reflect current state
     */
    fun onFragmentDataLoaded() {
        // Update counts immediately when fragments finish loading
        // Small delay to ensure the fragment's adapter has been updated
        searchHandler.postDelayed({
            updateTabCounts()
        }, 50)
    }

    private fun applyFilter(filterType: FilterType) {
        currentFilter = filterType
        performSearch(currentSearchQuery)
    }

    private fun resetSearch() {
        searchInput.setText("")
        filterSpinner.setSelection(0)  // Reset to "All"
        currentSearchQuery = ""
        performSearch("")
    }

    /**
     * Called by fragments when words are modified to refresh other tabs
     */
    fun refreshAllTabs() {
        fragments.forEach { it.refresh() }

        // Update tab counts to reflect changes
        searchHandler.postDelayed({
            updateTabCounts()
        }, COUNT_UPDATE_DELAY_MS)

        // Reload predictions to reflect dictionary changes
        reloadPredictions()
    }

    /**
     * Reload custom/user/disabled words in both typing and swipe predictors
     * PERFORMANCE: Only reloads small dynamic sets, not main dictionaries
     */
    private fun reloadPredictions() {
        try {
            // Signal typing predictions to reload on next prediction (lazy reload for performance)
            WordPredictor.signalReloadNeeded()

            // Reload swipe beam search vocabulary immediately (singleton, one-time cost)
            val swipePredictor = SwipePredictorOrchestrator.getInstance(this)
            swipePredictor.reloadVocabulary()

            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("DictionaryManagerActivity", "Reloaded predictions after dictionary changes")
            }
        } catch (e: Exception) {
            android.util.Log.e("DictionaryManagerActivity", "Failed to reload predictions", e)
        }
    }

    override fun onResume() {
        super.onResume()
        // Register receiver for dictionary import notifications
        LocalBroadcastManager.getInstance(this).registerReceiver(
            dictionaryImportReceiver, IntentFilter(BackupRestoreActivity.ACTION_DICTIONARY_IMPORTED)
        )
    }

    override fun onPause() {
        super.onPause()
        // Unregister receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(dictionaryImportReceiver)
    }
}

// Extension function to capitalize first letter
private fun String.capitalize(): String {
    return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}