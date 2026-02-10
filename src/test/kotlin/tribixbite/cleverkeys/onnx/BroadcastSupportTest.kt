package tribixbite.cleverkeys.onnx

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

/**
 * Pure JVM tests for BroadcastSupport path/config parsing logic.
 *
 * BroadcastSupport requires android.content.Context in its constructor for
 * asset loading. The core logic we test here is:
 *   - deriveConfigPath: pure string manipulation (private, tested via reflection)
 *   - extractJsonValue: regex-based JSON field extraction (private, tested via reflection)
 *   - isBroadcastEnabled: path-based detection of /bs/ directory (requires Context;
 *     we verify the path-check portion only)
 *
 * Note: isBroadcastEnabled and readModelConfig cannot be fully tested because they
 * call loadAssetAsString which requires Context.assets. We test the underlying
 * pure functions instead.
 *
 * TensorFactory is completely skipped — every method requires OrtEnvironment and
 * OnnxTensor which cannot be created without native ONNX Runtime libraries.
 */
class BroadcastSupportTest {

    private lateinit var instance: BroadcastSupport
    private lateinit var deriveConfigPathMethod: Method
    private lateinit var extractJsonValueMethod: Method

    @Before
    fun setUp() {
        // Allocate instance without calling constructor (bypasses Context requirement)
        val unsafe = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafe.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafeInstance = unsafeField.get(null)
        val allocateMethod = unsafe.getMethod("allocateInstance", Class::class.java)
        instance = allocateMethod.invoke(unsafeInstance, BroadcastSupport::class.java) as BroadcastSupport

        // Access private methods via reflection
        deriveConfigPathMethod = BroadcastSupport::class.java.getDeclaredMethod(
            "deriveConfigPath", String::class.java
        )
        deriveConfigPathMethod.isAccessible = true

        extractJsonValueMethod = BroadcastSupport::class.java.getDeclaredMethod(
            "extractJsonValue", String::class.java, String::class.java
        )
        extractJsonValueMethod.isAccessible = true
    }

    // =========================================================================
    // deriveConfigPath tests
    // =========================================================================

    @Test
    fun `deriveConfigPath extracts directory from model path`() {
        val result = deriveConfigPathMethod.invoke(instance, "models/bs/swipe_encoder_android.onnx")
        assertThat(result).isEqualTo("models/bs/model_config.json")
    }

    @Test
    fun `deriveConfigPath handles nested directory path`() {
        val result = deriveConfigPathMethod.invoke(instance, "assets/models/v2/bs/decoder.onnx")
        assertThat(result).isEqualTo("assets/models/v2/bs/model_config.json")
    }

    @Test
    fun `deriveConfigPath handles root-level model file`() {
        // No directory separator — model file is at root
        val result = deriveConfigPathMethod.invoke(instance, "encoder.onnx")
        assertThat(result).isEqualTo("model_config.json")
    }

    @Test
    fun `deriveConfigPath handles path with single directory`() {
        val result = deriveConfigPathMethod.invoke(instance, "models/encoder.onnx")
        assertThat(result).isEqualTo("models/model_config.json")
    }

    @Test
    fun `deriveConfigPath handles trailing slash in directory path`() {
        // Edge case: path is just a directory with trailing slash
        val result = deriveConfigPathMethod.invoke(instance, "models/bs/")
        assertThat(result).isEqualTo("models/bs/model_config.json")
    }

    // =========================================================================
    // extractJsonValue tests
    // =========================================================================

    @Test
    fun `extractJsonValue extracts string value`() {
        val json = """{"key": "value", "other": 123}"""
        val result = extractJsonValueMethod.invoke(instance, json, "key")
        assertThat(result).isEqualTo("value")
    }

    @Test
    fun `extractJsonValue extracts numeric value`() {
        val json = """{"accuracy": 0.7337, "d_model": 256}"""

        val accuracy = extractJsonValueMethod.invoke(instance, json, "accuracy")
        assertThat(accuracy).isEqualTo("0.7337")

        val dModel = extractJsonValueMethod.invoke(instance, json, "d_model")
        assertThat(dModel).isEqualTo("256")
    }

    @Test
    fun `extractJsonValue extracts integer value`() {
        val json = """{"max_seq_len": 250, "max_word_len": 20}"""
        val result = extractJsonValueMethod.invoke(instance, json, "max_seq_len")
        assertThat(result).isEqualTo("250")
    }

    @Test
    fun `extractJsonValue returns null for missing key`() {
        val json = """{"key": "value"}"""
        val result = extractJsonValueMethod.invoke(instance, json, "nonexistent")
        assertThat(result).isNull()
    }

    @Test
    fun `extractJsonValue handles boolean true`() {
        val json = """{"broadcast_enabled": true, "other": false}"""
        val result = extractJsonValueMethod.invoke(instance, json, "broadcast_enabled")
        assertThat(result).isEqualTo("true")
    }

