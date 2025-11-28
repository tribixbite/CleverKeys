package tribixbite.keyboard2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * Base adapter for word lists with filtering
 * PERFORMANCE: Uses simple list updates instead of AsyncListDiffer
 * Reason: AsyncListDiffer too slow for large datasets (50k words = 19 second delay)
 * Trade-off: No animations, but instant updates (speed > animations for dictionary)
 */
abstract class BaseWordAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    protected var currentList: List<DictionaryWord> = emptyList()

    /**
     * Update word list - instant update without diff calculation
     * PERFORMANCE: No diff means no delay, critical for 50k word searches
     */
    fun setWords(words: List<DictionaryWord>) {
        currentList = words
        notifyDataSetChanged()
    }

    override fun getItemCount() = currentList.size

    open fun getFilteredCount() = currentList.size
}

/**
 * Adapter for words with toggle (Active/Disabled/User tabs)
 */
class WordToggleAdapter(
    private val onToggle: (DictionaryWord, Boolean) -> Unit
) : BaseWordAdapter() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_word_toggle, parent, false)
        return ToggleViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        // Use bindingAdapterPosition to get stable position (avoids stale position bugs)
        val adapterPosition = holder.bindingAdapterPosition
        if (adapterPosition != RecyclerView.NO_POSITION) {
            (holder as ToggleViewHolder).bind(currentList[adapterPosition], onToggle)
        }
    }

    class ToggleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val wordText: TextView = itemView.findViewById(R.id.word_text)
        private val frequencyText: TextView = itemView.findViewById(R.id.frequency_text)
        private val enableToggle: SwitchCompat = itemView.findViewById(R.id.enable_toggle)

        fun bind(word: DictionaryWord, onToggle: (DictionaryWord, Boolean) -> Unit) {
            wordText.text = word.word
            frequencyText.text = "Frequency: ${word.frequency}"

            // Remove previous listener to avoid triggering during bind
            enableToggle.setOnCheckedChangeListener(null)
            enableToggle.isChecked = word.enabled

            enableToggle.setOnCheckedChangeListener { _, isChecked ->
                onToggle(word, isChecked)
            }
        }
    }
}

/**
 * Adapter for editable custom words
 */
class WordEditableAdapter(
    private val onEdit: (DictionaryWord) -> Unit,
    private val onDelete: (DictionaryWord) -> Unit,
    private val onAdd: () -> Unit
) : BaseWordAdapter() {

    companion object {
        private const val VIEW_TYPE_ADD = 0
        private const val VIEW_TYPE_WORD = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == 0) VIEW_TYPE_ADD else VIEW_TYPE_WORD
    }

    override fun getItemCount() = currentList.size + 1  // +1 for add button

    override fun getFilteredCount() = currentList.size + 1  // Include + Add button

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_ADD) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_word_editable, parent, false)
            AddViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_word_editable, parent, false)
            EditableViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        // Use bindingAdapterPosition to get stable position (avoids stale position bugs)
        val adapterPosition = holder.bindingAdapterPosition
        if (adapterPosition == RecyclerView.NO_POSITION) return

        when (holder) {
            is AddViewHolder -> holder.bind(onAdd)
            is EditableViewHolder -> {
                // Position 0 is Add button, actual words start at position 1
                if (adapterPosition > 0 && adapterPosition - 1 < currentList.size) {
                    holder.bind(currentList[adapterPosition - 1], onEdit, onDelete)
                }
            }
        }
    }

    class AddViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val wordText: TextView = itemView.findViewById(R.id.word_text)
        private val frequencyText: TextView = itemView.findViewById(R.id.frequency_text)
        private val editButton: Button = itemView.findViewById(R.id.edit_button)
        private val deleteButton: Button = itemView.findViewById(R.id.delete_button)

        fun bind(onAdd: () -> Unit) {
            wordText.text = "+ Add New Word"
            frequencyText.text = "Tap to add a custom word"
            editButton.visibility = View.GONE
            deleteButton.visibility = View.GONE

            itemView.setOnClickListener { onAdd() }
        }
    }

    class EditableViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val wordText: TextView = itemView.findViewById(R.id.word_text)
        private val frequencyText: TextView = itemView.findViewById(R.id.frequency_text)
        private val editButton: Button = itemView.findViewById(R.id.edit_button)
        private val deleteButton: Button = itemView.findViewById(R.id.delete_button)

        fun bind(
            word: DictionaryWord,
            onEdit: (DictionaryWord) -> Unit,
            onDelete: (DictionaryWord) -> Unit
        ) {
            wordText.text = word.word
            frequencyText.text = "Frequency: ${word.frequency}"
            editButton.visibility = View.VISIBLE
            deleteButton.visibility = View.VISIBLE

            editButton.setOnClickListener { onEdit(word) }
            deleteButton.setOnClickListener { onDelete(word) }
            itemView.setOnClickListener(null)
        }
    }
}
