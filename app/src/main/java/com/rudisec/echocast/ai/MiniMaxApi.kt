package com.rudisec.echocast.ai

import java.io.File
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * MiniMax API client for T2A (Text-to-Speech) and voice cloning.
 * Docs: https://platform.minimax.io/docs/api-reference/api-overview
 */
class MiniMaxApi(private val apiKey: String) {
    companion object {
        private const val BASE_URL = "https://api.minimax.io"
        private const val DEFAULT_MODEL = "speech-2.8-turbo"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    private fun buildRequest(url: String, body: String? = null): Request {
        val requestBody = body ?: JSONObject().put("voice_type", "all").toString()
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody(jsonMediaType))
            .build()
    }

    /**
     * Get all available voices (system + cloned + generated)
     */
    fun getVoices(): Result<String> {
        val request = buildRequest("$BASE_URL/v1/get_voice")
        return execute(request)
    }

    /**
     * Generate speech from text using T2A API
     */
    fun textToSpeech(
        text: String,
        voiceId: String,
        model: String = DEFAULT_MODEL
    ): Result<String> {
        val body = JSONObject().apply {
            put("model", model)
            put("text", text)
            put("stream", false)
            put("language_boost", "auto")
            put("output_format", "hex")
            put("voice_setting", JSONObject().apply {
                put("voice_id", voiceId)
                put("speed", 1)
                put("vol", 1)
                put("pitch", 0)
            })
            put("audio_setting", JSONObject().apply {
                put("sample_rate", 32000)
                put("bitrate", 128000)
                put("format", "mp3")
                put("channel", 1)
            })
        }.toString()
        
        val request = buildRequest("$BASE_URL/v1/t2a_v2", body)
        return execute(request)
    }

    /**
     * Upload audio file for voice cloning
     */
    fun uploadVoiceCloneAudio(file: File): Result<Long> {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("purpose", "voice_clone")
            .addFormDataPart("file", file.name, file.asRequestBody(getAudioMediaType(file)))
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/v1/files/upload")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            response.close()
            
            if (!response.isSuccessful) {
                Result.failure(Exception("Upload failed: HTTP ${response.code} - $responseBody"))
            } else {
                val json = JSONObject(responseBody)
                val fileObj = json.optJSONObject("file")
                val fileId = fileObj?.optLong("file_id", -1L) ?: -1L
                val baseResp = json.optJSONObject("base_resp")
                val statusCode = baseResp?.optInt("status_code", -1) ?: -1
                
                if (statusCode == 0 && fileId > 0) {
                    Result.success(fileId)
                } else {
                    val msg = baseResp?.optString("status_msg", "Unknown error") ?: "Unknown error"
                    Result.failure(Exception("Upload failed: $msg"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Clone voice from uploaded audio
     */
    fun cloneVoice(fileId: Long, voiceId: String): Result<String> {
        val body = JSONObject().apply {
            put("file_id", fileId)
            put("voice_id", voiceId)
        }.toString()
        
        val request = buildRequest("$BASE_URL/v1/voice_clone", body)
        return execute(request)
    }

    private fun getAudioMediaType(file: File): MediaType {
        return when {
            file.name.endsWith(".mp3", true) -> "audio/mpeg".toMediaType()
            file.name.endsWith(".m4a", true) -> "audio/mp4".toMediaType()
            file.name.endsWith(".wav", true) -> "audio/wav".toMediaType()
            else -> "audio/mpeg".toMediaType()
        }
    }

    private fun execute(request: Request): Result<String> {
        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            response.close()
            
            if (!response.isSuccessful) {
                Result.failure(Exception("HTTP ${response.code} - $responseBody"))
            } else {
                Result.success(responseBody)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
