package tribixbite.cleverkeys.gif

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Manages GIF pack downloads and installation using WorkManager.
 *
 * Tiered download system:
 * 1. Core pack: DB (gzipped ~6MB) + thumbnails (~6MB) = ~12MB total
 * 2. Category packs: Full animations grouped by emotion (~50MB each)
 * 3. On-demand: Individual animations fetched when user taps (lazy)
 *
 * WorkManager ensures downloads survive process death, respect network
 * constraints, and can run while the keyboard is in background.
 *
 * Pack format: ZIP/tar.gz archives hosted on GitHub Releases.
 */
class GifPackManager private constructor(private val context: Context) {

    private val assetManager = GifAssetManager.getInstance(context)

    private val packsDir: File by lazy {
        File(context.filesDir, "gif_packs").also { it.mkdirs() }
    }

    // Observable download state for UI
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    /**
     * Available pack definitions.
     * TODO: Fetch from manifest URL for dynamic pack list updates
     */
    val availablePacks = listOf(
        PackInfo(
            id = "core",
            name = "Core Pack",
            description = "Database + thumbnails for 130K GIFs",
            gifCount = 130000,
            sizeBytes = 12_000_000L,
            downloadUrl = "https://github.com/tribixbite/CleverKeys/releases/download/CleverKeys-GIF/gifs_v2.db.gz",
            isBundled = false
        )
    )

    /**
     * Check if the core database is installed.
     */
    fun isCoreInstalled(): Boolean {
        val dbFile = context.getDatabasePath(GifDatabase.DATABASE_NAME)
        return dbFile.exists() && dbFile.length() > 0
    }

    /**
     * Check which packs are installed.
     */
    fun getInstalledPacks(): List<String> {
        val installed = mutableListOf<String>()

        if (isCoreInstalled() && assetManager.getThumbnailCount() > 0) {
            installed.add("core")
        }

        // Check for downloaded category packs
        packsDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val manifest = File(dir, "manifest.json")
            if (manifest.exists()) {
                installed.add(dir.name)
            }
        }

