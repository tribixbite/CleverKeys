package tribixbite.keyboard2

import android.content.Context
import android.graphics.PointF
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Enhanced word prediction engine with advanced algorithms inspired by FlorisBoard
 * Features:
 * - Shape-based gesture matching
 * - Location-based scoring
 * - Path smoothing
 * - Trie-based dictionary for O(log n) lookups
 * - Dynamic programming for edit distance
 */
class EnhancedWordPredictor {
    private var dictionaryRoot: TrieNode = TrieNode()
    private val adjacentKeys: Map<Char, List<Char>> = buildAdjacentKeysMap()
    private val keyPositions: Map<Char, PointF> = buildKeyPositionsMap()

    // Configurable scoring weights (normalized from 0-255 scale to 0-1)
    private var shapeWeight = DEFAULT_SHAPE_WEIGHT
    private var locationWeight = DEFAULT_LOCATION_WEIGHT
    private var frequencyWeight = DEFAULT_FREQUENCY_WEIGHT

    // Endpoint and letter accuracy weights (0-1 scale)
    private var endpointBonusWeight = DEFAULT_ENDPOINT_BONUS_WEIGHT
    private var firstLetterWeight = DEFAULT_FIRST_LETTER_WEIGHT
    private var lastLetterWeight = DEFAULT_LAST_LETTER_WEIGHT

    /**
     * Update scoring weights from Config.
     * Config stores weights as 0-255 integers, we normalize to weights that sum to ~1.0
     */
    fun updateConfig(config: Config?) {
        if (config == null) return

        // Normalize weights from 0-255 scale to proportional weights
        val totalWeight = (config.swipe_confidence_shape_weight +
                          config.swipe_confidence_location_weight +
                          config.swipe_confidence_velocity_weight).toFloat()

        if (totalWeight > 0) {
            shapeWeight = config.swipe_confidence_shape_weight / totalWeight
            locationWeight = config.swipe_confidence_location_weight / totalWeight
            // Use velocity weight as frequency weight since we don't have velocity scoring here
            frequencyWeight = config.swipe_confidence_velocity_weight / totalWeight
        }

        // Endpoint and letter weights are normalized from 0-255 to 0-1
        // These act as bonus multipliers, not as part of the main weight sum
        endpointBonusWeight = config.swipe_endpoint_bonus_weight / 255f
        firstLetterWeight = config.swipe_first_letter_weight / 255f
        lastLetterWeight = config.swipe_last_letter_weight / 255f
    }

