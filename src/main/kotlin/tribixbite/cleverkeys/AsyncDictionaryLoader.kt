package tribixbite.cleverkeys

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CancellationException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Two replaceable dictionary requests per owner. Cancellation removes queued work and posted
 * results, and publication checks identity under the same lock as replacement/close. The shared
 * worker survives individual IMEs; a retired owner cannot enqueue or publish more dictionaries.
 */
internal class DictionaryLoadRequests(
    private val executor: ThreadPoolExecutor,
    private val post: (Any, Runnable) -> Unit,
    private val removePosted: (Any) -> Unit
) {
    enum class Slot { PRIMARY, SECONDARY }
    internal class Request {
        var future: FutureTask<Unit>? = null
    }
    private val lock = Any()
    private val requests = mutableMapOf<Slot, Request>()
    private var closed = false

    fun submit(slot: Slot, work: (Request) -> Unit) = synchronized(lock) {
        if (closed) return@synchronized
        cancelLocked(slot)
        val request = Request()
        requests[slot] = request
        val future = FutureTask<Unit> {
            // Cancellation belongs to the old request, never the next task on this worker.
            Thread.interrupted()
            try {
                checkCurrent(slot, request)
                work(request)
            } catch (_: CancellationException) {
                // An obsolete request has no success or failure callback.
            }
        }
        request.future = future
        executor.execute(future)
    }

    fun checkCurrent(slot: Slot, request: Request) {
        if (Thread.currentThread().isInterrupted || synchronized(lock) {
                closed || requests[slot] !== request
            }) throw CancellationException("Dictionary request retired")
    }

    fun publish(slot: Slot, request: Request, action: Runnable) = synchronized(lock) {
        if (!closed && requests[slot] === request) {
            post(request, Runnable {
                synchronized(lock) {
                    if (!closed && requests[slot] === request) action.run()
                }
            })
        }
    }

    fun cancel(slot: Slot) = synchronized(lock) { cancelLocked(slot) }

    fun close() = synchronized(lock) {
        closed = true
        Slot.entries.forEach(::cancelLocked)
    }

    private fun cancelLocked(slot: Slot) {
        requests.remove(slot)?.let { request ->
            request.future?.let { future ->
                future.cancel(true)
                executor.remove(future)
            }
            removePosted(request)
        }
    }
}

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
        private val EXECUTOR = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { r ->
            Thread(r, "DictionaryLoader").apply {
                priority = Thread.NORM_PRIORITY - 1 // Slightly lower priority
            }
        }
    }

    // Handler for callbacks on main thread
    private val mainHandler = Handler(Looper.getMainLooper())

    private val requests = DictionaryLoadRequests(
        EXECUTOR,
        { token, callback -> mainHandler.postAtTime(callback, token, android.os.SystemClock.uptimeMillis()); Unit },
        { token -> mainHandler.removeCallbacksAndMessages(token) }
    )

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
        requests.submit(DictionaryLoadRequests.Slot.PRIMARY) { request ->
            val slot = DictionaryLoadRequests.Slot.PRIMARY
            requests.publish(slot, request, Runnable { callback.onLoadStarted(language) })
            // OPTIMIZATION v3 (perftodos3.md): Use android.os.Trace for system-level profiling
            android.os.Trace.beginSection("AsyncDictionaryLoader.loadDictionaryAsync")
            try {
                val startTime = System.currentTimeMillis()

                val dictionary = mutableMapOf<String, Int>()
                val prefixIndex = mutableMapOf<String, MutableSet<String>>()

                // Issue #179: probe the INSTALLED LANGUAGE PACK first, mirroring the
                // synchronous WordPredictor.loadDictionary precedence (the issue #63 fix).
                // Before this, a pack-only language failed BOTH asset probes below, and
                // WordPredictor.onLoadFailed re-ran the whole load synchronously on the
                // MAIN thread — so the "async" path guaranteed a full pack parse inside
                // IME onCreate on every process create (a real per-create stall, and the
                // surviving sliver of #179's 4–10 s v1.5.0 startup).
                val packFile = try {
                    tribixbite.cleverkeys.langpack.LanguagePackManager
                        .getInstance(context).getDictionaryPath(language)
                } catch (e: Exception) {
                    Log.w(TAG, "Language pack probe failed for '$language'", e)
                    null
                }
                val loadedFromPack = packFile != null &&
                    BinaryDictionaryLoader.loadDictionaryWithPrefixIndexFromFile(
                        packFile, dictionary, prefixIndex
                    )
                if (loadedFromPack) {
                    Log.i(TAG, "Loaded dictionary from language pack on background thread: $language")
                }

                // Bundled binary format (fast path for shipped languages)
                val binaryFilename = "dictionaries/${language}_enhanced.bin"
                val loadedBinary = loadedFromPack || BinaryDictionaryLoader.loadDictionaryWithPrefixIndex(
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
                            lines.forEach {
                                requests.checkCurrent(slot, request)
                                jsonBuilder.append(it)
                            }
                        }

                        // Parse JSON object
                        val jsonDict = JSONObject(jsonBuilder.toString())
                        val keys = jsonDict.keys()
                        while (keys.hasNext()) {
                            requests.checkCurrent(slot, request)
                            val word = keys.next().lowercase()
                            val frequency = jsonDict.getInt(word)
                            // Scale frequency to 100-10000 range
                            val scaledFreq = 100 + ((frequency - 128) / 127.0 * 9900).toInt()
                            dictionary[word] = scaledFreq
                        }

                        // Build prefix index
                        for (word in dictionary.keys) {
                            requests.checkCurrent(slot, request)
                            val maxLen = min(3, word.length)
                            for (len in 1..maxLen) {
                                val prefix = word.substring(0, len)
                                prefixIndex.getOrPut(prefix) { HashSet() }.add(word)
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
                requests.checkCurrent(slot, request)
                val customWords = callback.onLoadCustomWords(context, dictionary, prefixIndex)
                Log.i(TAG, "Loaded ${customWords.size} custom/user words on background thread")

                // Notify success on main thread (maps already include custom words)
                requests.checkCurrent(slot, request)
                requests.publish(slot, request, Runnable { callback.onLoadComplete(dictionary, prefixIndex) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                requests.checkCurrent(slot, request)
                Log.e(TAG, "Dictionary loading failed: $language", e)
                // Notify failure on main thread
                requests.publish(slot, request, Runnable { callback.onLoadFailed(language, e) })
            } finally {
                android.os.Trace.endSection()
            }
        }
    }

    /** Build a secondary index off-main and publish only the latest still-live result. */
    fun runOffMain(task: () -> Runnable?) {
        requests.submit(DictionaryLoadRequests.Slot.SECONDARY) { request ->
            val result = task()
            requests.checkCurrent(DictionaryLoadRequests.Slot.SECONDARY, request)
            result?.let { requests.publish(DictionaryLoadRequests.Slot.SECONDARY, request, it) }
        }
    }

    /** Cancel the primary load without disturbing the independently configured secondary. */
    fun cancel() = requests.cancel(DictionaryLoadRequests.Slot.PRIMARY)

    /** Invalidate a pending secondary result before disabling or replacing the language. */
    fun cancelSecondary() = requests.cancel(DictionaryLoadRequests.Slot.SECONDARY)

    /** Retire this owner's work without shutting down the process-wide worker. */
    fun close() = requests.close()
}