        return installed
    }

    /**
     * Get pack installation status.
     */
    fun getPackStatus(packId: String): PackStatus {
        if (packId == "core") {
            return if (isCoreInstalled()) {
                PackStatus.Installed(assetManager.getThumbnailCount())
            } else {
                PackStatus.NotInstalled
            }
        }

        val manifestFile = File(packsDir, "$packId/manifest.json")
        return if (manifestFile.exists()) {
            PackStatus.Installed(assetManager.getCachedCount())
        } else {
            PackStatus.NotInstalled
        }
    }

    /**
     * Enqueue core pack download via WorkManager.
     * Respects WiFi-only constraint from Config.
     */
    fun enqueueCorePack(wifiOnly: Boolean = true): java.util.UUID {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresStorageNotLow(true)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<GifPackDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    GifPackDownloadWorker.KEY_PACK_ID to "core"
                )
            )
            .addTag(WORK_TAG_GIF_DOWNLOAD)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                WORK_NAME_CORE_PACK,
                ExistingWorkPolicy.KEEP,  // Don't restart if already running
                workRequest
            )

        return workRequest.id
    }

    /**
     * Observe download progress from WorkManager.
     */
    fun observeDownloadProgress(): LiveDataFlow {
        return LiveDataFlow(
            WorkManager.getInstance(context)
                .getWorkInfosByTagLiveData(WORK_TAG_GIF_DOWNLOAD)
        )
    }

    /**
     * Cancel all pending/running GIF downloads.
     */
    fun cancelDownloads() {
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG_GIF_DOWNLOAD)
        _downloadState.value = DownloadState.Idle
    }

    /**
     * Delete a downloaded pack and clean up its files.
     */
    suspend fun deletePack(packId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (packId == "core") {
                // Delete DB + all thumbnails
                context.getDatabasePath(GifDatabase.DATABASE_NAME).delete()
                assetManager.clearAll()
                GifDatabase.release()
            } else {
                File(packsDir, packId).deleteRecursively()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get total storage used by GIF data.
     */
    fun getTotalStorageUsed(): Long {
        val packsDirSize = packsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val assetsSize = assetManager.getTotalStorageUsed()
        val dbSize = context.getDatabasePath(GifDatabase.DATABASE_NAME).let {
            if (it.exists()) it.length() else 0L
        }
        return packsDirSize + assetsSize + dbSize
    }

    /**
     * Format bytes as human-readable string.
     */
    fun formatStorageSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    companion object {
        private const val TAG = "GifPackManager"
        const val WORK_TAG_GIF_DOWNLOAD = "gif_pack_download"
        const val WORK_NAME_CORE_PACK = "gif_core_pack"

        @Volatile
        private var instance: GifPackManager? = null

        fun getInstance(context: Context): GifPackManager {
            return instance ?: synchronized(this) {
                instance ?: GifPackManager(context.applicationContext).also { instance = it }
            }
        }

        fun release() {
            synchronized(this) { instance = null }
        }
    }
}

/**
 * WorkManager Worker for downloading and installing GIF packs.
 *
 * Handles: HTTP download → extract → install DB + thumbnails.
 * Reports progress via setProgress() for UI observation.
 */
class GifPackDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val packId = inputData.getString(KEY_PACK_ID) ?: return Result.failure()

        setProgress(workDataOf(KEY_PROGRESS to 0f, KEY_STAGE to "downloading"))

        return try {
            when (packId) {
                "core" -> downloadCorePack()
                else -> downloadCategoryPack(packId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pack download failed: $packId", e)
            Result.retry() // WorkManager will retry with backoff
        }
    }

    /**
     * Download the core pack: gzipped database + thumbnails archive.
     */
    private suspend fun downloadCorePack(): Result {
        val packsDir = File(applicationContext.filesDir, "gif_packs").also { it.mkdirs() }

        // Step 1: Download gzipped database from GitHub Releases
        setProgress(workDataOf(KEY_PROGRESS to 0.1f, KEY_STAGE to "downloading database"))
        val dbGzFile = File(packsDir, "${GifDatabase.DATABASE_NAME}.gz")

        downloadHttpFile(CORE_DB_URL, dbGzFile) { progress ->
            setProgress(workDataOf(KEY_PROGRESS to progress * 0.4f, KEY_STAGE to "downloading database"))
        }

        // Step 2: Decompress database
        setProgress(workDataOf(KEY_PROGRESS to 0.4f, KEY_STAGE to "installing database"))
        if (dbGzFile.exists()) {
            val dbFile = applicationContext.getDatabasePath(GifDatabase.DATABASE_NAME)
            dbFile.parentFile?.mkdirs()
            GZIPInputStream(dbGzFile.inputStream().buffered()).use { gzIn ->
                FileOutputStream(dbFile).use { out ->
                    gzIn.copyTo(out)
                }
            }
            dbGzFile.delete()
        }

        // Step 3: Download thumbnails archive
        setProgress(workDataOf(KEY_PROGRESS to 0.5f, KEY_STAGE to "downloading thumbnails"))
        // TODO: Download thumbnails zip from GitHub Releases
        // val thumbsZip = File(packsDir, "thumbs.zip")
        // downloadHttpFile(THUMBS_DOWNLOAD_URL, thumbsZip) { progress ->
        //     setProgress(workDataOf(KEY_PROGRESS to 0.5f + progress * 0.4f, KEY_STAGE to "downloading thumbnails"))
        // }

        // Step 4: Extract thumbnails
        setProgress(workDataOf(KEY_PROGRESS to 0.9f, KEY_STAGE to "installing thumbnails"))
        val thumbsDir = File(applicationContext.filesDir, Gif.THUMBS_DIR).also { it.mkdirs() }
        // TODO: Extract thumbs zip into thumbsDir

        // Write manifest to mark core as installed
        val manifest = File(packsDir, "core/manifest.json")
        manifest.parentFile?.mkdirs()
        manifest.writeText("""{"pack_id":"core","version":1,"installed_at":${System.currentTimeMillis()}}""")

        setProgress(workDataOf(KEY_PROGRESS to 1f, KEY_STAGE to "complete"))
        return Result.success()
    }

    /**
     * Download a category-specific animation pack.
     */
    private suspend fun downloadCategoryPack(packId: String): Result {
        val packsDir = File(applicationContext.filesDir, "gif_packs")
        val packDir = File(packsDir, packId).also { it.mkdirs() }

        // TODO: Download category pack ZIP from GitHub Releases
        // val packZip = File(packsDir, "$packId.zip")
        // downloadHttpFile(packUrl, packZip) { ... }

        // TODO: Extract full animations to gifs/full/ directory
        // extractZip(packZip, tempDir) { ... }
        // Move *.webp from tempDir/full/ to gifs/full/
        // Update is_available in database for this pack's GIFs

        // Write manifest
        val manifest = File(packDir, "manifest.json")
        manifest.writeText("""{"pack_id":"$packId","version":1,"installed_at":${System.currentTimeMillis()}}""")

        return Result.success()
    }

    /**
     * Download a file via HTTP with progress reporting.
     */
    private suspend fun downloadHttpFile(
        url: String,
        destFile: File,
        progressCallback: suspend (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        destFile.parentFile?.mkdirs()

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.connect()

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw java.io.IOException("HTTP ${connection.responseCode}: ${connection.responseMessage}")
        }

        val totalSize = connection.contentLengthLong
        var downloaded = 0L
        val buffer = ByteArray(8192)

        connection.inputStream.buffered().use { input ->
            FileOutputStream(destFile).use { output ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    if (totalSize > 0) {
                        progressCallback(downloaded.toFloat() / totalSize)
                    }
                }
            }
        }

        progressCallback(1f)
    }

    /**
     * Extract ZIP file contents to a directory.
     */
    @Suppress("unused") // Will be used when pack downloads are enabled
    private suspend fun extractZip(
        zipFile: File,
        destDir: File,
        progressCallback: suspend (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        destDir.mkdirs()

        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            var count = 0
            val totalEstimate = 1000

            while (entry != null) {
                // Path traversal protection
                val destFile = File(destDir, entry.name).canonicalFile
                if (!destFile.path.startsWith(destDir.canonicalPath)) {
                    throw SecurityException("Zip entry outside target: ${entry.name}")
                }

                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }

                count++
                progressCallback(minOf(count.toFloat() / totalEstimate, 1f))

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        progressCallback(1f)
    }

    companion object {
        const val TAG = "GifPackWorker"
        const val KEY_PACK_ID = "pack_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_STAGE = "stage"

        // GitHub Releases asset URL for core database
        private const val CORE_DB_URL =
            "https://github.com/tribixbite/CleverKeys/releases/download/CleverKeys-GIF/gifs_v2.db.gz"
        // TODO: Add thumbnails archive URL when thumbnail pack is published
    }
}

/**
 * Wrapper to observe WorkManager LiveData as a simple callback.
 * Avoids pulling in lifecycle-livedata-ktx dependency.
 */
class LiveDataFlow(
    val liveData: androidx.lifecycle.LiveData<List<WorkInfo>>
)

/**
 * Information about a GIF pack.
 */
data class PackInfo(
    val id: String,
    val name: String,
    val description: String,
    val gifCount: Int,
    val sizeBytes: Long,
    val downloadUrl: String?,
    val isBundled: Boolean
) {
    val sizeFormatted: String
        get() = when {
            sizeBytes >= 1_000_000_000 -> "%.1f GB".format(sizeBytes / 1_000_000_000.0)
            sizeBytes >= 1_000_000 -> "%.0f MB".format(sizeBytes / 1_000_000.0)
            else -> "%.0f KB".format(sizeBytes / 1_000.0)
        }
}

/**
 * Pack installation status.
 */
sealed class PackStatus {
    object NotInstalled : PackStatus()
    data class Installed(val gifCount: Int) : PackStatus()
    object Unknown : PackStatus()
}

/**
 * Download state for UI observation.
 */
sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val packId: String, val progress: Float) : DownloadState()
    data class Installing(val packId: String) : DownloadState()
    data class Completed(val packId: String) : DownloadState()
    data class Error(val packId: String, val message: String) : DownloadState()
}