    /**
     * Load enhanced dictionary with frequency data
     */
    fun loadEnhancedDictionary(context: Context, language: String) {
        dictionaryRoot = TrieNode()
        val filename = "dictionaries/${language}_enhanced.txt"

        try {
            context.assets.open(filename).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.forEachLine { line ->
                        if (!line.startsWith("#") && line.trim().isNotEmpty()) {
                            val parts = line.trim().split("\t")
                            if (parts.size >= 2) {
                                val word = parts[0].lowercase()
                                val frequency = parts[1].toIntOrNull() ?: 0
                                insertWord(word, frequency)
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // Fall back to basic dictionary
            loadBasicDictionary()
        }
    }

    /**
     * Insert word into trie for fast prefix matching
     */
    private fun insertWord(word: String, frequency: Int) {
        var current = dictionaryRoot
        for (c in word) {
            current = current.children.getOrPut(c) { TrieNode() }
        }
        current.isWord = true
        current.frequency = frequency
        current.word = word
    }

    /**
     * Enhanced prediction using gesture path analysis
     */
    fun predictFromGesture(gesturePath: List<PointF>?, touchedKeys: List<Char>): List<String> {
        if (gesturePath == null || gesturePath.size < 2)
            return emptyList()

        // Smooth the gesture path to reduce noise
        val smoothedPath = smoothPath(gesturePath)

        // Resample path to fixed number of points for comparison
        val resampledPath = resamplePath(smoothedPath, SAMPLING_POINTS)

        // Normalize path for shape comparison
        val normalizedPath = normalizePath(resampledPath)

        // Find candidate words using trie traversal
        val candidates = findCandidates(touchedKeys, resampledPath, normalizedPath)

        // Sort by combined score
        candidates.sortByDescending { it.score }

        // Return top predictions
        return candidates.take(MAX_PREDICTIONS).map { it.word }
    }

    /**
     * Smooth gesture path using moving average
     */
    private fun smoothPath(path: List<PointF>): List<PointF> {
        if (path.size < SMOOTHING_WINDOW)
            return path

        return path.mapIndexed { i, point ->
            val windowStart = (i - SMOOTHING_WINDOW / 2).coerceAtLeast(0)
            val windowEnd = (i + SMOOTHING_WINDOW / 2).coerceAtMost(path.size - 1)

            var sumX = 0f
            var sumY = 0f
            var count = 0

            for (j in windowStart..windowEnd) {
                sumX += path[j].x
                sumY += path[j].y
                count++
            }

            val smoothX = sumX / count
            val smoothY = sumY / count

            // Blend with original point
            PointF(
                point.x * (1 - SMOOTHING_FACTOR) + smoothX * SMOOTHING_FACTOR,
                point.y * (1 - SMOOTHING_FACTOR) + smoothY * SMOOTHING_FACTOR
            )
        }
    }

    /**
     * Resample path to fixed number of points
     */
    private fun resamplePath(path: List<PointF>, numPoints: Int): List<PointF> {
        if (path.size < 2) return path

        val resampled = mutableListOf<PointF>()
        val totalLength = calculatePathLength(path)
        val segmentLength = totalLength / (numPoints - 1)

        var accumulatedLength = 0f
        resampled.add(PointF(path[0].x, path[0].y))

        for (i in 1 until path.size) {
            val prev = path[i - 1]
            val curr = path[i]
            val dist = distance(prev, curr)

            if (accumulatedLength + dist >= segmentLength) {
                val ratio = (segmentLength - accumulatedLength) / dist
                val x = prev.x + ratio * (curr.x - prev.x)
                val y = prev.y + ratio * (curr.y - prev.y)
                resampled.add(PointF(x, y))

                if (resampled.size >= numPoints)
                    break

                accumulatedLength = 0f
            } else {
                accumulatedLength += dist
            }
        }

        // Ensure we have exactly numPoints
        while (resampled.size < numPoints) {
            resampled.add(PointF(path.last().x, path.last().y))
        }

        return resampled
    }

    /**
     * Normalize path to unit square for shape comparison
     */
    private fun normalizePath(path: List<PointF>): List<PointF> {
        if (path.isEmpty()) return path

        // Find bounding box
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE

        for (p in path) {
            minX = min(minX, p.x)
            maxX = kotlin.math.max(maxX, p.x)
            minY = min(minY, p.y)
            maxY = kotlin.math.max(maxY, p.y)
        }

        var width = maxX - minX
        var height = maxY - minY

        if (width == 0f) width = 1f
        if (height == 0f) height = 1f

        // Scale to unit square
        return path.map { p ->
            PointF(
                (p.x - minX) / width,
                (p.y - minY) / height
            )
        }
    }

    /**
     * Find candidate words using advanced matching
     */
    private fun findCandidates(
        touchedKeys: List<Char>,
        resampledPath: List<PointF>,
        normalizedPath: List<PointF>
    ): MutableList<WordCandidate> {
        val candidates = mutableListOf<WordCandidate>()

        // Build approximate key sequence
        val keySequence = buildKeySequence(touchedKeys)

        // Use trie to find words with similar prefixes
        findCandidatesFromTrie(
            dictionaryRoot, keySequence, "", 0, candidates,
            resampledPath, normalizedPath
        )

        return candidates
    }

    /**
     * Recursive trie traversal for candidate finding
     */
    private fun findCandidatesFromTrie(
        node: TrieNode,
        target: String,
        current: String,
        targetIndex: Int,
        candidates: MutableList<WordCandidate>,
        resampledPath: List<PointF>,
        normalizedPath: List<PointF>
    ) {
        if (node.isWord && abs(current.length - target.length) <= 2) {
            // Calculate score for this word
            val score = calculateWordScore(
                node.word!!, node.frequency, target,
                resampledPath, normalizedPath
            )
            candidates.add(WordCandidate(node.word!!, score))
        }

        // Continue traversal if we haven't gone too far
        if (targetIndex < target.length + 2) {
            for ((c, childNode) in node.children) {
                // Allow character if it matches target or is adjacent
                if (targetIndex < target.length) {
                    val targetChar = target[targetIndex]
                    if (c == targetChar || isAdjacent(c, targetChar)) {
                        findCandidatesFromTrie(
                            childNode, target, current + c,
                            targetIndex + 1, candidates, resampledPath, normalizedPath
                        )
                    }
                }

                // Also explore skipping characters (for shorter words)
                if (current.length < target.length) {
                    findCandidatesFromTrie(
                        childNode, target, current + c,
                        targetIndex, candidates, resampledPath, normalizedPath
                    )
                }
            }
        }
    }

    /**
     * Calculate comprehensive score for a word
     */
    private fun calculateWordScore(
        word: String,
        frequency: Int,
        keySequence: String,
        resampledPath: List<PointF>,
        normalizedPath: List<PointF>
    ): Float {
        // Shape score - how well does the word's ideal path match the gesture?
        val shapeScore = calculateShapeScore(word, normalizedPath)

        // Location score - how close are the gesture points to the expected keys?
        val locationScore = calculateLocationScore(word, resampledPath)

        // Frequency score - how common is this word?
        val frequencyScore = frequency / 50000f

        // Length penalty - penalize words that are very different in length
        val lengthDiff = abs(word.length - keySequence.length).toFloat()
        val lengthScore = 1.0f / (1.0f + lengthDiff * LENGTH_PENALTY)

        // Base score using configurable weights
        var baseScore = (shapeScore * shapeWeight +
            locationScore * locationWeight +
            frequencyScore * frequencyWeight) * lengthScore

        // Endpoint accuracy bonus - how well do gesture start/end match first/last keys?
        val endpointBonus = calculateEndpointBonus(word, resampledPath)
        baseScore += endpointBonus * endpointBonusWeight * 0.2f  // Scale bonus contribution

        // First letter accuracy bonus
        val firstLetterBonus = calculateFirstLetterAccuracy(word, resampledPath)
        baseScore += firstLetterBonus * firstLetterWeight * 0.15f

        // Last letter accuracy bonus
        val lastLetterBonus = calculateLastLetterAccuracy(word, resampledPath)
        baseScore += lastLetterBonus * lastLetterWeight * 0.15f

        return baseScore
    }

    /**
     * Calculate endpoint accuracy bonus - how well gesture endpoints match word endpoints
     */
    private fun calculateEndpointBonus(word: String, resampledPath: List<PointF>): Float {
        if (word.isEmpty() || resampledPath.size < 2) return 0f

        val firstChar = word.first().lowercaseChar()
        val lastChar = word.last().lowercaseChar()

        val firstKeyPos = keyPositions[firstChar] ?: return 0f
        val lastKeyPos = keyPositions[lastChar] ?: return 0f

        val gestureStart = resampledPath.first()
        val gestureEnd = resampledPath.last()

        // Calculate distances (normalized by typical key width ~0.1 in normalized coords)
        val startDistance = distance(gestureStart, firstKeyPos)
        val endDistance = distance(gestureEnd, lastKeyPos)

        // Convert to scores (closer = higher score)
        val startScore = 1.0f / (1.0f + startDistance * 10f)
        val endScore = 1.0f / (1.0f + endDistance * 10f)

        return (startScore + endScore) / 2f
    }

    /**
     * Calculate first letter accuracy - how close is gesture start to expected first key
     */
    private fun calculateFirstLetterAccuracy(word: String, resampledPath: List<PointF>): Float {
        if (word.isEmpty() || resampledPath.isEmpty()) return 0f

        val firstChar = word.first().lowercaseChar()
        val expectedPos = keyPositions[firstChar] ?: return 0f
        val gestureStart = resampledPath.first()

        val dist = distance(gestureStart, expectedPos)
        return 1.0f / (1.0f + dist * 10f)
    }

    /**
     * Calculate last letter accuracy - how close is gesture end to expected last key
     */
    private fun calculateLastLetterAccuracy(word: String, resampledPath: List<PointF>): Float {
        if (word.isEmpty() || resampledPath.isEmpty()) return 0f

        val lastChar = word.last().lowercaseChar()
        val expectedPos = keyPositions[lastChar] ?: return 0f
        val gestureEnd = resampledPath.last()

        val dist = distance(gestureEnd, expectedPos)
        return 1.0f / (1.0f + dist * 10f)
    }

    /**
     * Calculate shape similarity between ideal word path and gesture
     */
    private fun calculateShapeScore(word: String, normalizedPath: List<PointF>): Float {
        // Generate ideal path for word
        var idealPath = generateIdealPath(word)

        if (idealPath.size < 2) return 0f

        // Resample and normalize ideal path
        idealPath = resamplePath(idealPath, SAMPLING_POINTS)
        idealPath = normalizePath(idealPath)

        // Calculate Euclidean distance between paths
        var totalDistance = 0f
        for (i in 0 until min(idealPath.size, normalizedPath.size)) {
            totalDistance += distance(idealPath[i], normalizedPath[i])
        }

        // Convert distance to similarity score (0-1)
        val avgDistance = totalDistance / SAMPLING_POINTS
        return 1.0f / (1.0f + avgDistance)
    }

    /**
     * Calculate location accuracy score
     */
    private fun calculateLocationScore(word: String, resampledPath: List<PointF>): Float {
        var idealPath = generateIdealPath(word)

        if (idealPath.size < 2) return 0f

        idealPath = resamplePath(idealPath, SAMPLING_POINTS)

        // Calculate average distance between corresponding points
        var totalDistance = 0f
        for (i in 0 until min(idealPath.size, resampledPath.size)) {
            totalDistance += distance(idealPath[i], resampledPath[i])
        }

        val avgDistance = totalDistance / SAMPLING_POINTS

        // Normalize by keyboard size (approximate)
        val normalizedDistance = avgDistance / 100f // Assuming ~100px key width

        return 1.0f / (1.0f + normalizedDistance)
    }

    /**
     * Generate ideal swipe path for a word
     */
    private fun generateIdealPath(word: String): List<PointF> {
        return word.mapNotNull { c ->
            keyPositions[c]?.let { PointF(it.x, it.y) }
        }
    }

    /**
     * Build key positions map for QWERTY layout
     */
    private fun buildKeyPositionsMap(): Map<Char, PointF> {
        val positions = mutableMapOf<Char, PointF>()

        // QWERTY layout positions (normalized 0-1)
        val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val rowY = floatArrayOf(0.25f, 0.5f, 0.75f)
        val rowOffsets = floatArrayOf(0f, 0.05f, 0.15f)

        for (r in rows.indices) {
            val row = rows[r]
            val y = rowY[r]
            val offset = rowOffsets[r]

            for (c in row.indices) {
                val x = offset + (c * (1.0f - 2 * offset) / row.length)
                positions[row[c]] = PointF(x, y)
            }
        }

        return positions
    }

    /**
     * Build adjacent keys map
     */
    private fun buildAdjacentKeysMap(): Map<Char, List<Char>> {
        val adjacent = mutableMapOf<Char, List<Char>>()
        val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

        for (r in rows.indices) {
            val row = rows[r]
            for (c in row.indices) {
                val ch = row[c]
                val neighbors = mutableListOf<Char>()

                // Same row neighbors
                if (c > 0) neighbors.add(row[c - 1])
                if (c < row.length - 1) neighbors.add(row[c + 1])

                // Adjacent row neighbors
                if (r > 0) {
                    val prevRow = rows[r - 1]
                    for (i in (c - 1).coerceAtLeast(0)..(c + 1).coerceAtMost(prevRow.length - 1))
                        neighbors.add(prevRow[i])
                }
                if (r < rows.size - 1) {
                    val nextRow = rows[r + 1]
                    for (i in (c - 1).coerceAtLeast(0)..(c + 1).coerceAtMost(nextRow.length - 1))
                        neighbors.add(nextRow[i])
                }

                adjacent[ch] = neighbors
            }
        }

        return adjacent
    }

    /**
     * Check if two keys are adjacent
     */
    private fun isAdjacent(c1: Char, c2: Char): Boolean {
        return adjacentKeys[c1]?.contains(c2) == true
    }

    /**
     * Build key sequence from touched keys
     */
    private fun buildKeySequence(touchedKeys: List<Char>): String {
        return touchedKeys
            .filter { it.isLetter() }
            .joinToString("") { it.lowercaseChar().toString() }
    }

    /**
     * Calculate distance between two points
     */
    private fun distance(p1: PointF, p2: PointF): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Calculate total path length
     */
    private fun calculatePathLength(path: List<PointF>): Float {
        var length = 0f
        for (i in 1 until path.size) {
            length += distance(path[i - 1], path[i])
        }
        return length
    }

    /**
     * Load basic dictionary as fallback
     */
    private fun loadBasicDictionary() {
        val words = arrayOf(
            "the", "and", "you", "that", "was", "for", "are", "with", "his", "they",
            "this", "have", "from", "word", "but", "what", "some", "can", "hello", "world"
        )

        words.forEachIndexed { i, word ->
            insertWord(word, 10000 - i * 100)
        }
    }

    /**
     * Trie node for efficient dictionary storage
     */
    private class TrieNode {
        val children = mutableMapOf<Char, TrieNode>()
        var isWord = false
        var word: String? = null
        var frequency = 0
    }

    /**
     * Word candidate with score
     */
    private data class WordCandidate(
        val word: String,
        val score: Float
    )

    companion object {
        // Algorithm parameters from FlorisBoard research
        private const val MAX_PREDICTIONS = 10
        private const val SAMPLING_POINTS = 50 // Number of points to resample gesture to
        private const val LENGTH_PENALTY = 0.1f

        // Default scoring weights (used if config not set)
        // Based on UK config: shape=168, location=130, velocity=60 -> normalized
        private const val DEFAULT_SHAPE_WEIGHT = 0.47f  // 168/(168+130+60)
        private const val DEFAULT_LOCATION_WEIGHT = 0.36f  // 130/(168+130+60)
        private const val DEFAULT_FREQUENCY_WEIGHT = 0.17f  // 60/(168+130+60)

        // Default endpoint and letter accuracy weights (0-1 scale from 0-255)
        // Based on UK config: endpoint=200, first_letter=150, last_letter=150
        private const val DEFAULT_ENDPOINT_BONUS_WEIGHT = 0.78f  // 200/255
        private const val DEFAULT_FIRST_LETTER_WEIGHT = 0.59f   // 150/255
        private const val DEFAULT_LAST_LETTER_WEIGHT = 0.59f    // 150/255

        // Path smoothing parameters
        private const val SMOOTHING_WINDOW = 3
        private const val SMOOTHING_FACTOR = 0.5f
    }
}
