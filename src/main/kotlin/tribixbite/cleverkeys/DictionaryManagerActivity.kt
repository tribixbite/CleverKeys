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
