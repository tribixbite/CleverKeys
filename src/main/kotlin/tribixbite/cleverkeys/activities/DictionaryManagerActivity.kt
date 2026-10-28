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
import tribixbite.cleverkeys.langpack.LanguagePackManager

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

    // Listener for language preference changes
    private val languageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "tribixbite.cleverkeys.LANGUAGE_CHANGED") {
                android.util.Log.d(TAG, "Language settings changed, rebuilding tabs...")
                rebuildTabsForLanguageChange()
            }
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val COUNT_UPDATE_DELAY_MS = 100L // Delay to ensure fragments have updated
        // Log tag kept <=23 chars so Log.isLoggable does not crash on API <26 (LongLogTag lint).
        private const val TAG = "DictManagerActivity"

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

    // v1.2.7: Changed from FilterType to SortType for dictionary sorting
    enum class SortType {
        FREQ,   // Sort by frequency (highest first) - default
        MATCH,  // Sort by match quality/relevance to search query
        A_Z,    // Alphabetical ascending
        Z_A     // Alphabetical descending
    }

    private var currentSort: SortType = SortType.FREQ

    // Active languages for tab generation
    private var primaryLanguage = "en"
    private var secondaryLanguage: String? = null
    private var multiLangEnabled = false
    // Languages bound to a layout (GH #186/#61), each shown with its own tabs.
    private var boundLanguages: List<String> = emptyList()

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
            currentSort = SortType.values()[savedInstanceState.getInt("sortType", 0)]
            searchInput.setText(currentSearchQuery)
            filterSpinner.setSelection(currentSort.ordinal)

            // Reapply search/sort after all fragments load
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
        // The user's choice, as Settings shows it (the shared rule, audit L6).
        val languages = LanguagePrefs.userLanguages(prefs)
        primaryLanguage = languages.primary
        multiLangEnabled = Config.safeGetBoolean(prefs, LanguagePrefs.MULTILANG, Defaults.ENABLE_MULTILANG)
        secondaryLanguage = languages.secondary
        boundLanguages = readBoundLanguages(prefs)

        android.util.Log.d(TAG, "Language prefs: primary=$primaryLanguage, secondary=$secondaryLanguage, " +
            "multilang=$multiLangEnabled, bound=$boundLanguages")
    }

    /**
     * The languages layouts are bound to (GH #186/#61): the user's per-layout choice or the
     * layout XML's `language` default, resolved from the `layouts` preference exactly as the
     * keyboard and Settings' Multi-Language note resolve them
     * ([LayoutsPreference.loadLayoutsWithBindings]). Before (audit 2026-10-20, todo 13c), such a
     * language got no tab, so its custom and disabled words were reachable only by choosing it
     * in Multi-Language. On a parse failure no bound tabs are added; the rest is unaffected.
     */
    private fun readBoundLanguages(prefs: android.content.SharedPreferences): List<String> = try {
        DictionaryTabPlan.boundLanguages(
            tribixbite.cleverkeys.prefs.LayoutsPreference.loadLayoutsWithBindings(resources, prefs).languages
        )
    } catch (e: Exception) {
        android.util.Log.w(TAG, "Could not read layout language bindings", e)
        emptyList()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("searchQuery", currentSearchQuery)
        outState.putInt("sortType", currentSort.ordinal)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release the 50k-word dictionary caches (~7MB per language) held in
        // MainDictionarySource.sharedCache. These are only needed while the
        // Dictionary Manager is open — keeping them in memory permanently
        // wastes RAM for a rarely-used activity.
        // #96: only on a real close. A configuration change (rotation, resize,
        // split-screen) destroys and immediately recreates this activity; dropping the
        // cache made every recreation re-parse the dictionary cold, which both delayed
        // the restored (filtered) list and widened the load-vs-filter race window.
        if (isFinishing) {
            MainDictionarySource.invalidateCache()
        }
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
            title = getString(R.string.activities_dictionary_title)
            setDisplayHomeAsUpEnabled(true)
        }
    }

    /**
     * Setup ViewPager with language-specific tabs, in the order [DictionaryTabPlan] gives:
     * the user's language(s) and User Dict, then Active / Disabled / Custom for each language
     * a layout is bound to (GH #186/#61), then a Custom tab per installed pack not yet shown.
     *
     * @since v1.1.86 - Added language-specific tab generation
     * @since v1.1.87 - Added support for user-imported language pack tabs
     */
    private fun setupViewPager() {
        val fragmentList = mutableListOf<WordListFragment>()
        tabTitles.clear()

        val installedPacks = try {
            LanguagePackManager.getInstance(this).getInstalledPacks().map { it.code }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to list imported language packs", e)
            emptyList()
        }
        for (tab in DictionaryTabPlan.plan(primaryLanguage, secondaryLanguage, boundLanguages, installedPacks)) {
            val type = when (tab.kind) {
                DictionaryTabPlan.Kind.ACTIVE -> WordListFragment.TabType.ACTIVE
                DictionaryTabPlan.Kind.DISABLED -> WordListFragment.TabType.DISABLED
                DictionaryTabPlan.Kind.USER -> WordListFragment.TabType.USER
                DictionaryTabPlan.Kind.CUSTOM -> WordListFragment.TabType.CUSTOM
            }
            val code = tab.language
            fragmentList.add(WordListFragment.newInstance(type, code))
            tabTitles.add(tabTitle(tab, code?.let { LANGUAGE_NAMES[it] ?: it.uppercase() }))
        }

        fragments = fragmentList

        // Setup ViewPager2 adapter
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = fragments.size
            override fun createFragment(position: Int) = fragments[position]
        }

        // CRITICAL: Set offscreenPageLimit to keep all fragments in memory.
        // Without this, ViewPager2 only loads visible tab + 1 adjacent tab, so
        // unvisited tabs would have no loaded data to count. (This alone did NOT
        // fix counts after recreation — that needed liveFragment(), see ARC-110.)
        viewPager.offscreenPageLimit = fragments.size - 1

        // Connect TabLayout with ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()

        // Enable tab scrolling for multilang mode with many tabs
        tabLayout.tabMode = if (fragments.size > 4) TabLayout.MODE_SCROLLABLE else TabLayout.MODE_FIXED
    }

    /**
     * A tab's title: the bare label when the plan leaves the language out (single-language
     * English, the historical default, and User Dict), otherwise the label tagged with
     * [langLabel].
     */
    private fun tabTitle(tab: DictionaryTabPlan.Tab, langLabel: String?): String {
        if (!tab.languageInTitle || langLabel == null) {
            return getString(
                when (tab.kind) {
                    DictionaryTabPlan.Kind.ACTIVE -> R.string.dict_tab_active
                    DictionaryTabPlan.Kind.DISABLED -> R.string.dict_tab_disabled
                    DictionaryTabPlan.Kind.USER -> R.string.dict_tab_user
                    DictionaryTabPlan.Kind.CUSTOM -> R.string.dict_tab_custom
                }
            )
        }
        return getString(
            when (tab.kind) {
                DictionaryTabPlan.Kind.ACTIVE -> R.string.dict_tab_active_lang
                DictionaryTabPlan.Kind.DISABLED -> R.string.dict_tab_disabled_lang
                DictionaryTabPlan.Kind.USER -> R.string.dict_tab_user
                DictionaryTabPlan.Kind.CUSTOM -> R.string.dict_tab_custom_lang
            },
            langLabel,
        )
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
        // v1.2.7: Changed to sort options instead of filter options
        // Display labels only: the selection is resolved by POSITION against the enum
        // (SortType.values()[position]) and saved as the ordinal, so this list must stay in
        // the enum's declaration order (pinned by DictionarySortOrderTest).
        val sortOptions = listOf(
            R.string.dict_sort_freq, R.string.dict_sort_match, R.string.dict_sort_a_z, R.string.dict_sort_z_a
        ).map { getString(it) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sortOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        filterSpinner.adapter = adapter

        filterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applySort(SortType.values()[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupResetButton() {
        resetButton.setOnClickListener {
            resetSearch()
        }
    }

    /**
     * Resolve the LIVE fragment for a tab position.
     *
     * ARC-110: after a configuration change, FragmentStateAdapter restores the
     * previously-attached WordListFragment instances from the FragmentManager and never
     * calls createFragment() for them — while onCreate's setupViewPager() rebuilds
     * [fragments] with FRESH instances that are never attached. Reading counts (or
     * dispatching filter/refresh) through those orphans made every tab show "(0)" after
     * a recreation (uimode flip / rotation) even though the restored fragments rendered
     * a fully-populated list. Counts and list content must derive from the SAME loaded
     * data, so every read/dispatch resolves the fragment actually attached to the
     * FragmentManager: FragmentStateAdapter adds it with tag "f" + itemId, and itemId
     * defaults to the adapter position. The constructed instance is only a fallback for
     * the first-creation window before the adapter attaches it (when it IS the object
     * the adapter is about to attach).
     */
    private fun liveFragment(position: Int): WordListFragment? =
        (supportFragmentManager.findFragmentByTag("f$position") as? WordListFragment)
            ?: fragments.getOrNull(position)

    /** Apply [action] to the live (attached) fragment of every tab. See [liveFragment]. */
    private fun forEachLiveFragment(action: (WordListFragment) -> Unit) {
        for (i in fragments.indices) liveFragment(i)?.let(action)
    }

    private fun performSearch(query: String) {
        // v1.2.7: Apply search to all fragments with sort type
        forEachLiveFragment { it.filter(query, currentSort) }

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
            // ARC-110: count comes from the live fragment — the same object whose adapter
            // renders the list — never from a detached instance in [fragments].
            val count = liveFragment(i)?.getFilteredCount() ?: 0
            val title = tabTitles.getOrElse(i) { getString(R.string.dict_tab_fallback, i + 1) }
            tab.text = getString(R.string.dict_tab_with_count, title, count)
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

    private fun applySort(sortType: SortType) {
        currentSort = sortType
        performSearch(currentSearchQuery)
    }

    private fun resetSearch() {
        searchInput.setText("")
        filterSpinner.setSelection(0)  // Reset to "Freq" (default sort)
        currentSearchQuery = ""
        performSearch("")
    }

    /**
     * Called by fragments when words are modified to refresh other tabs
     */
    fun refreshAllTabs() {
        forEachLiveFragment { it.refresh() }

        // Update tab counts to reflect changes
        searchHandler.postDelayed({
            updateTabCounts()
        }, COUNT_UPDATE_DELAY_MS)

        // Reload predictions to reflect dictionary changes
        reloadPredictions()
    }

    /**
     * Rebuild tabs when primary/secondary language settings change.
     * Re-reads language preferences and recreates the ViewPager with appropriate tabs.
     */
    private fun rebuildTabsForLanguageChange() {
        val oldPrimary = primaryLanguage
        val oldSecondary = secondaryLanguage
        val oldBound = boundLanguages

        // Reload language preferences
        loadLanguagePreferences()

        // Only rebuild if languages actually changed (a layout's binding counts: GH #186/#61)
        if (primaryLanguage != oldPrimary || secondaryLanguage != oldSecondary || boundLanguages != oldBound) {
            android.util.Log.d(TAG, "Languages changed: $oldPrimary/$oldSecondary -> $primaryLanguage/$secondaryLanguage")

            // Clear current search
            currentSearchQuery = ""
            searchInput.setText("")

            // Rebuild ViewPager with new tabs
            setupViewPager()

            // Update tab counts
            searchHandler.postDelayed({
                updateTabCounts()
            }, 300) // Longer delay to allow fragments to load
        }
    }

    /**
     * Reload custom/user/disabled words in the typing predictor.
     * PERFORMANCE: Only reloads small dynamic sets, not main dictionaries.
     *
     * The swipe engines need no signal: the CTC adapter keys its merged lexicon on a content
     * hash of the custom/disabled word sets, so an edit here invalidates it on the next
     * decode, and the geometric engine rebuilds its template index per (layout, language).
     */
    private fun reloadPredictions() {
        try {
            // Signal typing predictions to reload on next prediction (lazy reload for performance)
            WordPredictor.signalReloadNeeded()

            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d(TAG, "Reloaded predictions after dictionary changes")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to reload predictions", e)
        }
    }

    override fun onResume() {
        super.onResume()
        // Register receiver for dictionary import notifications
        LocalBroadcastManager.getInstance(this).registerReceiver(
            dictionaryImportReceiver, IntentFilter(BackupRestoreActivity.ACTION_DICTIONARY_IMPORTED)
        )
        // Register receiver for language changes
        LocalBroadcastManager.getInstance(this).registerReceiver(
            languageChangeReceiver, IntentFilter("tribixbite.cleverkeys.LANGUAGE_CHANGED")
        )

        // Check if languages changed while we were paused
        // (Also when a layout's language binding changed in Layout Manager meanwhile.)
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        val current = LanguagePrefs.userLanguages(prefs)
        if (current.primary != primaryLanguage || current.secondary != secondaryLanguage ||
            readBoundLanguages(prefs) != boundLanguages
        ) {
            rebuildTabsForLanguageChange()
        }
    }

    override fun onPause() {
        super.onPause()
        // Unregister receivers
        LocalBroadcastManager.getInstance(this).unregisterReceiver(dictionaryImportReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(languageChangeReceiver)
    }
}

// Extension function to capitalize first letter
private fun String.capitalize(): String {
    return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
/**
 * Which tabs Dictionary Manager shows, in order (pure, so `runPureTests` pins it).
 *
 * - Single language: Active, Disabled, User Dict, Custom for the primary; the language is named
 *   in the title unless it is English (the historical default, where the suffix adds nothing).
 * - Multi-Language with a secondary: Active / Disabled / Custom for the primary, User Dict, then
 *   the same three for the secondary.
 * - Then Active / Disabled / Custom for every language a layout is BOUND to (GH #186/#61) that
 *   is not already shown, in layout order — the same tabs a secondary language gets.
 * - Then a Custom tab for each installed language pack not already shown (v1.1.87).
 */
internal object DictionaryTabPlan {
    enum class Kind { ACTIVE, DISABLED, USER, CUSTOM }

    /** One tab: [language] is null only for the (language-independent) User Dict tab. */
    data class Tab(val kind: Kind, val language: String?, val languageInTitle: Boolean)

    /**
     * @param primary the user's primary language (`LanguagePrefs.userLanguages`).
     * @param secondary the user's secondary, or null outside Multi-Language.
     * @param bindings each layout's EFFECTIVE language binding, index-aligned with the
     *   `layouts` preference (`LayoutsPreference.loadLayoutsWithBindings(...).languages`).
     * @param installedPacks codes of the installed language packs.
     */
    fun plan(primary: String, secondary: String?, bindings: List<String?>, installedPacks: List<String>): List<Tab> {
        val tabs = mutableListOf<Tab>()
        val shown = mutableSetOf<String>()
        fun addLanguage(code: String) {
            tabs += Tab(Kind.ACTIVE, code, true)
            tabs += Tab(Kind.DISABLED, code, true)
            tabs += Tab(Kind.CUSTOM, code, true)
            shown += code
        }
        if (secondary != null) {
            addLanguage(primary)
            tabs += Tab(Kind.USER, null, false)
            addLanguage(secondary)
        } else {
            val named = primary != "en"
            tabs += Tab(Kind.ACTIVE, primary, named)
            tabs += Tab(Kind.DISABLED, primary, named)
            tabs += Tab(Kind.USER, null, false)
            tabs += Tab(Kind.CUSTOM, primary, named)
            shown += primary
        }
        for (code in boundLanguages(bindings)) {
            if (code !in shown) addLanguage(code)
        }
        for (code in installedPacks) {
            if (shown.add(code)) tabs += Tab(Kind.CUSTOM, code, true)
        }
        return tabs
    }

    /** The distinct bound languages of [bindings], in layout order. */
    fun boundLanguages(bindings: List<String?>): List<String> = bindings.filterNotNull().distinct()
}
