package com.rudisec.echocast

import android.content.Context
import android.media.*
import android.net.Uri
import android.telecom.Call
import android.util.Log
import com.rudisec.echocast.ai.ElevenLabsApi
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles speech-to-speech transformation during calls
 */
class SpeechToSpeechHandler(
    private val context: Context,
    private val call: Call,
    private val api: ElevenLabsApi,
    private val apiKey: String,
    private val voiceId: String
) {
    private val TAG = SpeechToSpeechHandler::class.java.simpleName
    
    @Volatile
    private var isRunning = false
    
    @Volatile
    private var isCancelled = false
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Audio configuration
    private val sampleRate = 16000 // 16kHz for speech
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    
    // Buffer for audio chunks (send to API every ~500ms)
    private val audioChunkDurationMs = 500
    private val audioChunkSize = (sampleRate * audioChunkDurationMs / 1000) * 2 // 16-bit = 2 bytes per sample
    
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Speech-to-speech already running")
            return
        }
        
        isRunning = true
        isCancelled = false
        
        try {
            // Mute microphone
            muteMicrophone(true)
            
            // Initialize AudioRecord for microphone input
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                stop()
                return
            }
            
            // Get telephony output device
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val telephonyOutput = audioDevices.firstOrNull { 
                it.type == AudioDeviceInfo.TYPE_TELEPHONY 
            }
            
            if (telephonyOutput == null) {
                Log.e(TAG, "Telephony output device not found")
                stop()
                return
            }
            
            // Initialize AudioTrack for output
            val outputFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            
            val outputAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(outputAttributes)
                .setAudioFormat(outputFormat)
                .setBufferSizeInBytes(bufferSize * 2)
                .build()
            
            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack initialization failed")
                stop()
                return
            }
            
            audioTrack?.setPreferredDevice(telephonyOutput)
            audioTrack?.play()
            
            // Start recording and processing
            audioRecord?.startRecording()
            Log.i(TAG, "Speech-to-speech started")
            
            recordingJob = scope.launch {
                processAudioStream()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech-to-speech", e)
            stop()
        }
    }
    
    private suspend fun processAudioStream() {
        val buffer = ShortArray(audioChunkSize / 2)
        val audioChunk = ByteArrayOutputStream()
        
        while (isRunning && !isCancelled && audioRecord != null) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                
                if (bytesRead > 0) {
                    // Convert shorts to bytes (little-endian)
                    for (i in 0 until bytesRead) {
                        val value = buffer[i]
                        audioChunk.write(value.toInt() and 0xFF)
                        audioChunk.write((value.toInt() shr 8) and 0xFF)
                    }
                    
                    // When we have enough audio, send to API
                    if (audioChunk.size() >= audioChunkSize) {
                        val chunkData = audioChunk.toByteArray()
                        audioChunk.reset()
                        
                        // Send to API and play response
                        sendToApiAndPlay(chunkData)
                    }
                } else if (bytesRead < 0) {
                    Log.e(TAG, "Error reading audio: $bytesRead")
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing audio stream", e)
                break
            }
        }
    }
    
    private suspend fun sendToApiAndPlay(audioData: ByteArray) {
        try {
            // Convert PCM raw data to WAV format with headers
            val wavData = createWavFile(audioData, sampleRate, 1) // 1 channel (mono)
            
            // Save audio chunk to temporary file
            val tempFile = File(context.cacheDir, "sts_input_${System.currentTimeMillis()}.wav")
            FileOutputStream(tempFile).use { it.write(wavData) }
            
            // Create multipart request
            val requestFile = tempFile.asRequestBody("audio/wav".toMediaTypeOrNull())
            val audioPart = MultipartBody.Part.createFormData("audio", tempFile.name, requestFile)
            val modelIdPart = "eleven_multilingual_sts_v2".toRequestBody(MultipartBody.FORM)
            
            // Call API
            val response = api.speechToSpeech(apiKey, voiceId, audioPart, modelIdPart)
            
            if (response.isSuccessful) {
                val responseBody = response.body()
                if (responseBody != null) {
                    // Read response audio (likely MP3)
                    val responseAudio = responseBody.byteStream().readBytes()
                    
                    // Save to temp file and decode
                    val tempResponseFile = File(context.cacheDir, "sts_response_${System.currentTimeMillis()}.mp3")
                    FileOutputStream(tempResponseFile).use { it.write(responseAudio) }
                    
                    // Decode and play response audio
                    playResponseAudio(Uri.fromFile(tempResponseFile))
                    
                    // Clean up temp response file after a delay
                    scope.launch {
                        delay(5000)
                        tempResponseFile.delete()
                    }
                }
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Speech-to-speech API error: ${response.code()} - $errorBody")
            }
            
            // Clean up temp file
            tempFile.delete()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in speech-to-speech API call", e)
        }
    }
    
    private fun createWavFile(pcmData: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val dataSize = pcmData.size
        val fileSize = 36 + dataSize
        
        val buffer = ByteBuffer.allocate(44 + dataSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(fileSize)
        buffer.put("WAVE".toByteArray())
        
        // fmt chunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // fmt chunk size
        buffer.putShort(1.toShort()) // audio format (1 = PCM)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * channels * 2) // byte rate
        buffer.putShort((channels * 2).toShort()) // block align
        buffer.putShort(16.toShort()) // bits per sample
        
        // data chunk
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        buffer.put(pcmData)
        
        return buffer.array()
    }
    
    private fun playResponseAudio(audioUri: Uri) {
        if (audioTrack == null || isCancelled) return
        
        try {
            // Decode audio file using MediaExtractor and MediaCodec
            val extractor = MediaExtractor().apply {
                setDataSource(context, audioUri, null)
            }
            
            try {
                val codec = createDecoder(extractor)
                codec.start()
                
                val channelMask = when (val channelCount = codec.inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) {
                    1 -> AudioFormat.CHANNEL_OUT_MONO
                    2 -> AudioFormat.CHANNEL_OUT_STEREO
                    else -> AudioFormat.CHANNEL_OUT_MONO
                }
                
                val outputFormat = AudioFormat.Builder()
                    .setSampleRate(codec.inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
                
                // Use existing audioTrack if format matches, otherwise create new one
                val track = audioTrack ?: return
                
                // Decode and play
                val bufferInfo = MediaCodec.BufferInfo()
                var inputEOS = false
                var outputEOS = false
                
                while (!outputEOS && !isCancelled) {
                    // Feed input
                    if (!inputEOS) {
                        val inputBufferId = codec.dequeueInputBuffer(10000)
                        if (inputBufferId >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputBufferId)!!
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEOS = true
                            } else {
                                codec.queueInputBuffer(inputBufferId, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    
                    // Get output
                    val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferId >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferId)!!
                        if (bufferInfo.size > 0) {
                            val pcmData = ByteArray(bufferInfo.size)
                            outputBuffer.get(pcmData)
                            track.write(pcmData, 0, pcmData.size, AudioTrack.WRITE_BLOCKING)
                        }
                        codec.releaseOutputBuffer(outputBufferId, false)
                        
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEOS = true
                        }
                    }
                }
                
                codec.stop()
                codec.release()
            } finally {
                extractor.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing response audio", e)
        }
    }
    
    private fun createDecoder(extractor: MediaExtractor): MediaCodec {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        
        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            
            // This is an audio format if there's a sample rate
            if (!trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                Log.d(TAG, "Skipping non-audio format: $trackFormat")
                continue
            }
            
            val decoder = codecList.findDecoderForFormat(trackFormat)
            if (decoder == null) {
                Log.w(TAG, "No decoder found for: $trackFormat")
                continue
            }
            
            Log.d(TAG, "Audio decoder: $decoder")
            
            val codec = MediaCodec.createByCodecName(decoder)
            
            try {
                codec.configure(trackFormat, null, null, 0)
                extractor.selectTrack(i)
                return codec
            } catch (e: Exception) {
                Log.w(TAG, "Failed to configure codec for: $trackFormat", e)
                codec.release()
            }
        }
        
        throw Exception("No decoders could handle the input file")
    }
    
    private fun muteMicrophone(mute: Boolean) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.isMicrophoneMute = mute
            Log.d(TAG, "Microphone muted: $mute")
        } catch (e: Exception) {
            Log.e(TAG, "Error muting microphone", e)
        }
    }
    
    fun stop() {
        if (!isRunning) return
        
        isRunning = false
        isCancelled = true
        
        recordingJob?.cancel()
        playbackJob?.cancel()
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        
        // Unmute microphone
        muteMicrophone(false)
        
        scope.cancel()
        
        Log.i(TAG, "Speech-to-speech stopped")
    }
}
