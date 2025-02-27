package com.rudisec.echocast

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class SoundboardAdapter(
    private val sounds: List<SoundboardSound>,
    private val onPlayClick: (SoundboardSound) -> Unit,
    private val onStopClick: (SoundboardSound) -> Unit,
    private val onDeleteClick: (SoundboardSound) -> Unit,
    private val isPlaying: (String) -> Boolean
) : RecyclerView.Adapter<SoundboardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.sound_card)
        val name: TextView = view.findViewById(R.id.sound_name)
        val duration: TextView = view.findViewById(R.id.sound_duration)
        val playButton: ImageButton = view.findViewById(R.id.sound_play)
        val deleteButton: ImageButton = view.findViewById(R.id.sound_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.soundboard_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sound = sounds[position]
        val context = holder.itemView.context
        
        holder.name.text = sound.name

        // Obtener y mostrar la duración
        val duration = sound.getDuration(context)
        holder.duration.text = duration
        
        if (isPlaying(sound.id)) {
            holder.playButton.setImageResource(R.drawable.ic_pause)
            holder.playButton.contentDescription = holder.itemView.context.getString(R.string.pref_soundboard_sound_stop)
            holder.playButton.setOnClickListener { onStopClick(sound) }
        } else {
            holder.playButton.setImageResource(R.drawable.ic_play)
            holder.playButton.contentDescription = holder.itemView.context.getString(R.string.pref_soundboard_sound_play)
            holder.playButton.setOnClickListener { onPlayClick(sound) }
        }
        
        holder.deleteButton.setOnClickListener { onDeleteClick(sound) }
    }

    override fun getItemCount() = sounds.size
} 
