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
        private val TAB_TITLES = listOf("Active", "Disabled", "User Dict", "Custom")
        private const val COUNT_UPDATE_DELAY_MS = 100L // Delay to ensure fragments have updated
        private const val TAG = "DictionaryManagerActivity"
    }

    enum class FilterType {
        ALL, MAIN, USER, CUSTOM
    }

    private var currentFilter: FilterType = FilterType.ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dictionary_manager)

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
            // With offscreenPageLimit set, all 4 fragments will load immediately
            searchHandler.postDelayed({
                performSearch(currentSearchQuery)
            }, 400)  // Delay to ensure all 4 fragments created and data loaded
        }
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

    private fun setupViewPager() {
        // Create fragments for each tab
        fragments = listOf(
            WordListFragment.newInstance(WordListFragment.TabType.ACTIVE),
            WordListFragment.newInstance(WordListFragment.TabType.DISABLED),
            WordListFragment.newInstance(WordListFragment.TabType.USER),
            WordListFragment.newInstance(WordListFragment.TabType.CUSTOM)
        )

        // Setup ViewPager2 adapter
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = fragments.size
            override fun createFragment(position: Int) = fragments[position]
        }

        // CRITICAL: Set offscreenPageLimit to keep all fragments in memory
        // Without this, ViewPager2 only loads visible tab + 1 adjacent tab
        // This causes counts to show 0 for unvisited tabs after rotation
        viewPager.offscreenPageLimit = fragments.size - 1  // Keep all 4 tabs loaded

        // Connect TabLayout with ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = TAB_TITLES[position]
        }.attach()
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
            val title = TAB_TITLES[i]
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