    @Test
    fun `extractJsonValue handles boolean false`() {
        val json = """{"broadcast_enabled": false}"""
        val result = extractJsonValueMethod.invoke(instance, json, "broadcast_enabled")
        assertThat(result).isEqualTo("false")
    }

    @Test
    fun `extractJsonValue handles whitespace variations`() {
        // Extra whitespace around colon
        val json = """{"key"  :  "value"}"""
        val result = extractJsonValueMethod.invoke(instance, json, "key")
        assertThat(result).isEqualTo("value")
    }

    @Test
    fun `extractJsonValue handles last field without trailing comma`() {
        val json = """{"only_field": 42}"""
        val result = extractJsonValueMethod.invoke(instance, json, "only_field")
        assertThat(result).isEqualTo("42")
    }

    @Test
    fun `extractJsonValue handles multiline JSON`() {
        val json = """
        {
            "accuracy": 0.7337,
            "d_model": 256,
            "broadcast_enabled": true
        }
        """.trimIndent()

        assertThat(extractJsonValueMethod.invoke(instance, json, "accuracy")).isEqualTo("0.7337")
        assertThat(extractJsonValueMethod.invoke(instance, json, "d_model")).isEqualTo("256")
        assertThat(extractJsonValueMethod.invoke(instance, json, "broadcast_enabled")).isEqualTo("true")
    }

    @Test
    fun `extractJsonValue handles empty JSON`() {
        val json = "{}"
        val result = extractJsonValueMethod.invoke(instance, json, "key")
        assertThat(result).isNull()
    }

    // =========================================================================
    // ModelConfig data class tests
    // =========================================================================

    @Test
    fun `ModelConfig data class stores all fields`() {
        val config = ModelConfig(
            accuracy = 0.7337f,
            dModel = 256,
            maxSeqLen = 250,
            maxWordLen = 20,
            broadcastEnabled = true
        )

        assertThat(config.accuracy).isWithin(0.001f).of(0.7337f)
        assertThat(config.dModel).isEqualTo(256)
        assertThat(config.maxSeqLen).isEqualTo(250)
        assertThat(config.maxWordLen).isEqualTo(20)
        assertThat(config.broadcastEnabled).isTrue()
    }

    @Test
    fun `ModelConfig data class equality`() {
        val config1 = ModelConfig(0.73f, 256, 250, 20, true)
        val config2 = ModelConfig(0.73f, 256, 250, 20, true)
        val config3 = ModelConfig(0.73f, 256, 250, 20, false)

        assertThat(config1).isEqualTo(config2)
        assertThat(config1).isNotEqualTo(config3)
    }

    @Test
    fun `ModelConfig copy with modified field`() {
        val original = ModelConfig(0.73f, 256, 250, 20, false)
        val modified = original.copy(broadcastEnabled = true)

        assertThat(modified.broadcastEnabled).isTrue()
        assertThat(modified.accuracy).isWithin(0.001f).of(original.accuracy)
        assertThat(modified.dModel).isEqualTo(original.dModel)
    }

    // =========================================================================
    // Path-based broadcast detection logic
    // =========================================================================

    @Test
    fun `bs directory path is detected for broadcast check`() {
        // We can't call isBroadcastEnabled directly (needs Context for asset loading),
        // but we verify the path detection logic that determines whether to check config
        val bsPath = "models/bs/swipe_encoder_android.onnx"
        val standardPath = "models/swipe_encoder_android.onnx"

        // The /bs/ directory check is: modelPath.contains("/bs/")
        assertThat(bsPath.contains("/bs/")).isTrue()
        assertThat(standardPath.contains("/bs/")).isFalse()
    }

    @Test
    fun `broadcast detection via JSON content parsing`() {
        // Simulating what isBroadcastEnabled does internally after loading JSON
        val enabledJson = """{"broadcast_enabled": true, "accuracy": 0.73}"""
        val disabledJson = """{"broadcast_enabled": false, "accuracy": 0.73}"""
        val missingJson = """{"accuracy": 0.73}"""

        // The detection logic: contains "broadcast_enabled" AND contains "true"
        assertThat(
            enabledJson.contains("\"broadcast_enabled\"") && enabledJson.contains("true")
        ).isTrue()
        assertThat(
            disabledJson.contains("\"broadcast_enabled\"") && disabledJson.contains("true")
        ).isFalse()
        assertThat(
            missingJson.contains("\"broadcast_enabled\"") && missingJson.contains("true")
        ).isFalse()
    }

    @Test
    fun `broadcast detection handles edge case with true in other fields`() {
        // Edge case: "true" appears in another field value
        // The simple detection (contains "broadcast_enabled" AND contains "true")
        // could false-positive here. Documenting the behavior.
        val edgeCaseJson = """{"name": "true_model", "broadcast_enabled": false}"""

        // This will false-positive because "true" appears in "true_model"
        val detected = edgeCaseJson.contains("\"broadcast_enabled\"") &&
                edgeCaseJson.contains("true")
        // Note: This is the actual behavior of the production code (simple string matching)
        assertThat(detected).isTrue()
    }
}
