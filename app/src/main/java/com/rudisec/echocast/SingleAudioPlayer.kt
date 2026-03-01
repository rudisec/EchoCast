package com.rudisec.echocast

import android.content.Context
import android.media.*
import android.net.Uri
import android.telecom.Call
import android.util.Log

/**
 * Plays a single audio file to the telephony output device.
 */
class SingleAudioPlayer(
    private val context: Context,
    private val listener: AudioPlayerListener,
    call: Call,
    private val audioItem: AudioItem,
    private val loop: Boolean = false
) : Thread(SingleAudioPlayer::class.java.simpleName) {
    
    interface AudioPlayerListener {
        fun onPlaybackCompleted()
        fun onPlaybackFailed(errorMsg: String?)
    }
    
    private val tag = "${SingleAudioPlayer::class.java.simpleName}/${id}"

    // Thread state
    @Volatile private var isCancelled = false
    @Volatile private var isPaused = false
    private var playbackFailed = false
    private var errorMsg: String? = null
    private var currentAudioTrack: AudioTrack? = null

    init {
        Log.i(tag, "Created player thread for call: $call with audio: ${audioItem.name}")
        // Load initial state from preferences
        val prefs = Preferences(context)
        isPaused = prefs.isPaused
    }

    override fun run() {
        var success = false

        try {
            Log.i(tag, "Player thread started")

            if (isCancelled) {
                Log.i(tag, "Player cancelled before it began")
            } else {
                if (loop) {
                    // Loop playback until cancelled
                    while (!isCancelled) {
                        playAudioFile()
                        if (isCancelled || playbackFailed) break
                    }
                } else {
                    // Play once
                    playAudioFile()
                }
                success = !playbackFailed
            }
        } catch (e: Exception) {
            Log.e(tag, "Error during playback", e)
            errorMsg = e.localizedMessage
            playbackFailed = true
        } finally {
            Log.i(tag, "Player thread completed")

            if (success && !loop) {
                // Only notify completion if not looping (loop mode continues until cancelled)
                listener.onPlaybackCompleted()
            } else if (!success) {
                listener.onPlaybackFailed(errorMsg)
            }
        }
    }

    /**
     * Cancel playback. This stops playing audio after processing the current buffer.
     */
    fun cancel() {
        isCancelled = true
    }

    /**
     * Pause or resume playback
     */
    fun setPaused(paused: Boolean) {
        isPaused = paused
        currentAudioTrack?.let { track ->
            if (paused) {
                track.pause()
            } else {
                track.play()
            }
        }
    }

    /**
     * Play the audio file
     */
    private fun playAudioFile() {
        Log.d(tag, "Playing audio file: ${audioItem.name}")

        // Find telephony output device
        val audioManager = context.getSystemService(AudioManager::class.java)
        val telephonyOutput = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .find { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            ?: throw Exception("No telephony output audio device found")

        val extractor = MediaExtractor().apply {
            setDataSource(context, audioItem.uri, null)
        }

        try {
            val codec = createDecoder(extractor)

            try {
                codec.start()

                val channelMask = when (val channelCount =
                        codec.inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) {
                    1 -> AudioFormat.CHANNEL_OUT_FRONT_LEFT
                    2 -> AudioFormat.CHANNEL_OUT_FRONT_LEFT or AudioFormat.CHANNEL_OUT_FRONT_RIGHT
                    else -> throw Exception("Unsupported channel count: $channelCount")
                }

                val audioFormat = AudioFormat.Builder()
                    .setSampleRate(codec.inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()

                val minBufSize = AudioTrack.getMinBufferSize(
                    audioFormat.sampleRate,
                    audioFormat.channelMask,
                    audioFormat.encoding,
                )
                if (minBufSize < 0) {
                    throw Exception("Failure when querying minimum buffer size: $minBufSize")
                }

                // Create audio output track
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .build()

                val audioTrack = AudioTrack.Builder()
                    .setBufferSizeInBytes(minBufSize)
                    .setAudioAttributes(attributes)
                    .setAudioFormat(audioFormat)
                    .build()

                currentAudioTrack = audioTrack

                try {
                    if (!audioTrack.setPreferredDevice(telephonyOutput)) {
                        throw Exception("Failed to set preferred output device")
                    }

                    // Volume will be applied when writing PCM data

                    // Start playing if not paused
                    if (!isPaused) {
                        audioTrack.play()
                        if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            throw Exception("AudioTrack is in a bad state: ${audioTrack.playState}")
                        }
                    }

                    try {
                        playbackLoop(audioTrack, codec, extractor)
                    } finally {
                        audioTrack.stop()
                    }
                } finally {
                    audioTrack.release()
                    currentAudioTrack = null
                }
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /**
     * Main loop for playing the audio file
     */
    private fun playbackLoop(track: AudioTrack, codec: MediaCodec, extractor: MediaExtractor) {
        var lastPrefsCheck = System.currentTimeMillis()
        val prefsCheckInterval = 200L // Check preferences every 200ms

        while (!isCancelled) {
            // Periodically check preferences for pause changes
            val now = System.currentTimeMillis()
            if (now - lastPrefsCheck > prefsCheckInterval) {
                val prefs = Preferences(context)
                val newPaused = prefs.isPaused

                if (newPaused != isPaused) {
                    isPaused = newPaused
                }

                lastPrefsCheck = now
            }

            // Handle pause state
            if (isPaused) {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
                Thread.sleep(100) // Sleep while paused
                continue
            } else {
                // Resume if paused
                if (track.playState == AudioTrack.PLAYSTATE_PAUSED) {
                    track.play()
                }
            }

            var waitForever = false

            val inputBufferId = codec.dequeueInputBuffer(TIMEOUT)
            if (inputBufferId >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferId)!!

                val n = extractor.readSampleData(inputBuffer, 0)

                val flags = if (n < 0) {
                    Log.d(tag, "On final buffer; submitting EOF")
                    waitForever = true
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                } else {
                    0
                }

                codec.queueInputBuffer(
                    inputBufferId,
                    0,
                    inputBuffer.limit(),
                    extractor.sampleTime,
                    flags,
                )

                extractor.advance()
            }

            flushOutput(track, codec, waitForever)
            
            if (waitForever) {
                // End of file
                break
            }
        }
    }

    /**
     * Flush queued PCM output from the codec to the audio track
     */
    private fun flushOutput(track: AudioTrack, codec: MediaCodec, waitForever: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val timeout = if (waitForever) { -1 } else { TIMEOUT }
            val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, timeout)
            if (outputBufferId >= 0) {
                val buffer = codec.getOutputBuffer(outputBufferId)!!
                
                // Write PCM data directly to audio track
                if (bufferInfo.size > 0) {
                    val originalPosition = buffer.position()
                    val originalLimit = buffer.limit()
                    
                    // Set buffer to the data range
                    buffer.position(0)
                    buffer.limit(bufferInfo.size)
                    
                    val n = track.write(buffer, bufferInfo.size, AudioTrack.WRITE_BLOCKING)
                    
                    // Restore original buffer position/limit
                    buffer.position(originalPosition)
                    buffer.limit(originalLimit)
                    
                    if (n < 0) {
                        throw Exception("Failed to write to audio track: $n")
                    }
                }

                codec.releaseOutputBuffer(outputBufferId, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.d(tag, "Received EOF; fully flushed")
                    break
                }
            } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d(tag, "Output format changed to: ${codec.outputFormat}")
            } else if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else {
                Log.w(tag, "Unexpected output buffer dequeue error: $outputBufferId")
                break
            }
        }
    }

    private fun createDecoder(extractor: MediaExtractor): MediaCodec {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)

        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)

            // This is an audio format if there's a sample rate
            if (!trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                Log.d(tag, "Skipping non-audio format: $trackFormat")
                continue
            }

            val decoder = codecList.findDecoderForFormat(trackFormat)
            if (decoder == null) {
                Log.w(tag, "No decoder found for: $trackFormat")
                continue
            }

            Log.d(tag, "Audio decoder: $decoder")

            val codec = MediaCodec.createByCodecName(decoder)

            try {
                codec.configure(trackFormat, null, null, 0)
                extractor.selectTrack(i)
                return codec
            } catch (e: Exception) {
                Log.w(tag, "Failed to configure codec for: $trackFormat", e)
                codec.release()
            }
        }

        throw Exception("No decoders could handle the input file")
    }

    companion object {
        private const val TIMEOUT = 500L
    }
}
