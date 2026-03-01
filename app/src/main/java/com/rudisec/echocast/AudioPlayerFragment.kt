package com.rudisec.echocast

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.rudisec.echocast.databinding.DialogInputModernBinding
import com.rudisec.echocast.databinding.FragmentAudioPlayerBinding
import java.io.File
import java.io.FileOutputStream

class AudioPlayerFragment : Fragment() {
    private var _binding: FragmentAudioPlayerBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var prefs: Preferences
    private lateinit var playlist: AudioPlaylist
    private lateinit var adapter: AudioAdapter

    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            android.util.Log.d("EchoCast", "Audio picked: $uri")
            // Get the original display name from the content resolver
            val originalName = getDisplayName(uri) ?: "audio.mp3"
            // Copy file locally so the URI persists
            val localFile = copyUriToLocal(uri, originalName)
            if (localFile != null) {
                val localUri = Uri.fromFile(localFile)
                // Use the original file name (without extension) as the audio name
                val displayName = originalName.substringBeforeLast(".")
                val audioItem = AudioItem.create(localUri, displayName)
                playlist.addItem(audioItem)
                refreshAdapter()
                Snackbar.make(binding.root, R.string.audio_added, Snackbar.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Error copying audio file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getDisplayName(uri: Uri): String? {
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return it.getString(nameIndex)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast("/")?.substringAfterLast(":")
    }

    private fun copyUriToLocal(uri: Uri, originalName: String): File? {
        return try {
            val inputStream = requireContext().contentResolver.openInputStream(uri) ?: return null
            // Keep the original name but add timestamp to avoid collisions
            val extension = originalName.substringAfterLast(".", "mp3")
            val baseName = originalName.substringBeforeLast(".")
            val fileName = "${baseName}_${System.currentTimeMillis()}.${extension}"
            val dir = requireContext().getExternalFilesDir(null)
            if (dir == null) {
                android.util.Log.e("EchoCast", "getExternalFilesDir returned null")
                return null
            }
            val file = File(dir, fileName)
            FileOutputStream(file).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            file
        } catch (e: Exception) {
            android.util.Log.e("EchoCast", "Error copying audio file: ${e.message}", e)
            null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAudioPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    private val playbackStoppedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (::adapter.isInitialized) {
                adapter.resetPlayingState()
            }
        }
    }
    
    private val audioAddedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            android.util.Log.d("EchoCast", "AUDIO_ADDED broadcast received")
            refreshAdapter()
        }
    }
    
    private val shuffleFailedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val reason = intent?.getStringExtra("reason") ?: return
            val msg = when (reason) {
                "no_call" -> getString(R.string.shuffle_no_call)
                "no_audios" -> getString(R.string.shuffle_no_audios)
                else -> return
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        prefs = Preferences(requireContext())
        playlist = AudioPlaylist(requireContext())
        
        // Register broadcast receivers with proper flags for Android 13+
        val stoppedFilter = android.content.IntentFilter("com.rudisec.echocast.AUDIO_STOPPED")
        val addedFilter = android.content.IntentFilter("com.rudisec.echocast.AUDIO_ADDED")
        val shuffleFailedFilter = android.content.IntentFilter("com.rudisec.echocast.SHUFFLE_FAILED")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(playbackStoppedReceiver, stoppedFilter, Context.RECEIVER_EXPORTED)
            requireContext().registerReceiver(audioAddedReceiver, addedFilter, Context.RECEIVER_EXPORTED)
            requireContext().registerReceiver(shuffleFailedReceiver, shuffleFailedFilter, Context.RECEIVER_EXPORTED)
        } else {
            requireContext().registerReceiver(playbackStoppedReceiver, stoppedFilter)
            requireContext().registerReceiver(audioAddedReceiver, addedFilter)
            requireContext().registerReceiver(shuffleFailedReceiver, shuffleFailedFilter)
        }
        
