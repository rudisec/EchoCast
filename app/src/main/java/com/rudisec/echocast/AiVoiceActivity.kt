package com.rudisec.echocast

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.rudisec.echocast.ai.*
import com.rudisec.echocast.databinding.ActivityAiVoiceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream

class AiVoiceActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAiVoiceBinding
    private lateinit var prefs: Preferences
    private lateinit var playlist: AudioPlaylist
    private var sampleUri: Uri? = null

    private val api: ElevenLabsApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.elevenlabs.io/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ElevenLabsApi::class.java)
    }

    private val samplePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        sampleUri = uri
        binding.tvSampleStatus.text = uri?.lastPathSegment ?: "Ningún archivo seleccionado"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiVoiceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Preferences(this)
        playlist = AudioPlaylist(this)

        setupToolbar()
        setupUI()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupUI() {
        binding.etApiKey.setText(prefs.apiKey)
        binding.etApiKey.addTextChangedListener {
            prefs.apiKey = it.toString()
        }

        binding.btnUploadSample.setOnClickListener {
            samplePickerLauncher.launch("audio/*")
        }

        binding.btnGenerateAudio.setOnClickListener {
            generateAudio()
        }
    }

    private fun generateAudio() {
        val apiKey = prefs.apiKey
        val text = binding.etTtsText.text.toString()
        val voiceName = binding.etVoiceName.text.toString()

        if (apiKey.isBlank()) {
            Toast.makeText(this, "Introduce tu API Key", Toast.LENGTH_SHORT).show()
            return
        }
        if (text.isBlank()) {
            Toast.makeText(this, "Escribe un texto", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnGenerateAudio.isEnabled = false

        lifecycleScope.launch {
            try {
                // 1. Obtener o crear voice_id
                val voiceId = getOrCreateVoiceId(apiKey, voiceName)
                
                if (voiceId != null) {
                    prefs.selectedVoiceId = voiceId // Guardamos la voz activa para STS
                    // 2. Generar TTS
                    val response = api.textToSpeech(apiKey, voiceId, TtsRequest(text))
                    if (response.isSuccessful) {
                        val body = response.body()
                        if (body != null) {
                            // 3. Guardar archivo localmente
                            val file = saveAudioToFile(body.byteStream().readBytes(), "ai_${System.currentTimeMillis()}.mp3")
                            
                            // 4. Añadir a playlist
                            val audioItem = AudioItem.create(Uri.fromFile(file))
                            playlist.addItem(audioItem)
                            
                            withContext(Dispatchers.Main) {
                                // Notify AudioPlayerFragment to refresh the playlist
                                sendBroadcast(
                                    android.content.Intent("com.rudisec.echocast.AUDIO_ADDED")
                                )
                                Toast.makeText(this@AiVoiceActivity, "Audio generado y añadido", Toast.LENGTH_LONG).show()
                                finish()
                            }
                        }
                    } else {
                        showError("Error en TTS: ${response.code()}")
                    }
                }
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnGenerateAudio.isEnabled = true
                }
            }
        }
    }

    private suspend fun getOrCreateVoiceId(apiKey: String, name: String): String? {
        // Primero intentamos buscar si ya existe una voz con ese nombre
        val voicesResponse = api.getVoices(apiKey)
        val existingVoice = voicesResponse.voices.find { it.name.equals(name, ignoreCase = true) }
        
        if (existingVoice != null) return existingVoice.voice_id

        // Si no existe y tenemos muestra, la clonamos
        if (sampleUri != null) {
            val file = getFileFromUri(sampleUri!!)
            if (file != null) {
                val fileName = file.name.substringAfterLast("/").substringAfterLast("\\")
                val requestFile = file.asRequestBody("audio/mpeg".toMediaTypeOrNull())
                
                // Create multipart body manually
                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("name", name)
                    .addFormDataPart("files", fileName, requestFile)
                    .build()
                
                val response = api.addVoice(apiKey, multipartBody)
                if (response.isSuccessful) {
                    return response.body()?.voice_id
                }
            }
        }
        
        // Si no hay muestra, usamos una voz por defecto (ej. la primera disponible)
        return voicesResponse.voices.firstOrNull()?.voice_id
    }

    private fun saveAudioToFile(data: ByteArray, fileName: String): File {
        val file = File(getExternalFilesDir(null), fileName)
        FileOutputStream(file).use { it.write(data) }
        return file
    }

    private fun getFileFromUri(uri: Uri): File? {
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val file = File(cacheDir, "temp_sample.mp3")
        FileOutputStream(file).use { output ->
            inputStream.copyTo(output)
        }
        return file
    }

    private suspend fun showError(msg: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@AiVoiceActivity, msg, Toast.LENGTH_LONG).show()
        }
    }
}
