package com.rudisec.echocast

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rudisec.echocast.ai.*
import com.rudisec.echocast.databinding.DialogInputModernBinding
import com.rudisec.echocast.databinding.DialogSelectVoiceBinding
import com.rudisec.echocast.databinding.FragmentAiBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream

class AiFragment : Fragment() {
    private var _binding: FragmentAiBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var prefs: Preferences
    private lateinit var playlist: AudioPlaylist
    private var sampleUri: Uri? = null
    
    // MiniMax state
    private var minimaxVoicesCache: List<Pair<String, String>>? = null
    private var minimaxCloneAudioUri: Uri? = null
    
    // Collapsible state
    private var elevenLabsExpanded = true
    private var minimaxExpanded = false

    private val api: ElevenLabsApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.elevenlabs.io/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ElevenLabsApi::class.java)
    }

    private fun getMinimaxApi(): MiniMaxApi? {
        val key = prefs.minimaxApiKey
        return if (key != null && key.isNotBlank()) MiniMaxApi(key) else null
    }

    private val minimaxClonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val localFile = copyUriToLocal(uri, "minimax_clone_${System.currentTimeMillis()}.mp3")
            if (localFile != null) {
                minimaxCloneAudioUri = Uri.fromFile(localFile)
                binding.tvMinimaxSampleStatus.text = "Selected: ${uri.lastPathSegment?.take(30) ?: "audio"}"
                binding.btnMinimaxTrain.isEnabled = true
                showMinimaxCloneNameDialog()
            } else {
                binding.tvMinimaxSampleStatus.text = "Could not copy file"
            }
        }
    }

    private val samplePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val localFile = copyUriToLocal(uri, "voice_sample_${System.currentTimeMillis()}.mp3")
            if (localFile != null) {
                sampleUri = Uri.fromFile(localFile)
                binding.tvSampleStatus.text = uri.lastPathSegment ?: "Audio selected"
                binding.btnTrain.isEnabled = true
                calculateAndShowRecommendedDuration(sampleUri!!)
            } else {
                binding.tvSampleStatus.text = getString(R.string.no_file_selected)
                binding.btnTrain.isEnabled = false
            }
        } else {
            sampleUri = null
            binding.tvSampleStatus.text = getString(R.string.no_file_selected)
            binding.tvRecommendedDuration.visibility = View.GONE
            binding.btnTrain.isEnabled = false
        }
    }

    private fun copyUriToLocal(uri: Uri, fileName: String): File? {
        return try {
            val inputStream = requireContext().contentResolver.openInputStream(uri) ?: return null
            val file = File(requireContext().getExternalFilesDir(null), fileName)
            FileOutputStream(file).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            file
        } catch (e: Exception) {
            android.util.Log.e("EchoCast", "Error copying file: ${e.message}")
            null
        }
    }
    
    private fun calculateAndShowRecommendedDuration(uri: Uri) {
        lifecycleScope.launch {
            try {
                val durationMs = getAudioDuration(uri)
                if (durationMs > 0) {
                    val durationSeconds = durationMs / 1000
                    val recommendedText = when {
                        durationSeconds < 60 -> {
                            "Your audio: ${durationSeconds}s — Recommended: 1–5 minutes for optimal training"
                        }
                        durationSeconds < 300 -> {
                            "Your audio: ${durationSeconds}s — Great length for optimal training ✓"
                        }
                        else -> {
                            "Your audio: ${durationSeconds}s — Recommended: 1–5 min (your audio is longer)"
                        }
                    }
                    withContext(Dispatchers.Main) {
                        binding.tvRecommendedDuration.text = recommendedText
                        binding.tvRecommendedDuration.visibility = View.VISIBLE
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        binding.tvRecommendedDuration.text = "Recommended: 1–5 minutes of clear speech for optimal training"
                        binding.tvRecommendedDuration.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("EchoCast", "Error calculating duration: ${e.message}")
                withContext(Dispatchers.Main) {
                    binding.tvRecommendedDuration.text = "Recommended: 1–5 minutes of clear speech for optimal training"
                    binding.tvRecommendedDuration.visibility = View.VISIBLE
                }
            }
        }
    }
    
    private suspend fun getAudioDuration(uri: Uri): Long {
        return withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(requireContext(), uri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                retriever.release()
                duration?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                android.util.Log.e("EchoCast", "Error getting audio duration: ${e.message}")
                0L
            }
        }
    }
    
    private fun promptCreateVoice() {
        if (sampleUri == null) {
            Toast.makeText(requireContext(), "Please select an audio sample first", Toast.LENGTH_SHORT).show()
            return
        }
        showModernInputDialog(
            title = "Train Voice",
            hint = "Enter voice name",
            positiveText = "Train",
            onPositive = { voiceName ->
                if (voiceName.isNotBlank()) {
                    createVoiceFromSample(voiceName)
                } else {
                    Toast.makeText(requireContext(), "Voice name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
    
    private fun createVoiceFromSample(voiceName: String) {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            Toast.makeText(requireContext(), "Please enter your API Key first", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val voiceId = getOrCreateVoiceId(apiKey, voiceName)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    if (voiceId != null) {
                        Toast.makeText(requireContext(), "Voice '$voiceName' created successfully!", Toast.LENGTH_SHORT).show()
                        setupTextToAudio()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "Error creating voice: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        prefs = Preferences(requireContext())
        playlist = AudioPlaylist(requireContext())
        
        setupUI()
    }

    private fun setupUI() {
        updateElevenLabsApiKeyButton()
        binding.btnElevenLabsApiKey.setOnClickListener { showElevenLabsApiKeyDialog() }

        binding.btnUploadSample.setOnClickListener {
            samplePickerLauncher.launch("audio/*")
        }
        
        binding.btnTrain.setOnClickListener {
            promptCreateVoice()
        }
        
        binding.btnManageVoices.setOnClickListener {
            showManageVoicesDialog()
        }

        binding.btnGenerateAudio.setOnClickListener {
            generateAudio()
        }
        
        setupTextToAudio()
        
        // Collapsible ElevenLabs
        binding.headerElevenLabs.setOnClickListener { toggleElevenLabs() }
        
        // Collapsible MiniMax
        binding.headerMinimax.setOnClickListener { toggleMinimax() }
        
        setupMinimax()
        
        // Powered by links
        val elevenText = "Powered by ElevenLabs"
        val spannable = android.text.SpannableString(elevenText)
        val start = elevenText.indexOf("ElevenLabs")
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.primary)),
            start, start + "ElevenLabs".length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            android.text.style.UnderlineSpan(),
            start, start + "ElevenLabs".length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.tvPoweredByElevenLabs.text = spannable
        binding.tvPoweredByElevenLabs.setOnClickListener {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://elevenlabs.io")))
        }
        val minimaxText = "Powered by MiniMax"
        val minimaxSpannable = android.text.SpannableString(minimaxText)
        val minimaxStart = minimaxText.indexOf("MiniMax")
        minimaxSpannable.setSpan(
            android.text.style.ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.primary)),
            minimaxStart, minimaxStart + "MiniMax".length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        minimaxSpannable.setSpan(
            android.text.style.UnderlineSpan(),
            minimaxStart, minimaxStart + "MiniMax".length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.tvPoweredByMinimax.text = minimaxSpannable
        binding.tvPoweredByMinimax.setOnClickListener {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://minimax.io")))
        }
    }
    
    private fun toggleElevenLabs() {
        elevenLabsExpanded = !elevenLabsExpanded
        binding.contentElevenLabs.visibility = if (elevenLabsExpanded) View.VISIBLE else View.GONE
        binding.iconExpandElevenLabs.setImageResource(
            if (elevenLabsExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        )
    }
    
    private fun toggleMinimax() {
        minimaxExpanded = !minimaxExpanded
        binding.contentMinimax.visibility = if (minimaxExpanded) View.VISIBLE else View.GONE
        binding.iconExpandMinimax.setImageResource(
            if (minimaxExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        )
    }
    
    private fun updateElevenLabsApiKeyButton() {
        val apiKey = prefs.apiKey
        binding.btnElevenLabsApiKey.text = if (apiKey.isNotBlank()) "Change ElevenLabs API Key" else "Set ElevenLabs API Key"
        checkApiConnection(apiKey)
    }
    
    private fun showElevenLabsApiKeyDialog() {
        showModernInputDialog(
            title = "ElevenLabs API Key",
            hint = "Enter your API key",
            defaultText = prefs.apiKey,
            hintText = "Get your key from elevenlabs.io",
            positiveText = "Save",
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            onPositive = { key ->
                prefs.apiKey = key.trim()
                updateElevenLabsApiKeyButton()
                if (key.trim().isNotBlank()) {
                    Toast.makeText(requireContext(), "Validating...", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
    
    private fun checkApiConnection(apiKey: String) {
        if (apiKey.isBlank()) {
            binding.btnUploadSample.isEnabled = false
            binding.btnTrain.isEnabled = false
            binding.btnElevenLabsApiKey.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary))
            return
        }
        
        lifecycleScope.launch {
            try {
                api.getVoices(apiKey)
                withContext(Dispatchers.Main) {
                    binding.btnUploadSample.isEnabled = true
                    binding.btnTrain.isEnabled = sampleUri != null
                    binding.btnElevenLabsApiKey.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.success))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnUploadSample.isEnabled = false
                    binding.btnTrain.isEnabled = false
                    binding.btnElevenLabsApiKey.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary))
                }
            }
        }
    }
    
    private fun setupTextToAudio() {
        val selectedVoiceNameTts = prefs.selectedVoiceNameTts
        binding.tvSelectedVoiceTts.text = selectedVoiceNameTts ?: getString(R.string.no_voice_selected)
        
        binding.btnSelectVoiceTts.setOnClickListener {
            selectVoiceForTts()
        }
    }
    
    private fun selectVoiceForTts() {
        showVoicePickerDialog("Select Voice for Text to Audio") { selectedVoice ->
            prefs.selectedVoiceIdTts = selectedVoice.voice_id
            prefs.selectedVoiceNameTts = selectedVoice.name
            binding.tvSelectedVoiceTts.text = selectedVoice.name
        }
    }

    private fun showVoicePickerDialog(title: String, onVoiceSelected: (VoiceItem) -> Unit) {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            Toast.makeText(requireContext(), "Please set your API Key first", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val voicesResponse = api.getVoices(apiKey)
                val voices = voicesResponse.voices
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    
                    if (voices.isEmpty()) {
                        Toast.makeText(requireContext(), "No voices found. Create a voice in Voice Training first.", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    
                    showVoiceListDialog(title, voices.map { it.name }) { index ->
                        onVoiceSelected(voices[index])
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "Error loading voices: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showManageVoicesDialog() {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            Toast.makeText(requireContext(), "Please enter your API Key first", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val voicesResponse = api.getVoices(apiKey)
                val clonedVoices = voicesResponse.voices.filter { 
                    it.category == "cloned" || it.category == "generated"
                }
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    
                    if (clonedVoices.isEmpty()) {
                        Toast.makeText(requireContext(), "No custom voices found. Train a voice first.", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    
                    val voiceNames = clonedVoices.map { it.name }.toTypedArray()
                    MaterialAlertDialogBuilder(requireContext(), R.style.Theme_EchoCast_AlertDialog)
                        .setTitle("Delete Voices")
                        .setItems(voiceNames) { _, which ->
                            val voice = clonedVoices[which]
                            MaterialAlertDialogBuilder(requireContext(), R.style.Theme_EchoCast_AlertDialog)
                                .setTitle("Delete Voice")
                                .setMessage("Are you sure you want to delete \"${voice.name}\"? This cannot be undone.")
                                .setPositiveButton("Delete") { _, _ ->
                                    deleteVoice(voice)
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                        .setNegativeButton("Close", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "Error loading voices: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun deleteVoice(voice: VoiceItem) {
        val apiKey = prefs.apiKey
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val response = api.deleteVoice(apiKey, voice.voice_id)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    if (response.isSuccessful) {
                        Toast.makeText(requireContext(), "Voice \"${voice.name}\" deleted", Toast.LENGTH_SHORT).show()
                        if (prefs.selectedVoiceIdTts == voice.voice_id) {
                            prefs.selectedVoiceIdTts = null
                            prefs.selectedVoiceNameTts = null
                            binding.tvSelectedVoiceTts.text = getString(R.string.no_voice_selected)
                        }
                    } else {
                        Toast.makeText(requireContext(), "Error deleting voice: ${response.code()}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun generateAudio() {
        val apiKey = prefs.apiKey
        val text = binding.etTtsText.text.toString()
        val voiceId = prefs.selectedVoiceIdTts

        if (apiKey.isBlank()) {
            Toast.makeText(requireContext(), "Please enter your API Key first", Toast.LENGTH_SHORT).show()
            return
        }
        if (voiceId == null || voiceId.isBlank()) {
            Toast.makeText(requireContext(), "Please select a voice first", Toast.LENGTH_SHORT).show()
            return
        }
        if (text.isBlank()) {
            Toast.makeText(requireContext(), "Please enter text to generate", Toast.LENGTH_SHORT).show()
            return
        }

        val defaultName = text.take(30).trim()
        showModernInputDialog(
            title = "Name this audio",
            hint = "e.g. Greeting, Hello clip...",
            defaultText = defaultName,
            positiveText = "Generate",
            onPositive = { audioName ->
                doGenerateAudio(apiKey, voiceId, text, audioName.ifBlank { defaultName })
            }
        )
    }

    private fun doGenerateAudio(apiKey: String, voiceId: String, text: String, audioName: String) {
        binding.llProgressContainer.visibility = View.VISIBLE
        binding.progressBarTts.visibility = View.VISIBLE
        binding.tvProgressPercentage.text = "0%"
        binding.btnGenerateAudio.isEnabled = false

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) { binding.tvProgressPercentage.text = "30%" }
                withContext(Dispatchers.Main) { binding.tvProgressPercentage.text = "60%" }
                
                val response = api.textToSpeech(apiKey, voiceId, TtsRequest(text))
                
                withContext(Dispatchers.Main) { binding.tvProgressPercentage.text = "90%" }
                
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        val safeFileName = audioName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "").take(50)
                        val file = saveAudioToFile(body.byteStream().readBytes(), "${safeFileName}_${System.currentTimeMillis()}.mp3")
                        val audioItem = AudioItem.create(Uri.fromFile(file), audioName)
                        playlist.addItem(audioItem)
                        
                        withContext(Dispatchers.Main) {
                            binding.tvProgressPercentage.text = "100%"
                            requireContext().sendBroadcast(android.content.Intent("com.rudisec.echocast.AUDIO_ADDED"))
                            Toast.makeText(requireContext(), getString(R.string.audio_generated_and_added), Toast.LENGTH_LONG).show()
                            binding.etTtsText.setText("")
                        }
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    android.util.Log.e("EchoCast", "TTS Error ${response.code()}: $errorBody")
                    val errorMsg = when (response.code()) {
                        403 -> "Access denied (403). Check your API Key."
                        404 -> "Voice not found (404). Check that the selected voice exists."
                        else -> "TTS Error: ${response.code()}. ${errorBody ?: response.message()}"
                    }
                    showError(errorMsg)
                }
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    binding.llProgressContainer.visibility = View.GONE
                    binding.progressBar.visibility = View.GONE
                    binding.btnGenerateAudio.isEnabled = true
                }
            }
        }
    }

    private suspend fun getOrCreateVoiceId(apiKey: String, name: String): String? {
        val voicesResponse = api.getVoices(apiKey)
        val existingVoice = voicesResponse.voices.find { it.name.equals(name, ignoreCase = true) }
        
        if (existingVoice != null) return existingVoice.voice_id

        if (sampleUri != null) {
            val file = getFileFromUri(sampleUri!!)
            if (file != null) {
                val mimeType = when {
                    file.name.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
                    file.name.endsWith(".wav", ignoreCase = true) -> "audio/wav"
                    file.name.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
                    file.name.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
                    else -> "audio/mpeg"
                }
                
                val fileName = file.name.substringAfterLast("/").substringAfterLast("\\")
                val requestFile = file.asRequestBody(mimeType.toMediaTypeOrNull())
                
                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("name", name)
                    .addFormDataPart("files", fileName, requestFile)
                    .build()
                
                try {
                    val response = api.addVoice(apiKey, multipartBody)
                    if (response.isSuccessful) {
                        return response.body()?.voice_id
                    } else {
                        val errorBody = response.errorBody()?.string()
                        android.util.Log.e("EchoCast", "Error adding voice: ${response.code()} - $errorBody")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "Error ${response.code()}: ${errorBody ?: response.message()}", Toast.LENGTH_LONG).show()
                        }
                        return null
                    }
                } catch (e: Exception) {
                    android.util.Log.e("EchoCast", "Exception adding voice: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    return null
                }
            }
        }
        
        return null
    }

    private fun saveAudioToFile(data: ByteArray, fileName: String): File {
        val file = File(requireContext().getExternalFilesDir(null), fileName)
        FileOutputStream(file).use { it.write(data) }
        return file
    }

    private fun getFileFromUri(uri: Uri): File? {
        val inputStream = requireContext().contentResolver.openInputStream(uri) ?: return null
        val file = File(requireContext().cacheDir, "temp_sample.mp3")
        FileOutputStream(file).use { output ->
            inputStream.copyTo(output)
        }
        return file
    }

    private suspend fun showError(msg: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        }
    }

    // ==================== Modern Dialog ====================
    
    private fun showModernInputDialog(
        title: String,
        hint: String,
        defaultText: String = "",
        message: String? = null,
        hintText: String? = null,
        positiveText: String = "OK",
        inputType: Int = android.text.InputType.TYPE_CLASS_TEXT,
        onPositive: (String) -> Unit
    ) {
        val dialogBinding = DialogInputModernBinding.inflate(layoutInflater)
        dialogBinding.dialogTitle.text = title
        dialogBinding.dialogInputLayout.hint = hint
        dialogBinding.dialogInput.setText(defaultText)
        if (defaultText.isNotEmpty()) dialogBinding.dialogInput.setSelection(0, defaultText.length)
        dialogBinding.dialogInput.inputType = inputType
        
        if (message != null) {
            dialogBinding.dialogMessage.text = message
            dialogBinding.dialogMessage.visibility = View.VISIBLE
        }
        if (hintText != null) {
            dialogBinding.dialogHint.text = hintText
            dialogBinding.dialogHint.visibility = View.VISIBLE
        }
        
        dialogBinding.dialogPositive.text = positiveText
        
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.Theme_EchoCast_AlertDialog)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()
        
        dialogBinding.dialogPositive.setOnClickListener {
            onPositive(dialogBinding.dialogInput.text.toString().trim())
            dialog.dismiss()
        }
        dialogBinding.dialogNegative.setOnClickListener { dialog.dismiss() }
        
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
    }
    
    private fun showVoiceListDialog(title: String, voiceNames: List<String>, onVoiceSelected: (Int) -> Unit) {
        val dialogBinding = DialogSelectVoiceBinding.inflate(layoutInflater)
        dialogBinding.dialogTitle.text = title
        
        val container = dialogBinding.voiceListContainer
        for (name in voiceNames) {
            val textView = android.widget.TextView(requireContext()).apply {
                text = name
                setPadding(48, 32, 48, 32)
                textSize = 16f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            container.addView(textView)
        }
        
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.Theme_EchoCast_AlertDialog)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()
        
        dialogBinding.dialogCancel.setOnClickListener { dialog.dismiss() }
        
        for (i in 0 until container.childCount) {
            val index = i
            container.getChildAt(i).setOnClickListener {
                onVoiceSelected(index)
                dialog.dismiss()
            }
        }
        
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
    }

    // ==================== MiniMax ====================
    
    private fun setupMinimax() {
        updateMinimaxApiKeyButton()
        binding.btnMinimaxApiKey.setOnClickListener { showMinimaxApiKeyDialog() }
        
        binding.tvSelectedMinimaxVoice.text = prefs.minimaxSelectedVoiceName ?: getString(R.string.no_voice_selected)
        
        updateMinimaxButtons()
        
        binding.btnMinimaxSelectSample.setOnClickListener {
            val api = getMinimaxApi()
            if (api == null) {
                Toast.makeText(requireContext(), "Please enter MiniMax API key first", Toast.LENGTH_SHORT).show()
            } else {
                minimaxClonePickerLauncher.launch("audio/*")
            }
        }
        
        binding.btnMinimaxTrain.setOnClickListener {
            if (minimaxCloneAudioUri == null) {
                Toast.makeText(requireContext(), "Please select an audio sample first", Toast.LENGTH_SHORT).show()
            } else {
                showMinimaxCloneNameDialog()
            }
        }
        
        binding.btnMinimaxDeleteVoices.setOnClickListener {
            showManageMinimaxVoicesDialog()
        }
        
        binding.btnSelectMinimaxVoice.setOnClickListener {
            selectMinimaxVoice()
        }
        
        binding.btnGenerateMinimax.setOnClickListener {
            generateMinimaxAudio()
        }
    }
    
    private fun updateMinimaxButtons() {
        val api = getMinimaxApi()
        binding.btnMinimaxSelectSample.isEnabled = api != null
        binding.btnMinimaxTrain.isEnabled = api != null && minimaxCloneAudioUri != null
    }
    
    private fun updateMinimaxApiKeyButton() {
        val apiKey = prefs.minimaxApiKey
        binding.btnMinimaxApiKey.text = if (apiKey != null && apiKey.isNotBlank()) "Change MiniMax API Key" else "Set MiniMax API Key"
        if (apiKey != null && apiKey.isNotBlank()) {
            lifecycleScope.launch {
                try {
                    val api = MiniMaxApi(apiKey)
                    val result = withContext(Dispatchers.IO) { api.getVoices() }
                    withContext(Dispatchers.Main) {
                        binding.btnMinimaxApiKey.backgroundTintList = android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(requireContext(), if (result.isSuccess) R.color.success else R.color.primary)
                        )
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.btnMinimaxApiKey.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary))
                    }
                }
            }
        } else {
            binding.btnMinimaxApiKey.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary))
        }
    }
    
    private fun showMinimaxApiKeyDialog() {
        showModernInputDialog(
            title = "MiniMax API Key",
            hint = "Enter your API key",
            defaultText = prefs.minimaxApiKey ?: "",
            hintText = "Get your key from platform.minimax.io",
            positiveText = "Save",
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            onPositive = { key ->
                prefs.minimaxApiKey = if (key.trim().isNotBlank()) key.trim() else null
                minimaxVoicesCache = null
                updateMinimaxApiKeyButton()
                updateMinimaxButtons()
                if (key.trim().isNotBlank()) {
                    Toast.makeText(requireContext(), "Validating...", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
    
    private fun showMinimaxCloneNameDialog() {
        showModernInputDialog(
            title = "Name Your Cloned Voice",
            hint = "e.g. MiVoz, MyVoice",
            hintText = "Voice name: 8–256 chars, start with letter. Letters, digits, - and _ only.",
            positiveText = "Clone",
            onPositive = { voiceName ->
                val voiceId = voiceName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                if (voiceId.length < 8) {
                    Toast.makeText(requireContext(), "Name must be at least 8 characters", Toast.LENGTH_SHORT).show()
                    return@showModernInputDialog
                }
                if (!voiceId[0].isLetter()) {
                    Toast.makeText(requireContext(), "Name must start with a letter", Toast.LENGTH_SHORT).show()
                    return@showModernInputDialog
                }
                doCloneMinimaxVoice(voiceId)
            }
        )
        // If user cancels, reset
        minimaxCloneAudioUri?.let { _ ->
            // We can't easily detect cancel - the dialog doesn't have onCancel. 
            // User will need to select again if they cancel. That's ok.
        }
    }
    
    private fun doCloneMinimaxVoice(voiceId: String) {
        val api = getMinimaxApi() ?: return
        val uri = minimaxCloneAudioUri ?: return
        val file = getFileFromUri(uri) ?: run {
            Toast.makeText(requireContext(), "Could not read audio file", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) { binding.tvMinimaxSampleStatus.text = "Uploading..." }
                val uploadResult = withContext(Dispatchers.IO) { api.uploadVoiceCloneAudio(file) }
                
                if (uploadResult.isFailure) {
                    showError("Upload failed: ${uploadResult.exceptionOrNull()?.message}")
                    return@launch
                }
                
                val fileId = uploadResult.getOrNull()!!
                withContext(Dispatchers.Main) { binding.tvMinimaxSampleStatus.text = "Cloning voice..." }
                val cloneResult = withContext(Dispatchers.IO) { api.cloneVoice(fileId, voiceId) }
                
                if (cloneResult.isFailure) {
                    showError("Clone failed: ${cloneResult.exceptionOrNull()?.message}")
                    return@launch
                }
                
                val json = org.json.JSONObject(cloneResult.getOrNull() ?: "{}")
                val baseResp = json.optJSONObject("base_resp")
                if (baseResp?.optInt("status_code", -1) != 0) {
                    val msg = baseResp?.optString("status_msg", "Unknown error") ?: "Unknown error"
                    showError("Clone failed: $msg")
                    return@launch
                }
                
                val existing = prefs.minimaxClonedVoices?.split(",")?.filter { it.contains("|") }?.toMutableList() ?: mutableListOf()
                existing.add("$voiceId|$voiceId")
                prefs.minimaxClonedVoices = existing.joinToString(",")
                
                withContext(Dispatchers.Main) {
                    minimaxCloneAudioUri = null
                    minimaxVoicesCache = null
                    binding.tvMinimaxSampleStatus.text = getString(R.string.no_voice_selected)
                    binding.btnMinimaxTrain.isEnabled = false
                    prefs.minimaxSelectedVoiceId = voiceId
                    prefs.minimaxSelectedVoiceName = voiceId
                    binding.tvSelectedMinimaxVoice.text = voiceId
                    Toast.makeText(requireContext(), "Voice \"$voiceId\" cloned! You can use it now.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }
    
    private fun showManageMinimaxVoicesDialog() {
        val api = getMinimaxApi()
        if (api == null) {
            Toast.makeText(requireContext(), "Please enter MiniMax API key first", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val allVoices = minimaxVoicesCache ?: loadMinimaxVoices()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    if (allVoices == null) {
                        Toast.makeText(requireContext(), "Could not load voices. Check API key.", Toast.LENGTH_LONG).show()
                        return@withContext
                    }
                    minimaxVoicesCache = allVoices
                    val clonedOnly = allVoices.filter { it.second.endsWith("(cloned)") }
                    if (clonedOnly.isEmpty()) {
                        Toast.makeText(requireContext(), "No cloned voices found. Clone a voice first.", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    val names = clonedOnly.map { it.second }.toTypedArray()
                    MaterialAlertDialogBuilder(requireContext(), R.style.Theme_EchoCast_AlertDialog)
                        .setTitle("Delete Voices")
                        .setItems(names) { _, which ->
                            val voiceId = clonedOnly[which].first
                            MaterialAlertDialogBuilder(requireContext(), R.style.Theme_EchoCast_AlertDialog)
                                .setTitle("Delete Voice")
                                .setMessage("Are you sure you want to delete this cloned voice?")
                                .setPositiveButton("Delete") { _, _ ->
                                    val existing = prefs.minimaxClonedVoices?.split(",")?.filter { it.contains("|") }?.toMutableList() ?: mutableListOf()
                                    existing.removeAll { it.startsWith("$voiceId|") }
                                    prefs.minimaxClonedVoices = existing.joinToString(",")
                                    minimaxVoicesCache = null
                                    if (prefs.minimaxSelectedVoiceId == voiceId) {
                                        prefs.minimaxSelectedVoiceId = null
                                        prefs.minimaxSelectedVoiceName = null
                                        binding.tvSelectedMinimaxVoice.text = getString(R.string.no_voice_selected)
                                    }
                                    Toast.makeText(requireContext(), "Voice deleted", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                        .setNegativeButton("Close", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun selectMinimaxVoice() {
        val api = getMinimaxApi()
        if (api == null) {
            Toast.makeText(requireContext(), "Please enter MiniMax API key first", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val allVoices = minimaxVoicesCache ?: loadMinimaxVoices()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    if (allVoices == null) {
                        Toast.makeText(requireContext(), "Could not load voices. Check API key.", Toast.LENGTH_LONG).show()
                        return@withContext
                    }
                    minimaxVoicesCache = allVoices
                    val names = allVoices.map { it.second }
                    showVoiceListDialog("Select Voice (${allVoices.size} voices)", names) { which ->
                        prefs.minimaxSelectedVoiceId = allVoices[which].first
                        prefs.minimaxSelectedVoiceName = allVoices[which].second
                        binding.tvSelectedMinimaxVoice.text = allVoices[which].second
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private suspend fun loadMinimaxVoices(): List<Pair<String, String>>? {
        return withContext(Dispatchers.IO) {
            val api = getMinimaxApi() ?: return@withContext null
            try {
                val result = api.getVoices()
                if (result.isSuccess) {
                    val body = result.getOrNull() ?: return@withContext null
                    val json = org.json.JSONObject(body)
                    val baseResp = json.optJSONObject("base_resp")
                    if (baseResp?.optInt("status_code", -1) != 0) {
                        android.util.Log.e("EchoCast", "MiniMax getVoices: status_code != 0")
                        return@withContext null
                    }
                    val voices = mutableListOf<Pair<String, String>>()
                    prefs.minimaxClonedVoices?.split(",")?.forEach { part ->
                        val p = part.trim().split("|")
                        if (p.size >= 2 && p[0].isNotBlank()) {
                            voices.add(Pair(p[0], "${p[1]} (cloned)"))
                        }
                    }
                    for (key in listOf("system_voice", "voice_cloning", "voice_generation")) {
                        val arr = json.optJSONArray(key) ?: continue
                        for (i in 0 until arr.length()) {
                            val v = arr.getJSONObject(i)
                            val voiceId = v.optString("voice_id", "")
                            val voiceName = v.optString("voice_name", voiceId)
                            val displayName = if (voiceName.isNotBlank()) voiceName else voiceId
                            if (voiceId.isNotBlank()) {
                                voices.add(Pair(voiceId, displayName))
                            }
                        }
                    }
                    voices
                } else {
                    null
                }
            } catch (e: Exception) {
                android.util.Log.e("EchoCast", "MiniMax loadVoices: ${e.message}", e)
                null
            }
        }
    }
    
    private fun generateMinimaxAudio() {
        val api = getMinimaxApi()
        if (api == null) {
            Toast.makeText(requireContext(), "Please enter MiniMax API key first", Toast.LENGTH_SHORT).show()
            return
        }
        val voiceId = prefs.minimaxSelectedVoiceId
        val voiceName = prefs.minimaxSelectedVoiceName ?: "Voice"
        val text = binding.etMinimaxText.text.toString()
        
        if (voiceId == null) {
            Toast.makeText(requireContext(), "Please select a voice first", Toast.LENGTH_SHORT).show()
            return
        }
        if (text.isBlank()) {
            Toast.makeText(requireContext(), "Please enter text to generate", Toast.LENGTH_SHORT).show()
            return
        }
        
        val defaultName = "${voiceName.take(15)} - ${text.take(15).trim()}"
        showModernInputDialog(
            title = "Name this audio",
            hint = "e.g. My clip",
            defaultText = defaultName,
            positiveText = "Generate",
            onPositive = { audioName ->
                doGenerateMinimax(voiceId, text, audioName.ifBlank { defaultName })
            }
        )
    }
    
    private fun doGenerateMinimax(voiceId: String, text: String, audioName: String) {
        val api = getMinimaxApi() ?: return
        binding.llMinimaxProgress.visibility = View.VISIBLE
        binding.tvMinimaxProgress.text = "Generating..."
        binding.btnGenerateMinimax.isEnabled = false
        
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) { binding.tvMinimaxProgress.text = "Requesting TTS..." }
                val result = withContext(Dispatchers.IO) { api.textToSpeech(text, voiceId) }
                
                if (result.isFailure) {
                    showError("MiniMax error: ${result.exceptionOrNull()?.message}")
                    return@launch
                }
                
                val responseBody = result.getOrNull() ?: ""
                val json = org.json.JSONObject(responseBody)
                val baseResp = json.optJSONObject("base_resp")
                if (baseResp?.optInt("status_code", -1) != 0) {
                    val msg = baseResp?.optString("status_msg", "Unknown error") ?: "Unknown error"
                    showError("MiniMax: $msg")
                    return@launch
                }
                
                withContext(Dispatchers.Main) { binding.tvMinimaxProgress.text = "Decoding audio..." }
                val data = json.optJSONObject("data")
                val hexAudio = data?.optString("audio", "") ?: ""
                
                if (hexAudio.isBlank()) {
                    showError("MiniMax: No audio in response")
                    return@launch
                }
                
                val audioBytes = hexStringToByteArray(hexAudio)
                val safeFileName = audioName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "").take(50)
                val file = saveAudioToFile(audioBytes, "${safeFileName}.mp3")
                val audioItem = AudioItem.create(Uri.fromFile(file), audioName)
                playlist.addItem(audioItem)
                
                withContext(Dispatchers.Main) {
                    binding.tvMinimaxProgress.text = "Done!"
                    requireContext().sendBroadcast(android.content.Intent("com.rudisec.echocast.AUDIO_ADDED"))
                    Toast.makeText(requireContext(), "Audio generated and added!", Toast.LENGTH_LONG).show()
                    binding.etMinimaxText.setText("")
                }
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    binding.llMinimaxProgress.visibility = View.GONE
                    binding.btnGenerateMinimax.isEnabled = true
                }
            }
        }
    }
    
    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
