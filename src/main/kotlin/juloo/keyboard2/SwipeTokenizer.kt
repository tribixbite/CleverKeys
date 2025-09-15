package juloo.keyboard2

/**
 * Complete tokenizer matching Java implementation
 */
class SwipeTokenizer {
    
    companion object {
        private const val TAG = "SwipeTokenizer"
        private const val VOCAB_SIZE = 30
    }
    
    // Character to token mapping
    private val charToToken = mutableMapOf<Char, Int>()
    private val tokenToChar = mutableMapOf<Int, Char>()
    
    fun initialize() {
        // Initialize character mappings
        charToToken.clear()
        tokenToChar.clear()
        
        // Special tokens
        tokenToChar[0] = '\u0000' // PAD
        tokenToChar[1] = '\u0001' // UNK  
        tokenToChar[2] = '\u0002' // SOS
        tokenToChar[3] = '\u0003' // EOS
        
        // Character tokens (4-29 for a-z)
        ('a'..'z').forEachIndexed { index, char ->
            val tokenId = index + 4
            charToToken[char] = tokenId
            tokenToChar[tokenId] = char
        }
        
        logD("Tokenizer initialized with ${charToToken.size} character mappings")
    }
    
    fun charToToken(char: Char): Int {
        return charToToken[char.lowercaseChar()] ?: 1 // UNK token
    }
    
    fun tokenToChar(token: Int): Char {
        return tokenToChar[token] ?: '?'
    }
    
    fun tokensToWord(tokens: List<Long>): String {
        return tokens.mapNotNull { token ->
            val char = tokenToChar(token.toInt())
            if (char.isLetter()) char.toString() else null
        }.joinToString("")
    }
    
    fun wordToTokens(word: String): List<Long> {
        val tokens = mutableListOf<Long>()
        tokens.add(2L) // SOS token
        
        word.lowercase().forEach { char ->
            tokens.add(charToToken(char).toLong())
        }
        
        tokens.add(3L) // EOS token
        return tokens
    }
    
    fun isValidToken(token: Int): Boolean {
        return token in 0 until VOCAB_SIZE
    }
    
    val vocabularySize: Int get() = VOCAB_SIZE
}

/**
 * Use complete optimized vocabulary implementation
 */
class OptimizedVocabulary(context: Context) {
    private val impl = OptimizedVocabularyImpl(context)
    
    suspend fun loadVocabulary(): Boolean = impl.loadVocabulary()
    fun isLoaded(): Boolean = impl.isLoaded()
    fun getStats(): VocabStats = impl.getStats().let { stats ->
        VocabStats(stats.totalWords)
    }
    
    fun filterPredictions(candidates: List<CandidateWord>, stats: SwipeStats): List<FilteredPrediction> {
        val implCandidates = candidates.map { 
            OptimizedVocabularyImpl.CandidateWord(it.word, it.confidence) 
        }
        val implStats = OptimizedVocabularyImpl.SwipeStats(stats.pathLength, stats.duration, stats.straightnessRatio)
        
        return impl.filterPredictions(implCandidates, implStats).map {
            FilteredPrediction(it.word, it.score)
        }
    }
    
    data class VocabStats(val totalWords: Int)
    data class CandidateWord(val word: String, val confidence: Float)
    data class FilteredPrediction(val word: String, val score: Float)
    data class SwipeStats(val pathLength: Float, val duration: Float, val straightnessRatio: Float)
}