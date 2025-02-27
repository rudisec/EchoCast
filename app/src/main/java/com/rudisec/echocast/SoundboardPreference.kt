package com.rudisec.echocast

import android.app.AlertDialog
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SoundboardPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : Preference(context, attrs, defStyleAttr) {

    private var recyclerView: RecyclerView? = null
    private var emptyView: TextView? = null
    private var addButton: Button? = null
    private var adapter: SoundboardAdapter? = null
    private var soundboardPlayer: SoundboardPlayer? = null
    private var prefs: Preferences? = null
    private var onAddClickListener: (() -> Unit)? = null

    init {
        layoutResource = R.layout.soundboard_layout
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val view = holder.itemView
        recyclerView = view.findViewById(R.id.sounds_recycler_view)
        emptyView = view.findViewById(R.id.empty_view)
        addButton = view.findViewById(R.id.add_sound_button)

        setupViews()
    }

    fun initialize(prefs: Preferences, soundboardPlayer: SoundboardPlayer, onAddClick: () -> Unit) {
        this.prefs = prefs
        this.soundboardPlayer = soundboardPlayer
        this.onAddClickListener = onAddClick
        setupViews()
    }

    private fun setupViews() {
        val prefs = this.prefs ?: return
        val soundboardPlayer = this.soundboardPlayer ?: return
        val onAddClick = this.onAddClickListener ?: return

        adapter = SoundboardAdapter(
            prefs.getSoundboardSounds(),
            { sound -> onPlaySound(sound) },
            { sound -> onStopSound(sound) },
            { sound -> onDeleteSound(sound) },
            { id -> soundboardPlayer.isPlaying(id) }
        )

        recyclerView?.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SoundboardPreference.adapter
        }

        addButton?.setOnClickListener { onAddClick() }

        updateVisibility()
    }

    private fun onPlaySound(sound: SoundboardSound) {
        if (!prefs?.isEnabled!!) {
            AlertDialog.Builder(context)
                .setMessage("Audio playback is disabled. Please enable it first using the 'Enabled' button")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val call = PlayerInCallService.getCurrentCall()
        if (call == null) {
            AlertDialog.Builder(context)
                .setMessage("No active call")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        
        soundboardPlayer?.play(sound, call)
        adapter?.notifyDataSetChanged()
    }

    private fun onStopSound(sound: SoundboardSound) {
        soundboardPlayer?.stop(sound.id)
        adapter?.notifyDataSetChanged()
    }

    private fun onDeleteSound(sound: SoundboardSound) {
        soundboardPlayer?.stop(sound.id)
        prefs?.removeSoundboardSound(sound.id)
        updateSoundboard()
    }

    fun updateSoundboard() {
        val prefs = this.prefs ?: return
        val soundboardPlayer = this.soundboardPlayer ?: return

        adapter = SoundboardAdapter(
            prefs.getSoundboardSounds(),
            { sound -> onPlaySound(sound) },
            { sound -> onStopSound(sound) },
            { sound -> onDeleteSound(sound) },
            { id -> soundboardPlayer.isPlaying(id) }
        )
        recyclerView?.adapter = adapter
        updateVisibility()
    }

    private fun updateVisibility() {
        val sounds = prefs?.getSoundboardSounds() ?: return
        
        if (sounds.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            recyclerView?.visibility = View.GONE
        } else {
            emptyView?.visibility = View.GONE
            recyclerView?.visibility = View.VISIBLE
        }
    }
} 
