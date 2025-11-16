package tribixbite.keyboard2

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tribixbite.keyboard2.theme.KeyboardTheme

/**
 * Dictionary Manager Activity for CleverKeys
 *
 * Fix for Bug #472: Dictionary Management UI
 *
 * Allows users to:
 * - View all custom words
 * - Add new custom words
 * - Delete custom words
 * - See word count statistics
 *
 * Backend: Uses DictionaryManager.kt and MultiLanguageDictionaryManager.kt
 */
@OptIn(ExperimentalMaterial3Api::class)
class DictionaryManagerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DictionaryManagerActivity"
    }

    private lateinit var dictionaryManager: DictionaryManager
    private var customWords by mutableStateOf<List<String>>(emptyList())
    private var isLoading by mutableStateOf(true)
    private var showAddWordDialog by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize dictionary manager
        try {
            dictionaryManager = DictionaryManager(this)
            loadCustomWords()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error initializing dictionary manager", e)
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
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.dictionary_title))
                            if (!isLoading) {
                                Text(
                                    text = stringResource(R.string.dictionary_word_count, customWords.size),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
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
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showAddWordDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.dictionary_add_word))
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    customWords.isEmpty() -> {
                        EmptyState()
                    }
                    else -> {
                        WordList(customWords)
                    }
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

    @Composable
    private fun EmptyState() {
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
    private fun WordList(words: List<String>) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(words) { word ->
                WordItem(word)
            }
        }
    }

    @Composable
    private fun WordItem(word: String) {
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

    private fun loadCustomWords() {
        lifecycleScope.launch {
            try {
                isLoading = true
                val words = withContext(Dispatchers.IO) {
                    // Get custom words from DictionaryManager
                    dictionaryManager.getUserWords().toList()
                }
                customWords = words.sorted() // Sort alphabetically
                isLoading = false
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error loading custom words", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DictionaryManagerActivity,
                        getString(R.string.dictionary_error_load),
                        Toast.LENGTH_SHORT
                    ).show()
                    isLoading = false
                }
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
}