        setupRecyclerView()
        setupListeners()
        loadState()
    }
    
    override fun onResume() {
        super.onResume()
        // Always refresh when fragment becomes visible (e.g. tab switch)
        if (::playlist.isInitialized && ::adapter.isInitialized) {
            refreshAdapter()
        }
    }

    /**
     * Reload playlist from storage and update the adapter.
     */
    private fun refreshAdapter() {
        playlist.reload()
        val items = playlist.getItems()
        android.util.Log.d("EchoCast", "refreshAdapter: ${items.size} items in playlist")
        adapter.setItems(items)
        updateEmptyState()
    }

    private fun setupRecyclerView() {
        adapter = AudioAdapter(
            onItemRemove = { item ->
                playlist.removeItem(item.id)
                refreshAdapter()
                Snackbar.make(binding.root, R.string.audio_removed, Snackbar.LENGTH_SHORT).show()
            },
            onItemPlay = { item ->
                playAudioItem(item)
            },
            onPlaybackStopped = {
                adapter.notifyDataSetChanged()
            },
            onSelectionChanged = { selectedIds ->
                updateAddAudioButton(selectedIds.isNotEmpty())
            }
        )
        
        binding.recyclerViewPlaylist.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewPlaylist.adapter = adapter
        refreshAdapter()
    }

    private fun setupListeners() {
        binding.switchEnable.setOnCheckedChangeListener { _, isChecked ->
            prefs.isEnabled = isChecked
        }

        binding.btnModeSingle.setOnClickListener {
            prefs.playMode = "single"
            updatePlayModeButtons()
            updateShuffleControls()
        }

        binding.btnModeLoop.setOnClickListener {
            prefs.playMode = "loop"
            updatePlayModeButtons()
            updateShuffleControls()
        }

        binding.btnModeShuffle.setOnClickListener {
            prefs.playMode = "shuffle"
            updatePlayModeButtons()
            updateShuffleControls()
        }
        
        binding.btnShufflePlayPause.setOnClickListener {
            if (!prefs.isEnabled) {
                Toast.makeText(requireContext(), "Enable playback first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (prefs.isShufflePaused) {
                // Play / Resume
                prefs.isShufflePaused = false
                updateShuffleControls()
                val intent = android.content.Intent(requireContext(), EchoCastInCallService::class.java).apply {
                    action = "START_SHUFFLE"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    requireContext().startForegroundService(intent)
                } else {
                    requireContext().startService(intent)
                }
            } else {
                // Pause
                prefs.isShufflePaused = true
                updateShuffleControls()
                val intent = android.content.Intent(requireContext(), EchoCastInCallService::class.java).apply {
                    action = "PAUSE_SHUFFLE"
                }
                requireContext().startService(intent)
            }
        }

        binding.checkboxShuffleLoop.setOnCheckedChangeListener { _, isChecked ->
            prefs.isShuffleLoop = isChecked
        }

        binding.btnDelete.setOnClickListener {
            val selectedIds = adapter.getSelectedItems()
            if (selectedIds.isNotEmpty()) {
                selectedIds.forEach { id ->
                    playlist.removeItem(id)
                }
                adapter.clearSelection()
                refreshAdapter()
                Snackbar.make(binding.root, R.string.audios_deleted, Snackbar.LENGTH_SHORT).show()
            }
        }

        binding.btnEditName.setOnClickListener {
            val selectedIds = adapter.getSelectedItems()
            if (selectedIds.size != 1) {
                Toast.makeText(requireContext(), R.string.select_one_to_edit, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val item = playlist.getItems().find { it.id == selectedIds.first() } ?: return@setOnClickListener
            showEditNameDialog(item)
        }

        // Initial setup: Add Audio button
        updateAddAudioButton(false)
    }

    private fun loadState() {
        binding.switchEnable.isChecked = prefs.isEnabled
        updatePlayModeButtons()
        updateShuffleControls()
    }

    private fun playAudioItem(item: AudioItem) {
        if (!prefs.isEnabled) {
            Toast.makeText(requireContext(), "Enable playback first", Toast.LENGTH_SHORT).show()
            return
        }
        
        val intent = android.content.Intent(requireContext(), EchoCastInCallService::class.java).apply {
            action = "PLAY_AUDIO"
            putExtra("audio_id", item.id)
            putExtra("audio_uri", item.uri.toString())
            putExtra("play_mode", prefs.playMode)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
    }

    private fun updatePlayModeButtons() {
        val activeColor = ContextCompat.getColor(requireContext(), R.color.primary)
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.surface)
        val activeTextColor = ContextCompat.getColor(requireContext(), R.color.on_primary)
        val inactiveTextColor = ContextCompat.getColor(requireContext(), R.color.on_surface)

        binding.btnModeSingle.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (prefs.playMode == "single") activeColor else inactiveColor
            )
            setTextColor(if (prefs.playMode == "single") activeTextColor else inactiveTextColor)
        }
        binding.btnModeLoop.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (prefs.playMode == "loop") activeColor else inactiveColor
            )
            setTextColor(if (prefs.playMode == "loop") activeTextColor else inactiveTextColor)
        }
        binding.btnModeShuffle.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (prefs.playMode == "shuffle") activeColor else inactiveColor
            )
            setTextColor(if (prefs.playMode == "shuffle") activeTextColor else inactiveTextColor)
        }
    }
    
    private fun updateShuffleControls() {
        if (prefs.playMode == "shuffle") {
            binding.layoutShuffleControls.visibility = View.VISIBLE
            if (prefs.isShufflePaused) {
                binding.btnShufflePlayPause.text = getString(R.string.play_resume)
                binding.btnShufflePlayPause.setIconResource(android.R.drawable.ic_media_play)
            } else {
                binding.btnShufflePlayPause.text = getString(R.string.pause_shuffle)
                binding.btnShufflePlayPause.setIconResource(android.R.drawable.ic_media_pause)
            }
            binding.checkboxShuffleLoop.isChecked = prefs.isShuffleLoop
        } else {
            binding.layoutShuffleControls.visibility = View.GONE
        }
    }

    private fun updateEmptyState() {
        val isEmpty = playlist.getItems().isEmpty()
        binding.tvEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        // RecyclerView stays VISIBLE always - it just shows 0 items when empty
    }
    
    private fun updateAddAudioButton(hasSelection: Boolean) {
        if (hasSelection) {
            binding.fabAddAudio.visibility = android.view.View.GONE
            binding.layoutSelectionActions.visibility = android.view.View.VISIBLE
        } else {
            binding.fabAddAudio.visibility = android.view.View.VISIBLE
            binding.layoutSelectionActions.visibility = android.view.View.GONE
            binding.fabAddAudio.text = getString(R.string.add_audio)
            binding.fabAddAudio.setIconResource(android.R.drawable.ic_input_add)
            binding.fabAddAudio.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.primary)
            )
            binding.fabAddAudio.setTextColor(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.on_primary)
            ))
            binding.fabAddAudio.iconTint = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.on_primary)
            )
            binding.fabAddAudio.setOnClickListener {
                audioPickerLauncher.launch("audio/*")
            }
        }
    }

    private fun showEditNameDialog(item: AudioItem) {
        val dialogBinding = DialogInputModernBinding.inflate(layoutInflater)
        dialogBinding.dialogTitle.text = getString(R.string.edit_audio_name)
        dialogBinding.dialogInputLayout.hint = getString(R.string.edit_audio_name)
        dialogBinding.dialogInput.setText(item.name)
        dialogBinding.dialogInput.setSelection(0, item.name.length)
        dialogBinding.dialogInput.inputType = android.text.InputType.TYPE_CLASS_TEXT

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.Theme_EchoCast_AlertDialog)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialogBinding.dialogPositive.setOnClickListener {
            val newName = dialogBinding.dialogInput.text.toString().trim()
            if (newName.isNotEmpty()) {
                val updatedItem = item.copy(name = newName)
                playlist.updateItem(updatedItem)
                adapter.clearSelection()
                refreshAdapter()
                Snackbar.make(binding.root, R.string.name_updated, Snackbar.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        dialogBinding.dialogNegative.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            requireContext().unregisterReceiver(playbackStoppedReceiver)
            requireContext().unregisterReceiver(audioAddedReceiver)
            requireContext().unregisterReceiver(shuffleFailedReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
        _binding = null
    }
}
