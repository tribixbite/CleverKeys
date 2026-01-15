---
title: Language Packs - Technical Specification
user_guide: ../../layouts/language-packs.md
status: implemented
version: v1.2.7
---

# Language Packs Technical Specification

## Overview

Language packs are downloadable packages containing dictionaries, layouts, autocorrect rules, and neural model vocabularies for specific languages.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| PackManager | `PackManager.kt` | Download and installation |
| PackRegistry | `PackRegistry.kt` | Available packs catalog |
| PackExtractor | `PackExtractor.kt` | Package decompression |
| PackValidator | `PackValidator.kt` | Integrity verification |
| Config | `Config.kt` | Pack preferences |

## Data Model

### Language Pack Structure

```kotlin
// PackManager.kt
data class LanguagePack(
    val id: String,                    // Unique identifier
    val languageCode: String,          // ISO code (fr, de, es)
    val displayName: String,           // "French"
    val nativeName: String,            // "Français"
    val version: Int,                  // Pack version
    val size: Long,                    // Bytes
    val contents: PackContents,        // Included components
    val downloadUrl: String,           // Package URL
    val checksum: String               // SHA256 hash
)

data class PackContents(
    val hasDictionary: Boolean,
    val hasLayout: Boolean,
    val hasAutocorrect: Boolean,
    val hasVocabulary: Boolean,        // Neural model vocab
    val dictionarySize: Int,           // Word count
    val layoutVariants: List<String>   // Layout IDs
)
```

### Pack Manifest

```json
// pack_manifest.json (inside pack)
{
    "id": "lang_fr",
    "language": "fr",
    "display_name": "French",
    "native_name": "Français",
    "version": 3,
    "contents": {
        "dictionary": "dictionary.bin",
        "layout": "layout_azerty.json",
        "autocorrect": "autocorrect.json",
        "vocabulary": "vocab_fr.txt"
    },
    "metadata": {
        "author": "CleverKeys",
        "updated": "2024-12-01"
    }
}
```

## Pack Registry

### Available Packs

```kotlin
// PackRegistry.kt
object PackRegistry {
    private val availablePacks = listOf(
        LanguagePack(
            id = "lang_fr",
            languageCode = "fr",
            displayName = "French",
            nativeName = "Français",
            version = 3,
            size = 8_500_000,
            contents = PackContents(
                hasDictionary = true,
                hasLayout = true,
                hasAutocorrect = true,
                hasVocabulary = true,
                dictionarySize = 85000,
                layoutVariants = listOf("azerty_fr")
            ),
            downloadUrl = "https://packs.cleverkeys.app/fr/v3.zip",
            checksum = "sha256:abc123..."
        ),
        // ... more packs
    )

    fun getAvailablePacks(): List<LanguagePack>
    fun getPack(id: String): LanguagePack?
    fun getPacksForRegion(region: String): List<LanguagePack>
}
```

## Download and Installation

### Download Flow

```
User selects pack
        ↓
PackManager.downloadPack(packId)
        ↓
Download from CDN with progress
        ↓
Verify checksum (SHA256)
        ↓
PackExtractor.extract(zipFile)
        ↓
PackValidator.validate(contents)
        ↓
Install components to appropriate locations
        ↓
Update installed packs registry
        ↓
Notify listeners (language available)
```

### Download Implementation

```kotlin
// PackManager.kt
class PackManager {
    suspend fun downloadPack(packId: String): Result<Unit> {
        val pack = registry.getPack(packId) ?: return Result.failure(...)

        // Download with progress
        val tempFile = downloadFile(pack.downloadUrl) { progress ->
            notifyProgress(packId, progress)
        }

        // Verify integrity
        val actualChecksum = tempFile.sha256()
        if (actualChecksum != pack.checksum) {
            tempFile.delete()
            return Result.failure(ChecksumMismatchException())
        }

        // Extract and install
        val extracted = extractor.extract(tempFile, getPackDir(packId))
        validator.validate(extracted)

        // Install components
        installDictionary(extracted, pack.languageCode)
        installLayout(extracted)
        installAutocorrect(extracted, pack.languageCode)
        installVocabulary(extracted, pack.languageCode)

        // Update registry
        installedPacks.add(packId)
        saveInstalledPacks()

        return Result.success(Unit)
    }
}
```

## Pack Contents Installation

### Dictionary Installation

```kotlin
// PackManager.kt
private fun installDictionary(packDir: File, langCode: String) {
    val dictFile = File(packDir, "dictionary.bin")
    if (!dictFile.exists()) return

    val targetDir = File(context.filesDir, "dictionaries")
    val targetFile = File(targetDir, "${langCode}_dict.bin")

    dictFile.copyTo(targetFile, overwrite = true)

    // Register with dictionary manager
    dictionaryManager.registerDictionary(langCode, targetFile)
}
```

### Layout Installation

```kotlin
// PackManager.kt
private fun installLayout(packDir: File) {
    val layoutFile = File(packDir, "layout.json")
    if (!layoutFile.exists()) return

    val layout = layoutParser.parse(layoutFile)
    layoutManager.registerLayout(layout)
}
```

### Vocabulary Installation

```kotlin
// PackManager.kt
private fun installVocabulary(packDir: File, langCode: String) {
    val vocabFile = File(packDir, "vocabulary.txt")
    if (!vocabFile.exists()) return

    // Load into neural vocabulary manager
    val vocab = OptimizedVocabulary.loadFromFile(vocabFile)
    vocabularyManager.registerVocabulary(langCode, vocab)
}
```

## Pack Updates

```kotlin
// PackManager.kt
suspend fun checkForUpdates(): List<PackUpdate> {
    val updates = mutableListOf<PackUpdate>()

    installedPacks.forEach { packId ->
        val installed = getInstalledVersion(packId)
        val available = registry.getPack(packId)?.version ?: 0

        if (available > installed) {
            updates.add(PackUpdate(packId, installed, available))
        }
    }

    return updates
}

data class PackUpdate(
    val packId: String,
    val installedVersion: Int,
    val availableVersion: Int
)
```

## Storage Management

```kotlin
// PackManager.kt
fun getPackStorageInfo(): StorageInfo {
    val packsDir = File(context.filesDir, "packs")
    val totalSize = packsDir.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }

    return StorageInfo(
        totalUsed = totalSize,
        packCount = installedPacks.size,
        byPack = installedPacks.associateWith { getPackSize(it) }
    )
}

fun removePack(packId: String) {
    val pack = registry.getPack(packId) ?: return

    // Remove dictionary
    dictionaryManager.unregisterDictionary(pack.languageCode)

    // Remove layout
    pack.contents.layoutVariants.forEach {
        layoutManager.unregisterLayout(it)
    }

    // Remove vocabulary
    vocabularyManager.unregisterVocabulary(pack.languageCode)

    // Delete files
    File(context.filesDir, "packs/$packId").deleteRecursively()

    installedPacks.remove(packId)
    saveInstalledPacks()
}
```

## Configuration

| Setting | Key | Default | Description |
|---------|-----|---------|-------------|
| **Installed Packs** | `installed_packs` | [] | Downloaded pack IDs |
| **Auto-Update** | `auto_update_packs` | false | Update automatically |
| **WiFi Only** | `packs_wifi_only` | true | Download on WiFi only |
| **Storage Limit** | `packs_storage_limit` | 500MB | Max pack storage |

## Related Specifications

- [Dictionary System](../../../specs/dictionary-and-language-system.md) - Dictionary management
- [Layout System](../../../specs/layout-system.md) - Layout management
- [Neural Prediction](../../../specs/neural-prediction.md) - Vocabulary integration
