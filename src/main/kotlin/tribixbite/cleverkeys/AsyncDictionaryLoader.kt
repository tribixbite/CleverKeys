package tribixbite.cleverkeys

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.min

/**
 * Asynchronous dictionary loader with background thread execution.
 *
 * Ensures dictionary loading never blocks the main thread, providing
 * responsive UI during startup and language switches.
 *
 * OPTIMIZATION: Prevents UI freezes during dictionary loading
 * - Main thread remains responsive
 * - User receives feedback about loading state
 * - Predictions become available asynchronously
 *
 * Usage:
 * ```
 * val loader = AsyncDictionaryLoader()
 * loader.loadDictionaryAsync(context, language, object : AsyncDictionaryLoader.LoadCallback {
 *   override fun onLoadStarted(language: String) {
 *     // Show loading indicator
 *   }
 *
 *   override fun onLoadComplete(dictionary: Map<String, Int>,
 *                                prefixIndex: Map<String, Set<String>>) {
 *     // Hide loading indicator, enable predictions
 *   }
 *
 *   override fun onLoadFailed(language: String, error: Exception) {
 *     // Show error message
 *   }
 * })
 * ```
 */
class AsyncDictionaryLoader {
    companion object {
        private const val TAG = "AsyncDictionaryLoader"

        // Single-threaded executor for sequential dictionary loading
        // (only one dictionary should load at a time)
        private val EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "DictionaryLoader").apply {
                priority = Thread.NORM_PRIORITY - 1 // Slightly lower priority
            }
        }

        /**
         * Shutdown the executor service.
         * Should be called when the loader is no longer needed.
         */
        @JvmStatic
        fun shutdown() {
            EXECUTOR.shutdown()
            Log.d(TAG, "Dictionary loader executor shutdown")
        }
    }

    // Handler for callbacks on main thread
    private val mainHandler = Handler(Looper.getMainLooper())

    // Current loading task (for cancellation)
    private var currentTask: Future<*>? = null

    /**
     * Callback interface for asynchronous dictionary loading.
     */
    interface LoadCallback {
        /**
         * Called on main thread when loading starts.
         */
        fun onLoadStarted(language: String)

        /**
         * Called on BACKGROUND THREAD after dictionary is loaded but before callback.
         * Use this to load custom/user words into the maps before they're swapped.
         *
         * OPTIMIZATION v4 (perftodos4.md): This runs on background thread to avoid
         * blocking main thread with custom word loading.
         *
         * @param context Android context for accessing SharedPreferences and ContentProvider
         * @param dictionary The loaded dictionary map (modify in place)
         * @param prefixIndex The loaded prefix index (modify in place)
         * @return Set of custom words loaded (for logging)
         */
        fun onLoadCustomWords(
            context: Context,
            dictionary: MutableMap<String, Int>,
            prefixIndex: MutableMap<String, MutableSet<String>>
        ): Set<String>

        /**
         * Called on main thread when loading completes successfully.
         *
         * @param dictionary Map of words to frequencies (includes custom words)
         * @param prefixIndex Map of prefixes to matching words (includes custom words)
         */
        fun onLoadComplete(
            dictionary: Map<String, Int>,
            prefixIndex: Map<String, @JvmSuppressWildcards Set<String>>
        )

        /**
         * Called on main thread if loading fails.
         */
        fun onLoadFailed(language: String, error: Exception)
    }

    /**
     * Load dictionary asynchronously on background thread.
     *
     * This method returns immediately. The callback will be invoked on the
     * main thread when loading completes or fails.
     *
     * @param context Android context for asset access
     * @param language Language code (e.g., "en")
     * @param callback Callback for load events (called on main thread)
     */
    fun loadDictionaryAsync(
        context: Context,
        language: String,
        callback: LoadCallback
    ) {
        // Cancel any previous loading task
        currentTask?.let {
            if (!it.isDone) {
                it.cancel(true)
                Log.d(TAG, "Cancelled previous dictionary load")
            }
        }

        // Notify on main thread that loading started
        mainHandler.post { callback.onLoadStarted(language) }

        // Submit loading task to background thread
        currentTask = EXECUTOR.submit {
            // OPTIMIZATION v3 (perftodos3.md): Use android.os.Trace for system-level profiling
            android.os.Trace.beginSection("AsyncDictionaryLoader.loadDictionaryAsync")
            try {
                val startTime = System.currentTimeMillis()

                // Try binary format first (fast path)
                val binaryFilename = "dictionaries/${language}_enhanced.bin"
                val dictionary = mutableMapOf<String, Int>()
                val prefixIndex = mutableMapOf<String, MutableSet<String>>()

                val loadedBinary = BinaryDictionaryLoader.loadDictionaryWithPrefixIndex(
                    context, binaryFilename, dictionary, prefixIndex
                )

                if (!loadedBinary) {
                    // Fall back to JSON format (slow path)
                    Log.d(TAG, "Binary dictionary not available, falling back to JSON")

                    val jsonFilename = "dictionaries/${language}_enhanced.json"
                    try {
                        val reader = BufferedReader(
                            InputStreamReader(context.assets.open(jsonFilename))
                        )
                        val jsonBuilder = StringBuilder()
                        reader.useLines { lines ->
                            lines.forEach { jsonBuilder.append(it) }
                        }

                        // Parse JSON object
                        val jsonDict = JSONObject(jsonBuilder.toString())
                        val keys = jsonDict.keys()
                        while (keys.hasNext()) {
                            val word = keys.next().lowercase()
                            val frequency = jsonDict.getInt(word)
                            // Scale frequency to 100-10000 range
                            val scaledFreq = 100 + ((frequency - 128) / 127.0 * 9900).toInt()
                            dictionary[word] = scaledFreq
                        }

                        // Build prefix index
                        for (word in dictionary.keys) {
                            val maxLen = min(3, word.length)
                            for (len in 1..maxLen) {
                                val prefix = word.substring(0, len)
                                prefixIndex.getOrPut(prefix) { mutableSetOf() }.add(word)
                            }
                        }

                        Log.d(TAG, "Loaded JSON dictionary: $jsonFilename")
                    } catch (e: Exception) {
                        throw RuntimeException("Failed to load dictionary: $language", e)
                    }
                }

                val loadTime = System.currentTimeMillis() - startTime
                Log.i(
                    TAG,
                    "Dictionary loaded in ${loadTime}ms on background thread: ${dictionary.size} words, ${prefixIndex.size} prefixes"
                )

                // OPTIMIZATION v4 (perftodos4.md): Load custom words on BACKGROUND THREAD
                // This prevents blocking the main thread with SharedPreferences and ContentProvider access
                val customWords = callback.onLoadCustomWords(context, dictionary, prefixIndex)
                Log.i(TAG, "Loaded ${customWords.size} custom/user words on background thread")

                // Notify success on main thread (maps already include custom words)
                mainHandler.post { callback.onLoadComplete(dictionary, prefixIndex) }
            } catch (e: Exception) {
                Log.e(TAG, "Dictionary loading failed: $language", e)
                // Notify failure on main thread
                mainHandler.post { callback.onLoadFailed(language, e) }
            } finally {
                android.os.Trace.endSection()
            }
        }
    }

    /**
     * Cancel any ongoing dictionary load.
     */
    fun cancel() {
        currentTask?.let {
            if (!it.isDone) {
                it.cancel(true)
                Log.d(TAG, "Dictionary load cancelled")
            }
        }
    }
}
