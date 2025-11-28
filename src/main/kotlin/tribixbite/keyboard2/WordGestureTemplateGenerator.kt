package tribixbite.keyboard2

import android.content.Context
import android.graphics.PointF
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import kotlin.math.sqrt

/**
 * Generates gesture templates for words based on ACTUAL keyboard layout
 * Uses real keyboard dimensions and layout calculations for accurate templates
 */
class WordGestureTemplateGenerator {
    // Dynamic QWERTY coordinates based on actual keyboard rendering
    private var keyboardCoords: MutableMap<Char, ContinuousGestureRecognizer.Point> = mutableMapOf()

    private val dictionary: MutableList<String> = mutableListOf()
    private val wordFrequencies: MutableMap<String, Int> = mutableMapOf()

    // PERFORMANCE: Template cache to avoid regenerating on every swipe
    private val templateCache: MutableMap<String, ContinuousGestureRecognizer.Template> = mutableMapOf()
    private var cachedDimensions = "" // Track when cache is valid

    init {
        // Initialize with default coordinates to prevent crashes
        setKeyboardDimensions(1080f, 400f) // Default fallback dimensions
    }

    /**
     * Set keyboard dimensions for dynamic template generation
     * Uses ACTUAL keyboard layout calculations (not fixed 1000x1000)
     */
    fun setKeyboardDimensions(keyboardWidth: Float, keyboardHeight: Float) {
        var width = keyboardWidth
        var height = keyboardHeight

        if (width <= 0 || height <= 0) {
            Log.w(TAG, "Invalid keyboard dimensions: ${width}x$height, using defaults")
            width = 1080f
            height = 400f
        }

        keyboardCoords.clear()

        // Use EXACT same layout calculation as SwipeCalibrationActivity.KeyboardView
        val keyWidth = width / 10f
        val rowHeight = height / 4f // 4 rows
        val verticalMargin = 0.1f * rowHeight   // Match keyboard rendering
        val horizontalMargin = 0.05f * keyWidth // Match keyboard rendering

        // QWERTY layout using IDENTICAL calculations as keyboard rendering
        val keyboardLayout = arrayOf(
            arrayOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            arrayOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            arrayOf("z", "x", "c", "v", "b", "n", "m")
        )

        for (row in 0..2) { // Only letter rows
            val rowKeys = keyboardLayout[row]

            when (row) {
                0 -> { // Top row (q-p)
                    for (col in rowKeys.indices) {
                        val key = rowKeys[col]
                        val x = col * keyWidth + horizontalMargin / 2
                        val y = row * rowHeight + verticalMargin / 2

                        // Use CENTER of key for template coordinate
                        val centerX = x + (keyWidth - horizontalMargin) / 2
                        val centerY = y + (rowHeight - verticalMargin) / 2

                        keyboardCoords[key[0]] = ContinuousGestureRecognizer.Point(centerX.toDouble(), centerY.toDouble())
                    }
                }
                1 -> { // Middle row (a-l) - with half-key offset
                    val rowOffset = keyWidth * 0.5f
                    for (col in rowKeys.indices) {
                        val key = rowKeys[col]
                        val x = rowOffset + col * keyWidth + horizontalMargin / 2
                        val y = row * rowHeight + verticalMargin / 2

                        val centerX = x + (keyWidth - horizontalMargin) / 2
                        val centerY = y + (rowHeight - verticalMargin) / 2

                        keyboardCoords[key[0]] = ContinuousGestureRecognizer.Point(centerX.toDouble(), centerY.toDouble())
                    }
                }
                2 -> { // Bottom row (z-m)
                    // Calculate starting position to center 7 keys
                    val totalKeysWidth = 7 * keyWidth
                    val startX = (width - totalKeysWidth) / 2

                    for (col in rowKeys.indices) {
                        val key = rowKeys[col]
                        val x = startX + col * keyWidth + horizontalMargin / 2
                        val y = row * rowHeight + verticalMargin / 2

                        val centerX = x + (keyWidth - horizontalMargin) / 2
                        val centerY = y + (rowHeight - verticalMargin) / 2

                        keyboardCoords[key[0]] = ContinuousGestureRecognizer.Point(centerX.toDouble(), centerY.toDouble())
                    }
                }
            }
        }

        Log.d(TAG, "Generated dynamic keyboard coordinates for %.0fx%.0f keyboard".format(width, height))
    }

    /**
     * Load dictionary from en.txt file
     */
    fun loadDictionary(context: Context) {
        dictionary.clear()
        wordFrequencies.clear()

        try {
            BufferedReader(InputStreamReader(context.assets.open("dictionaries/en.txt"))).use { reader ->
                var wordCount = 0

                reader.forEachLine { line ->
                    if (wordCount >= 10000) return@forEachLine

                    // Skip comments and empty lines
                    if (line.startsWith("#") || line.trim().isEmpty()) return@forEachLine

                    val word = line.trim().lowercase()

                    // Only include words with letters (3-12 characters for comprehensive gesture typing)
                    if (word.matches(Regex("[a-z]+")) && word.length in 3..12) {
                        dictionary.add(word)
                        wordFrequencies[word] = 1000 // Default frequency since no frequencies in new format
                        wordCount++
                    }
                }
            }

            Log.d(TAG, "Loaded ${dictionary.size} words for gesture templates")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load dictionary: ${e.message}")
        }
    }

