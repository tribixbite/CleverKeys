package tribixbite.cleverkeys.gif

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Scale
import kotlinx.coroutines.*
import java.io.File

/**
 * Manages a RecyclerView-based GIF grid with Coil image loading.
 *
 * Replaces the legacy GridView+BaseAdapter approach with RecyclerView+ViewHolder
 * for better view recycling in the memory-constrained IME context.
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

    private val assetManager = GifAssetManager.getInstance(context)
    private val database = GifDatabase.getInstance(context)
    private val adapter = GifRecyclerAdapter()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Coil image loader with IME-friendly memory limits */
    private val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
        .diskCachePolicy(coil.request.CachePolicy.DISABLED) // Files already on disk
        .crossfade(false) // Minimize animation overhead in IME
        .build()

    /** Callback for GIF selection (tap). */
    var onGifSelected: ((Gif) -> Unit)? = null

    /** Callback for long-press preview. */
    var onGifLongPress: ((Gif, View) -> Unit)? = null

    init {
        recyclerView.layoutManager = GridLayoutManager(context, columns)
        recyclerView.adapter = adapter
        recyclerView.setHasFixedSize(true)
        recyclerView.itemAnimator = null // No animations for perf

        // Load recently used on init
        scope.launch { loadCategory(GifCategory.RECENTLY_USED) }
    }

    /**
     * Load GIFs for a specific category.
     */
    fun setCategory(category: GifCategory) {
        currentCategory = category
        scope.launch { loadCategory(category) }
    }

    /**
     * Search GIFs by query.
     */
    fun search(query: String) {
        scope.launch {
            gifList = if (query.isBlank()) {
                database.getRecentlyUsedGifs(50)
            } else {
                database.searchGifs(query, 100)
            }
            adapter.notifyDataSetChanged()
        }
    }

    /** Current result count. */
    fun getResultCount(): Int = gifList.size

    private suspend fun loadCategory(category: GifCategory) {
        gifList = database.getGifsByCategory(category, 200)
        withContext(Dispatchers.Main) {
            adapter.notifyDataSetChanged()
        }
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
        /** Thumbnail decode target size (px). Keeps Coil memory low. */
        private const val THUMB_SIZE_PX = 200

        private fun dpToPx(dp: Int): Int {
            return (dp * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
        }
    }
}
