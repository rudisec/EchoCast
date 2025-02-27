package com.rudisec.echocast

import android.content.Context
import android.media.*
import android.net.Uri
import android.telecom.Call
import android.util.Log

/**
 * Plays back a sine wave to the telephony output device.
 *
 * @constructor Create a thread for audio playback. Note that the system only has a single telephony
 * output device. If multiple calls are active, unexpected behavior may occur.
 * @param context Used for accessing the [AudioManager]. A reference is kept in the object.
 * @param listener Used for sending completion notifications. The listener is called from this
 * thread, not the main thread.
 * @param call Used only for logging and is not saved.
 * @param audioUri The URI of the audio file to play, or null if no specific file is to be played.
 */
class PlayerThread(
    private val context: Context,
    private val listener: OnPlaybackCompletedListener,
    call: Call,
    private val audioUri: Uri? = null
) : Thread(PlayerThread::class.java.simpleName) {
    private val tag = "${PlayerThread::class.java.simpleName}/${id}"

    private val prefs = Preferences(context)

    // Thread state
    @Volatile private var isCancelled = false
    private var captureFailed = false

    init {
        Log.i(tag, "Created thread for call: $call")
    }

    override fun run() {
        var success = false
        var errorMsg: String? = null

        try {
            Log.i(tag, "Player thread started")

            if (isCancelled) {
                Log.i(tag, "Player cancelled before it began")
            } else {
                playUntilCancelled()

                success = !captureFailed
            }
        } catch (e: Exception) {
            Log.e(tag, "Error during playback", e)
            errorMsg = e.localizedMessage
        } finally {
            Log.i(tag, "Player thread completed")

            if (success) {
                listener.onPlaybackCompleted(this)
            } else {
                listener.onPlaybackFailed(this, errorMsg)
            }
        }
    }

    /**
     * Cancel playback. This stops playing audio after processing the next minimum buffer size.
     *
     * If called before [start], no initialization of the audio output device will occur.
     */
    fun cancel() {
        isCancelled = true
    }

    /**
     * Play audio until [cancel] is called or an audio playback error occurs.
     */
    private fun playUntilCancelled() {
        // Find telephony output device
        val audioManager = context.getSystemService(AudioManager::class.java)
        val telephonyOutput = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .find { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            ?: throw Exception("No telephony output audio device found")

        val audioFile = audioUri ?: prefs.audioFile ?: throw Exception("No input file was selected")
        val extractor = openAudioFile(audioFile)

        try {
            val codec = createDecoder(extractor)

            try {
                codec.start()

                // Usar el formato original del archivo
                val sampleRate = codec.inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = codec.inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
                val channelMask = if (channelCount > 1) {
                    AudioFormat.CHANNEL_OUT_STEREO
                } else {
                    AudioFormat.CHANNEL_OUT_MONO
                }

                val audioFormat = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
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

                // Buffer más grande para mejor calidad
                val bufferSize = minBufSize * 3

                Log.d(tag, "AudioTrack configuración:")
                Log.d(tag, "- Formato: ${codec.inputFormat.getString(MediaFormat.KEY_MIME)}")
                Log.d(tag, "- Sample rate: $sampleRate Hz")
                Log.d(tag, "- Canales: ${if (channelCount > 1) "Stereo" else "Mono"}")
                Log.d(tag, "- Buffer size: $bufferSize (min: $minBufSize)")

                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

                val audioTrack = AudioTrack.Builder()
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioAttributes(attributes)
                    .setAudioFormat(audioFormat)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                try {
                    if (!audioTrack.setPreferredDevice(telephonyOutput)) {
                        throw Exception("Failed to set preferred output device")
                    }

                    // Volumen al máximo para mejor calidad
                    audioTrack.setVolume(1.0f)
                    
                    audioTrack.play()
                    if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        throw Exception("AudioTrack is in a bad state: ${audioTrack.playState}")
                    }

                    try {
                        playbackLoop(audioTrack, codec, extractor)
                    } finally {
                        audioTrack.stop()
                    }
                } finally {
                    audioTrack.release()
                }
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /**
     * Main loop for playing the call audio.
     *
     * The loop runs forever until [cancel] is called. The approximate amount of time to cancel is
     * the time it takes to process the minimum buffer size.
     *
     * @param track [AudioTrack.play] must have been called
     * @param codec [MediaCodec.start] must have been called
     * @param extractor A track must have already been selected
     *
     * @throws Exception if an error occurs during audio playback or decoding
     */
    private fun playbackLoop(track: AudioTrack, codec: MediaCodec, extractor: MediaExtractor) {
        while (!isCancelled) {
            var waitForever = false

            val inputBufferId = codec.dequeueInputBuffer(TIMEOUT)
            if (inputBufferId >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferId)!!

                val n = extractor.readSampleData(inputBuffer, 0)

                val flags = if (n < 0) {
                    Log.d(tag, "On final buffer; submitting EOF")
                    waitForever = true
                    isCancelled = true
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
            } else {
                Log.w(tag, "Unexpected input buffer dequeue error: $inputBufferId")
            }

            flushOutput(track, codec, waitForever)
        }
    }

    /**
     * Flush queued PCM output from the codec to the audio track.
     *
     * @throws Exception if an error occurs during audio playback
     */
    private fun flushOutput(track: AudioTrack, codec: MediaCodec, waitForever: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val timeout = if (waitForever) { -1 } else { TIMEOUT }
            val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, timeout)
            if (outputBufferId >= 0) {
                val buffer = codec.getOutputBuffer(outputBufferId)!!

                val n = track.write(buffer, bufferInfo.size, AudioTrack.WRITE_BLOCKING)
                if (n < 0) {
                    throw Exception("Failed to write to audio track: $n")
                }

                codec.releaseOutputBuffer(outputBufferId, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.d(tag, "Received EOF; fully flushed")
                    // Output has been fully written
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

    private fun openAudioFile(uri: Uri): MediaExtractor {
        Log.d(tag, "Opening audio file: $uri")
        return MediaExtractor().apply {
            setDataSource(context, uri, null)
        }
    }

    private fun createDecoder(extractor: MediaExtractor): MediaCodec {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)

        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)

            if (!trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                Log.d(tag, "Skipping non-audio format: $trackFormat")
                continue
            }

            val mimeType = trackFormat.getString(MediaFormat.KEY_MIME)
            Log.d(tag, "Detected audio format: $mimeType")

            val decoder = codecList.findDecoderForFormat(trackFormat)
            if (decoder == null) {
                Log.w(tag, "No decoder found for: $trackFormat")
                continue
            }

            Log.d(tag, "Configuración de audio:")
            Log.d(tag, "- Decoder: $decoder")
            Log.d(tag, "- Sample rate: ${trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)} Hz")
            Log.d(tag, "- Canales: ${trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)}")

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

    interface OnPlaybackCompletedListener {
        /**
         * Called when the playback completes successfully.
         */
        fun onPlaybackCompleted(thread: PlayerThread)

        /**
         * Called when an error occurs during playback.
         */
        fun onPlaybackFailed(thread: PlayerThread, errorMsg: String?)
    }
}