    /**
     * Generate gesture template for a single word
     */
    fun generateWordTemplate(word: String): ContinuousGestureRecognizer.Template? {
        if (keyboardCoords.isEmpty()) {
            Log.w(TAG, "Keyboard dimensions not set - call setKeyboardDimensions() first")
            return null
        }

        val lowerWord = word.lowercase()

        // PERFORMANCE: Check cache first
        templateCache[lowerWord]?.let { return it }

        val points = mutableListOf<ContinuousGestureRecognizer.Point>()

        for (c in lowerWord) {
            val coord = keyboardCoords[c]
            if (coord != null) {
                points.add(ContinuousGestureRecognizer.Point(coord.x, coord.y))
            } else {
                Log.w(TAG, "No coordinate found for character: $c")
                return null // Skip words with unknown characters
            }
        }

        if (points.size < 2) {
            return null // Need at least 2 points for a gesture
        }

        val template = ContinuousGestureRecognizer.Template(lowerWord, points)

        // CACHE: Store for future use
        templateCache[lowerWord] = template

        return template
    }

    /**
     * Generate templates for all dictionary words
     */
    fun generateAllWordTemplates(): List<ContinuousGestureRecognizer.Template> {
        val templates = mutableListOf<ContinuousGestureRecognizer.Template>()
        var successCount = 0

        for (word in dictionary) {
            generateWordTemplate(word)?.let {
                templates.add(it)
                successCount++
            }
        }

        Log.d(TAG, "Generated $successCount word templates from ${dictionary.size} dictionary words")

        return templates
    }

    /**
     * Generate templates for most frequent words only
     */
    fun generateFrequentWordTemplates(maxWords: Int): List<ContinuousGestureRecognizer.Template> {
        val templates = mutableListOf<ContinuousGestureRecognizer.Template>()
        var count = 0

        for (word in dictionary) {
            if (count >= maxWords) break

            generateWordTemplate(word)?.let {
                templates.add(it)
                count++
            }
        }

        Log.d(TAG, "Generated $count frequent word templates")

        return templates
    }

    /**
     * Get word frequency for a given word
     */
    fun getWordFrequency(word: String): Int {
        return wordFrequencies.getOrDefault(word.lowercase(), 0)
    }

    /**
     * Check if word is in dictionary
     */
    fun isWordInDictionary(word: String): Boolean {
        return wordFrequencies.containsKey(word.lowercase())
    }

    /**
     * Set real key positions for 100% accurate coordinate mapping (CRITICAL FIX)
     * Replaces simplified grid calculations with actual keyboard layout positions
     */
    fun setRealKeyPositions(realPositions: Map<Char, PointF>?) {
        if (realPositions.isNullOrEmpty()) {
            Log.w(TAG, "No real key positions provided - using grid calculations")
            return
        }

        // Replace calculated coordinates with real positions
        keyboardCoords.clear()
        for ((keyChar, realPos) in realPositions) {
            keyboardCoords[keyChar] = ContinuousGestureRecognizer.Point(realPos.x.toDouble(), realPos.y.toDouble())
            Log.d(TAG, "Real coord: '$keyChar' = (${realPos.x},${realPos.y})")
        }

        Log.i(TAG, "✅ Using ${keyboardCoords.size} REAL key positions instead of grid calculations")
    }

    /**
     * Get dictionary size
     */
    fun getDictionarySize(): Int = dictionary.size

    /**
     * Get direct access to dictionary words (for efficient candidate generation)
     */
    fun getDictionary(): List<String> = dictionary.toList()

    /**
     * Get coordinate for a character (requires keyboard dimensions to be set)
     */
    fun getCharacterCoordinate(c: Char): ContinuousGestureRecognizer.Point? {
        val coord = keyboardCoords[c.lowercaseChar()] ?: return null
        return ContinuousGestureRecognizer.Point(coord.x, coord.y)
    }

    /**
     * Calculate gesture path length for a word (for complexity estimation)
     */
    fun calculateGesturePathLength(word: String): Double {
        val lowerWord = word.lowercase()
        var totalLength = 0.0
        var prevPoint: ContinuousGestureRecognizer.Point? = null

        for (c in lowerWord) {
            val point = keyboardCoords[c]
            if (point != null) {
                prevPoint?.let { prev ->
                    val dx = point.x - prev.x
                    val dy = point.y - prev.y
                    totalLength += sqrt(dx * dx + dy * dy)
                }
                prevPoint = point
            }
        }

        return totalLength
    }

    /**
     * Get words by length range
     */
    fun getWordsByLength(minLength: Int, maxLength: Int): List<String> {
        return dictionary.filter { it.length in minLength..maxLength }
    }

    /**
     * Generate templates with complexity filtering
     * Excludes words that would create overly complex or simple gestures
     */
    fun generateBalancedWordTemplates(maxWords: Int): List<ContinuousGestureRecognizer.Template> {
        val templates = mutableListOf<ContinuousGestureRecognizer.Template>()
        var count = 0

        for (word in dictionary) {
            if (count >= maxWords) break

            // Filter by gesture complexity - MUCH MORE SELECTIVE FOR LENGTH MATCHING
            val pathLength = calculateGesturePathLength(word)
            if (pathLength > 200 && pathLength < 2500) { // Stricter complexity filtering for better length matching
                generateWordTemplate(word)?.let {
                    templates.add(it)
                    count++
                }
            }
        }

        Log.d(TAG, "Generated $count balanced word templates")

        return templates
    }

    companion object {
        private const val TAG = "WordGestureTemplateGenerator"
    }
}
