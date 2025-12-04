package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import java.io.*
import java.nio.charset.StandardCharsets

object VocabularyCache {

    private const val TAG = "VocabularyCache"
    private const val MAGIC_NUMBER = 0x564F4342 // "VOCB" for VOCabulary Binary
    private const val VERSION = 2.toByte() // v2 includes contractions

    /**
     * Attempts to load vocabulary and contraction data from a binary cache file.
     *
     * @param context The Android application context.
     * @param vocabulary The map to populate with WordInfo.
     * @param vocabularyTrie The Trie to populate with words.
     * @param vocabularyByLength The map to populate with words grouped by length.
     * @param contractionPairings The map to populate with paired contractions.
     * @param nonPairedContractions The map to populate with non-paired contractions.
     * @return True if the cache was successfully loaded and is valid, false otherwise.
     */
    @JvmStatic
    fun tryLoadBinaryCache(
        context: Context,
        vocabulary: MutableMap<String, WordInfo>,
        vocabularyTrie: VocabularyTrie,
        vocabularyByLength: MutableMap<Int, MutableList<String>>,
        contractionPairings: MutableMap<String, MutableList<String>>,
        nonPairedContractions: MutableMap<String, String>
    ): Boolean {
        var wordCount = 0
        var pairedCount = 0
        var nonPairedCount = 0

        try {
            val cacheDir = context.cacheDir ?: run {
                Log.w(TAG, "Cannot load binary cache: getCacheDir() returned null")
                return false
            }

            val cacheFile = File(cacheDir, "vocab_cache.bin")
            if (!cacheFile.exists()) {
                Log.d(TAG, "Binary cache file does not exist: ${cacheFile.absolutePath}")
                return false
            }

            Log.d(TAG, "Loading binary cache from: ${cacheFile.absolutePath}")

            DataInputStream(BufferedInputStream(FileInputStream(cacheFile), 65536)).use { dis ->
                // Verify magic number and version
                val magic = dis.readInt()
                if (magic != MAGIC_NUMBER) {
                    Log.w(TAG, "Invalid binary cache magic number")
                    return false
                }

                val version = dis.readByte()
                if (version != VERSION) {
                    Log.w(TAG, "Unsupported binary cache version: $version (expected $VERSION)")
                    return false
                }

                // Read vocabulary words
                wordCount = dis.readInt()
                for (i in 0 until wordCount) {
                    val wordLen = dis.readUnsignedByte()
                    val wordBytes = ByteArray(wordLen)
                    dis.readFully(wordBytes)
                    val word = String(wordBytes, StandardCharsets.UTF_8)

                    val frequency = dis.readFloat()
                    val tier = dis.readByte()

                    vocabulary[word] = WordInfo(frequency, tier)
                    vocabularyTrie.insert(word)

                    vocabularyByLength.getOrPut(word.length) { mutableListOf() }.add(word)
                }

                // Read paired contractions
                pairedCount = dis.readInt()
                for (i in 0 until pairedCount) {
                    val baseLen = dis.readUnsignedByte()
                    val baseBytes = ByteArray(baseLen)
                    dis.readFully(baseBytes)
                    val baseWord = String(baseBytes, StandardCharsets.UTF_8)

                    val variantCount = dis.readUnsignedShort()
                    val variants = ArrayList<String>(variantCount)
                    for (j in 0 until variantCount) {
                        val variantLen = dis.readUnsignedByte()
                        val variantBytes = ByteArray(variantLen)
                        dis.readFully(variantBytes)
                        variants.add(String(variantBytes, StandardCharsets.UTF_8))
                    }
                    contractionPairings[baseWord] = variants
                }

                // Read non-paired contractions
                nonPairedCount = dis.readInt()
                for (i in 0 until nonPairedCount) {
                    val keyLen = dis.readUnsignedByte()
                    val keyBytes = ByteArray(keyLen)
                    dis.readFully(keyBytes)
                    val key = String(keyBytes, StandardCharsets.UTF_8)

                    val valueLen = dis.readUnsignedByte()
                    val valueBytes = ByteArray(valueLen)
                    dis.readFully(valueBytes)
                    val value = String(valueBytes, StandardCharsets.UTF_8)

                    nonPairedContractions[key] = value
                }
            } // dis.use handles closing

            Log.i(TAG, "📦 Loaded binary cache: $wordCount words, $pairedCount paired contractions, $nonPairedCount non-paired")
            vocabularyTrie.logStats()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Binary cache load failed: ${e.javaClass.name}: ${e.message}", e)
            return false
        }
    }

    /**
     * Saves the current vocabulary and contraction data to a binary cache file.
     *
     * @param context The Android application context.
     * @param vocabulary The map containing WordInfo.
     * @param contractionPairings The map containing paired contractions.
     * @param nonPairedContractions The map containing non-paired contractions.
     */
    @JvmStatic
    fun saveBinaryCache(
        context: Context,
        vocabulary: Map<String, WordInfo>,
        contractionPairings: Map<String, List<String>>,
        nonPairedContractions: Map<String, String>
    ) {
        try {
            val cacheFile = File(context.cacheDir, "vocab_cache.bin")
            DataOutputStream(BufferedOutputStream(FileOutputStream(cacheFile), 65536)).use { dos -> // Typo here, should be BufferedOutputStream
                // Write header
                dos.writeInt(MAGIC_NUMBER)
                dos.writeByte(VERSION.toInt())
                dos.writeInt(vocabulary.size)

                // Write all words
                for ((word, info) in vocabulary) {
                    val wordBytes = word.toByteArray(StandardCharsets.UTF_8)
                    dos.writeByte(wordBytes.size)
                    dos.write(wordBytes)
                    dos.writeFloat(info.frequency)
                    dos.writeByte(info.tier.toInt())
                }

                // Write paired contractions
                dos.writeInt(contractionPairings.size)
                for ((baseWord, variants) in contractionPairings) {
                    val baseBytes = baseWord.toByteArray(StandardCharsets.UTF_8)
                    dos.writeByte(baseBytes.size)
                    dos.write(baseBytes)

                    dos.writeShort(variants.size)
                    for (variant in variants) {
                        val variantBytes = variant.toByteArray(StandardCharsets.UTF_8)
                        dos.writeByte(variantBytes.size)
                        dos.write(variantBytes)
                    }
                }

                // Write non-paired contractions
                dos.writeInt(nonPairedContractions.size)
                for ((key, value) in nonPairedContractions) { // Corrected typo
                    val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
                    dos.writeByte(keyBytes.size)
                    dos.write(keyBytes)

                    val valueBytes = value.toByteArray(StandardCharsets.UTF_8)
                    dos.writeByte(valueBytes.size)
                    dos.write(valueBytes)
                }
            } // dos.use handles closing
            Log.i(TAG, "💾 Saved binary cache V$VERSION: ${vocabulary.size} words, " +
                         "${contractionPairings.size} paired contractions, " +
                         "${nonPairedContractions.size} non-paired")
        } catch (e: Exception) {
            Log.w(TAG, "Binary cache save failed: ${e.message}", e)
        }
    }
}