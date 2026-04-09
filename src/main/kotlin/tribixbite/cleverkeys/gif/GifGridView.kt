package tribixbite.cleverkeys.gif

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Scale
import kotlinx.coroutines.*
import java.io.File

/**
 * Manages a RecyclerView-based GIF grid with Coil image loading and pagination.
 *
 * Replaces the legacy GridView+BaseAdapter approach with RecyclerView+ViewHolder
 * for better view recycling in the memory-constrained IME context.
 *
 * Pagination: 100 items per page (same as clipboard). When total items exceed
 * ITEMS_PER_PAGE, the onPaginationChanged callback fires with page info for
 * the UI to show prev/next controls.
 *
 * Coil handles:
 * - Async thumbnail loading from files
 * - Memory + disk caching with configurable limits
 * - Automatic cancellation on view recycling
 */
class GifGridManager(
    private val context: Context,
    private val recyclerView: RecyclerView,
    private val columns: Int = 3
) {
    private var gifList: List<Gif> = emptyList()
    private var currentCategory: GifCategory = GifCategory.RECENTLY_USED
    private var currentSearchQuery: String = ""
    private var currentPage: Int = 0
    private var totalItems: Int = 0

    private val assetManager = GifAssetManager.getInstance(context)
    private val database = GifDatabase.getInstance(context)
    private val adapter = GifRecyclerAdapter()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Coil image loader with IME-friendly memory limits.
     * Default Coil cache is 25% of available RAM (~200-300MB on modern phones).
     * Cap at 32MB — Coil caches uncompressed ARGB_8888 bitmaps (~160KB at 200px),
     * so 32MB holds ~200 images — enough for two 100-item pages. Prevents IME
     * from bloating to 500MB+. */
    private val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizeBytes(32 * 1024 * 1024) // 32 MB cap (vs ~250MB default)
                .build()
        }
        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
        .diskCachePolicy(coil.request.CachePolicy.DISABLED) // Files already on disk
        .crossfade(false) // Minimize animation overhead in IME
        .build()

    /** Callback for GIF selection (tap). */
    var onGifSelected: ((Gif) -> Unit)? = null

    /** Callback for long-press preview. */
    var onGifLongPress: ((Gif, View) -> Unit)? = null

    /**
     * Pagination state callback.
     * @param needsPagination true if total items exceed ITEMS_PER_PAGE
     * @param currentPage 1-indexed page number
     * @param totalPages total number of pages
     */
    var onPaginationChanged: ((needsPagination: Boolean, currentPage: Int, totalPages: Int) -> Unit)? = null

    init {
        recyclerView.layoutManager = GridLayoutManager(context, columns)
        recyclerView.adapter = adapter
        recyclerView.setHasFixedSize(true)
        recyclerView.itemAnimator = null // No animations for perf

        // Load recently used on init, fall back to "All" if empty
        scope.launch {
            loadCategory(GifCategory.RECENTLY_USED)
            if (gifList.isEmpty()) {
                // No usage history yet — show all available GIFs
                loadCategory(GifCategory.ALL)
            }
        }
    }

    /**
     * Load GIFs for a specific category.
     */
    fun setCategory(category: GifCategory) {
        currentCategory = category
        currentSearchQuery = ""
        currentPage = 0
        scope.launch { loadCategory(category) }
    }

    /**
     * Search GIFs by query.
     */
    fun search(query: String) {
        currentSearchQuery = query
        currentPage = 0
        scope.launch {
            if (query.isBlank()) {
                gifList = database.getRecentlyUsedGifs(50)
                totalItems = gifList.size
            } else {
                totalItems = database.countSearchResults(query)
                gifList = database.searchGifs(query, ITEMS_PER_PAGE, currentPage * ITEMS_PER_PAGE)
            }
            adapter.notifyDataSetChanged()
            recyclerView.scrollToPosition(0)
            notifyPagination()
        }
    }

    /** Current result count on this page. */
    fun getResultCount(): Int = gifList.size

    /** Navigate to next page. */
    fun nextPage() {
        if (!hasNextPage()) return
        currentPage++
        scope.launch { reloadCurrentView() }
    }

    /** Navigate to previous page. */
    fun previousPage() {
        if (currentPage <= 0) return
        currentPage--
        scope.launch { reloadCurrentView() }
    }

    fun hasNextPage(): Boolean = (currentPage + 1) * ITEMS_PER_PAGE < totalItems
    fun hasPreviousPage(): Boolean = currentPage > 0

    private suspend fun loadCategory(category: GifCategory) {
        totalItems = database.getCategoryCount(category)
        gifList = database.getGifsByCategory(category, ITEMS_PER_PAGE, currentPage * ITEMS_PER_PAGE)
        withContext(Dispatchers.Main) {
            adapter.notifyDataSetChanged()
            recyclerView.scrollToPosition(0)
            notifyPagination()
        }
    }

    /** Reload current view (category or search) at current page offset. */
    private suspend fun reloadCurrentView() {
        if (currentSearchQuery.isNotBlank()) {
            gifList = database.searchGifs(currentSearchQuery, ITEMS_PER_PAGE, currentPage * ITEMS_PER_PAGE)
        } else {
            gifList = database.getGifsByCategory(currentCategory, ITEMS_PER_PAGE, currentPage * ITEMS_PER_PAGE)
        }
        withContext(Dispatchers.Main) {
            adapter.notifyDataSetChanged()
            recyclerView.scrollToPosition(0)
            notifyPagination()
        }
    }

    private fun notifyPagination() {
        val totalPages = getTotalPages()
        onPaginationChanged?.invoke(
            totalItems > ITEMS_PER_PAGE,
            currentPage + 1,  // 1-indexed for display
            totalPages
        )
    }

    private fun getTotalPages(): Int {
        return if (totalItems <= 0) 1
        else (totalItems + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
    }

    /** Clean up resources. */
    fun destroy() {
        scope.cancel()
        imageLoader.shutdown()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  RecyclerView Adapter + ViewHolder
    // ─────────────────────────────────────────────────────────────────────────

    private inner class GifViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView as ImageView

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val gif = gifList.getOrNull(pos) ?: return@setOnClickListener
                scope.launch { database.recordGifUsage(gif.id) }
                onGifSelected?.invoke(gif)
            }
            itemView.setOnLongClickListener { v ->
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                val gif = gifList.getOrNull(pos) ?: return@setOnLongClickListener false
                onGifLongPress?.invoke(gif, v)
                true
            }
        }

        fun bind(gif: Gif) {
            val thumbFile = File(context.filesDir, gif.getThumbnailPath())

            if (thumbFile.exists()) {
                val request = ImageRequest.Builder(context)
                    .data(thumbFile)
                    .target(imageView)
                    .scale(Scale.FILL)
                    .size(THUMB_SIZE_PX)
                    .build()
                imageLoader.enqueue(request)
            } else {
                imageView.setImageDrawable(null)
            }
        }
    }

    private inner class GifRecyclerAdapter : RecyclerView.Adapter<GifViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GifViewHolder {
            val imageView = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(80)
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = true
                val pad = dpToPx(2)
                setPadding(pad, pad, pad, pad)
            }
            return GifViewHolder(imageView)
        }

        override fun onBindViewHolder(holder: GifViewHolder, position: Int) {
            gifList.getOrNull(position)?.let { holder.bind(it) }
        }

        override fun getItemCount(): Int = gifList.size

        override fun onViewRecycled(holder: GifViewHolder) {
            // Coil cancels requests automatically on target reuse,
            // but clear drawable to free memory immediately
            holder.imageView.setImageDrawable(null)
        }
    }

    companion object {
        /** Items per page — matches clipboard pagination (100 items). */
        const val ITEMS_PER_PAGE = 100

        /** Thumbnail decode target size (px). Keeps Coil memory low. */
        private const val THUMB_SIZE_PX = 200

        private fun dpToPx(dp: Int): Int {
            return (dp * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
        }
    }
}
