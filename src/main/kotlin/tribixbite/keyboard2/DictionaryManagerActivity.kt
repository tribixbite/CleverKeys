package tribixbite.keyboard2

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tribixbite.keyboard2.theme.KeyboardTheme
import java.io.BufferedReader

/**
 * Dictionary Manager Activity for CleverKeys
 *
 * Fix for Bug #472 & #473: Tabbed Dictionary Management UI
 *
 * Features:
 * - **Tab 1: User Dictionary** - Add/remove custom words with search
 * - **Tab 2: Built-in Dictionary** - Browse 10k built-in words, disable unwanted words
 * - **Tab 3: Disabled Words** - Manage blacklisted words that won't appear in predictions
 *
 * Backend: DictionaryManager.kt, DisabledWordsManager.kt
 */
@OptIn(ExperimentalMaterial3Api::class)
class DictionaryManagerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DictionaryManagerActivity"
    }

    // Tab state
    private var selectedTabIndex by mutableStateOf(0)

    // Tab 1: User Dictionary
    private lateinit var dictionaryManager: DictionaryManager
    private var customWords by mutableStateOf<List<String>>(emptyList())
    private var filteredCustomWords by mutableStateOf<List<String>>(emptyList())
    private var userSearchQuery by mutableStateOf("")
    private var isLoadingUser by mutableStateOf(true)
    private var showAddWordDialog by mutableStateOf(false)

    // Tab 2: Built-in Dictionary
    private var builtInWords by mutableStateOf<List<DictionaryWord>>(emptyList())
    private var filteredBuiltInWords by mutableStateOf<List<DictionaryWord>>(emptyList())
    private var builtInSearchQuery by mutableStateOf("")
    private var isLoadingBuiltIn by mutableStateOf(true)

    // Tab 3: Disabled Words
    private lateinit var disabledWordsManager: DisabledWordsManager
    private var disabledWords by mutableStateOf<List<String>>(emptyList())
    private var filteredDisabledWords by mutableStateOf<List<String>>(emptyList())
    private var disabledSearchQuery by mutableStateOf("")

    /**
     * Data class for built-in dictionary words
     */
    data class DictionaryWord(
        val word: String,
        val rank: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize managers
        try {
            dictionaryManager = DictionaryManager(this)
            disabledWordsManager = DisabledWordsManager.getInstance(this)

            // Load data for all tabs
            loadCustomWords()
            loadBuiltInDictionary()
            loadDisabledWords()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error initializing dictionary managers", e)
            Toast.makeText(this, getString(R.string.dictionary_error_init), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            KeyboardTheme(darkTheme = true) {
                DictionaryManagerScreen()
            }
        }
    }

    @Composable
    private fun DictionaryManagerScreen() {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(stringResource(R.string.dictionary_title)) },
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.dictionary_back))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    TabRow(
                        selectedTabIndex = selectedTabIndex,
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Tab(
                            selected = selectedTabIndex == 0,
                            onClick = { selectedTabIndex = 0 },
                            text = { Text(stringResource(R.string.tab_user_words)) }
                        )
                        Tab(
                            selected = selectedTabIndex == 1,
                            onClick = { selectedTabIndex = 1 },
                            text = { Text(stringResource(R.string.tab_builtin_dict)) }
                        )
                        Tab(
                            selected = selectedTabIndex == 2,
                            onClick = { selectedTabIndex = 2 },
                            text = { Text(stringResource(R.string.tab_disabled_words)) }
                        )
                    }
                }
            },
            floatingActionButton = {
                // Only show FAB on User Dictionary tab
                if (selectedTabIndex == 0) {
                    FloatingActionButton(
                        onClick = { showAddWordDialog = true },
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.dictionary_add_word))
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (selectedTabIndex) {
                    0 -> UserDictionaryTab()
                    1 -> BuiltInDictionaryTab()
                    2 -> DisabledWordsTab()
                }
            }
        }

        // Add Word Dialog
        if (showAddWordDialog) {
            AddWordDialog(
                onDismiss = { showAddWordDialog = false },
                onAdd = { word ->
                    addWord(word)
                    showAddWordDialog = false
                }
            )
        }
    }

    // ===============================================
    // TAB 1: USER DICTIONARY
    // ===============================================

    @Composable
    private fun UserDictionaryTab() {
        Column(modifier = Modifier.fillMaxSize()) {
            // Search field
            OutlinedTextField(
                value = userSearchQuery,
                onValueChange = { query ->
                    userSearchQuery = query
                    filterUserWords(query)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text(stringResource(R.string.user_dict_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )

            // Word count
            Text(
                text = stringResource(R.string.dictionary_word_count, filteredCustomWords.size),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Content
            when {
                isLoadingUser -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                customWords.isEmpty() -> {
                    UserDictionaryEmptyState()
                }
                filteredCustomWords.isEmpty() -> {
                    EmptySearchResults(stringResource(R.string.dictionary_empty_search))
                }
                else -> {
                    UserWordList(filteredCustomWords)
                }
            }
        }
    }

    @Composable
    private fun UserDictionaryEmptyState() {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.dictionary_empty_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.dictionary_empty_desc),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = { showAddWordDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.dictionary_add_first_word))
            }
        }
    }

    @Composable
    private fun UserWordList(words: List<String>) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(words) { word ->
                UserWordItem(word)
            }
        }
    }

    @Composable
    private fun UserWordItem(word: String) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = word,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { deleteWord(word) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.dictionary_delete_word),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    // ===============================================
    // TAB 2: BUILT-IN DICTIONARY
    // ===============================================

    @Composable
    private fun BuiltInDictionaryTab() {
        Column(modifier = Modifier.fillMaxSize()) {
            // Search field
            OutlinedTextField(
                value = builtInSearchQuery,
                onValueChange = { query ->
                    builtInSearchQuery = query
                    filterBuiltInWords(query)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text(stringResource(R.string.builtin_dict_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )

            // Word count
            val countText = if (builtInSearchQuery.isBlank()) {
                stringResource(R.string.builtin_dict_all_count, builtInWords.size)
            } else {
                stringResource(R.string.builtin_dict_showing_count, filteredBuiltInWords.size, builtInWords.size)
            }
            Text(
                text = countText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Content
            when {
                isLoadingBuiltIn -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                builtInWords.isEmpty() -> {
                    EmptySearchResults(stringResource(R.string.builtin_dict_error_load))
                }
                filteredBuiltInWords.isEmpty() -> {
                    EmptySearchResults(stringResource(R.string.builtin_dict_no_results))
                }
                else -> {
                    BuiltInWordList(filteredBuiltInWords)
                }
            }
        }
    }

    @Composable
    private fun BuiltInWordList(words: List<DictionaryWord>) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(words) { dictWord ->
                BuiltInWordItem(dictWord)
            }
        }
    }

    @Composable
    private fun BuiltInWordItem(dictWord: DictionaryWord) {
        val isDisabled = disabledWords.contains(dictWord.word)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isDisabled) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dictWord.word,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.builtin_dict_rank, dictWord.rank),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!isDisabled) {
                    Button(
                        onClick = { disableBuiltInWord(dictWord.word) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.builtin_dict_disable_button))
                    }
                }
            }
        }
    }

    // ===============================================
    // TAB 3: DISABLED WORDS
    // ===============================================

    @Composable
    private fun DisabledWordsTab() {
        Column(modifier = Modifier.fillMaxSize()) {
            // Search field
            OutlinedTextField(
                value = disabledSearchQuery,
                onValueChange = { query ->
                    disabledSearchQuery = query
                    filterDisabledWords(query)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text(stringResource(R.string.disabled_words_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )

            // Word count and Clear All button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.disabled_words_count, filteredDisabledWords.size),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (disabledWords.isNotEmpty()) {
                    TextButton(onClick = { clearAllDisabledWords() }) {
                        Text(stringResource(R.string.disabled_words_clear_all))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Content
            when {
                disabledWords.isEmpty() -> {
                    DisabledWordsEmptyState()
                }
                filteredDisabledWords.isEmpty() -> {
                    EmptySearchResults(stringResource(R.string.disabled_words_no_results))
                }
                else -> {
                    DisabledWordList(filteredDisabledWords)
                }
            }
        }
    }

    @Composable
    private fun DisabledWordsEmptyState() {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.disabled_words_empty_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.disabled_words_empty_desc),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }

    @Composable
    private fun DisabledWordList(words: List<String>) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(words) { word ->
                DisabledWordItem(word)
            }
        }
    }

    @Composable
    private fun DisabledWordItem(word: String) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = word,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { enableDisabledWord(word) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(stringResource(R.string.disabled_words_enable_button))
                }
            }
        }
    }

    // ===============================================
    // SHARED COMPONENTS
    // ===============================================

    @Composable
    private fun EmptySearchResults(message: String) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    private fun AddWordDialog(
        onDismiss: () -> Unit,
        onAdd: (String) -> Unit
    ) {
        var word by remember { mutableStateOf("") }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(stringResource(R.string.dialog_add_word_title))
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = word,
                        onValueChange = {
                            word = it
                            errorMessage = null
                        },
                        label = { Text(stringResource(R.string.dialog_add_word_hint)) },
                        singleLine = true,
                        isError = errorMessage != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmedWord = word.trim()
                        when {
                            trimmedWord.isEmpty() -> {
                                errorMessage = getString(R.string.dialog_add_word_error_empty)
                            }
                            trimmedWord.length < 2 -> {
                                errorMessage = getString(R.string.dialog_add_word_error_too_short)
                            }
                            customWords.contains(trimmedWord) -> {
                                errorMessage = getString(R.string.dialog_add_word_error_duplicate)
                            }
                            else -> {
                                onAdd(trimmedWord)
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.dialog_add_word_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // ===============================================
    // DATA LOADING FUNCTIONS
    // ===============================================

    /**
     * Load user custom words from DictionaryManager
     */
    private fun loadCustomWords() {
        lifecycleScope.launch {
            try {
                isLoadingUser = true
                val words = withContext(Dispatchers.IO) {
                    dictionaryManager.getUserWords().toList()
                }
                customWords = words.sorted()
                filteredCustomWords = customWords
                isLoadingUser = false
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error loading custom words", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DictionaryManagerActivity,
                        getString(R.string.dictionary_error_load),
                        Toast.LENGTH_SHORT
                    ).show()
                    isLoadingUser = false
                }
            }
        }
    }

    /**
     * Load built-in dictionary from assets/dictionaries/en.txt
     */
    private fun loadBuiltInDictionary() {
        lifecycleScope.launch {
            try {
                isLoadingBuiltIn = true
                val words = withContext(Dispatchers.IO) {
                    val inputStream = assets.open("dictionaries/en.txt")
                    val reader = BufferedReader(inputStream.reader())
                    val wordList = mutableListOf<DictionaryWord>()

                    reader.useLines { lines ->
                        lines.forEachIndexed { index, word ->
                            wordList.add(DictionaryWord(word.trim(), index + 1))
                        }
                    }

                    wordList
                }
                builtInWords = words
                filteredBuiltInWords = words
                isLoadingBuiltIn = false
                android.util.Log.d(TAG, "Loaded ${words.size} built-in words")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error loading built-in dictionary", e)
                isLoadingBuiltIn = false
            }
        }
    }

    /**
     * Load disabled words from DisabledWordsManager
     */
    private fun loadDisabledWords() {
        lifecycleScope.launch {
            try {
                // Observe disabled words changes via Flow
                disabledWordsManager.disabledWords.collectLatest { words ->
                    disabledWords = words.sorted()
                    filteredDisabledWords = disabledWords
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error loading disabled words", e)
            }
        }
    }

    private fun addWord(word: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    dictionaryManager.addUserWord(word)
                }
                // Reload words
                loadCustomWords()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DictionaryManagerActivity,
                        getString(R.string.dictionary_toast_word_added, word),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error adding word: $word", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DictionaryManagerActivity,
                        getString(R.string.dictionary_error_add),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun deleteWord(word: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    dictionaryManager.removeUserWord(word)
                }
                // Reload words
                loadCustomWords()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DictionaryManagerActivity,
                        getString(R.string.dictionary_toast_word_deleted, word),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error deleting word: $word", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DictionaryManagerActivity,
                        getString(R.string.dictionary_error_delete),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // ===============================================
    // SEARCH/FILTER FUNCTIONS
    // ===============================================

    /**
     * Filter user custom words based on search query
     */
    private fun filterUserWords(query: String) {
        filteredCustomWords = if (query.isBlank()) {
            customWords
        } else {
            customWords.filter { word ->
                word.contains(query, ignoreCase = true)
            }
        }
    }

    /**
     * Filter built-in dictionary words based on search query
     */
    private fun filterBuiltInWords(query: String) {
        filteredBuiltInWords = if (query.isBlank()) {
            builtInWords
        } else {
            builtInWords.filter { dictWord ->
                dictWord.word.contains(query, ignoreCase = true)
            }
        }
    }

    /**
     * Filter disabled words based on search query
     */
    private fun filterDisabledWords(query: String) {
        filteredDisabledWords = if (query.isBlank()) {
            disabledWords
        } else {
            disabledWords.filter { word ->
                word.contains(query, ignoreCase = true)
            }
        }
    }

    // ===============================================
    // DISABLED WORDS ACTIONS
    // ===============================================

    /**
     * Add a built-in word to the disabled list (blacklist)
     */
    private fun disableBuiltInWord(word: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    disabledWordsManager.addDisabledWord(word)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DictionaryManagerActivity,
                        getString(R.string.toast_word_disabled, word),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error disabling word: $word", e)
            }
        }
    }

    /**
     * Remove a word from the disabled list (enable it)
     */
    private fun enableDisabledWord(word: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    disabledWordsManager.removeDisabledWord(word)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DictionaryManagerActivity,
                        getString(R.string.toast_word_enabled, word),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error enabling word: $word", e)
            }
        }
    }

    /**
     * Clear all disabled words
     */
    private fun clearAllDisabledWords() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    disabledWordsManager.clearAll()
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DictionaryManagerActivity,
                        getString(R.string.toast_all_disabled_cleared),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error clearing disabled words", e)
            }
        }
    }
}
