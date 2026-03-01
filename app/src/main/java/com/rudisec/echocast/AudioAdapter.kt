package com.rudisec.echocast

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.rudisec.echocast.databinding.ItemAudioBinding

class AudioAdapter(
    private val onItemRemove: (AudioItem) -> Unit,
    private val onItemPlay: (AudioItem) -> Unit,
    private val onPlaybackStopped: () -> Unit = {},
    private val onSelectionChanged: (Set<String>) -> Unit = {}
) : RecyclerView.Adapter<AudioAdapter.ViewHolder>() {
    
    private val items = mutableListOf<AudioItem>()
    private var currentlyPlayingId: String? = null
    private val selectedItems = mutableSetOf<String>()
    private var isSelectionMode = false
    
    fun setItems(newItems: List<AudioItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
    
    fun getItemCount2(): Int = items.size
    
    fun resetPlayingState() {
        currentlyPlayingId = null
        notifyDataSetChanged()
    }
    
    fun clearSelection() {
        selectedItems.clear()
        isSelectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(selectedItems)
    }
    
    fun getSelectedItems(): Set<String> = selectedItems.toSet()

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAudioBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ViewHolder(
        private val binding: ItemAudioBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AudioItem) {
            // Use the stored name directly (set when audio was added)
            binding.tvAudioName.text = item.name
            binding.tvAudioUri.visibility = android.view.View.GONE

            val isSelected = selectedItems.contains(item.id)
            val isPlaying = currentlyPlayingId == item.id
            
            // Update selection visual state
            binding.root.alpha = if (isSelected) 0.7f else 1.0f
            val backgroundColor = if (isSelected) {
                android.graphics.Color.parseColor("#E0E0E0") // Light gray for selection
            } else {
                binding.root.context.getColor(R.color.surface)
            }
            binding.root.setCardBackgroundColor(backgroundColor)

            // Set icon based on playing state
            if (isPlaying) {
                binding.btnPlayPause.setIconResource(android.R.drawable.ic_media_pause)
            } else {
                binding.btnPlayPause.setIconResource(android.R.drawable.ic_media_play)
            }

            // Long press to enter selection mode
            binding.root.setOnLongClickListener {
                if (!isSelectionMode) {
                    isSelectionMode = true
                    selectedItems.add(item.id)
                    notifyDataSetChanged()
                    onSelectionChanged(selectedItems)
                }
                true
            }

            // Click to toggle selection or play
            binding.root.setOnClickListener {
                if (isSelectionMode) {
                    if (isSelected) {
                        selectedItems.remove(item.id)
                        if (selectedItems.isEmpty()) {
                            isSelectionMode = false
                        }
                    } else {
                        selectedItems.add(item.id)
                    }
                    notifyDataSetChanged()
                    onSelectionChanged(selectedItems)
                } else {
                    binding.btnPlayPause.performClick()
                }
            }

            binding.btnPlayPause.setOnClickListener {
                if (isSelectionMode) return@setOnClickListener
                
                if (currentlyPlayingId == item.id) {
                    currentlyPlayingId = null
                    binding.btnPlayPause.setIconResource(android.R.drawable.ic_media_play)
                    val stopIntent = android.content.Intent(binding.root.context, EchoCastInCallService::class.java).apply {
                        action = "STOP_AUDIO"
                        putExtra("audio_id", item.id)
                    }
                    binding.root.context.startService(stopIntent)
                } else {
                    currentlyPlayingId = item.id
                    notifyDataSetChanged()
                    onItemPlay(item)
                }
            }
        }
    }
}
