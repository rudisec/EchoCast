package com.rudisec.echocast.ai

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface ElevenLabsApi {
    @POST("v1/voices/add")
    suspend fun addVoice(
        @Header("xi-api-key") apiKey: String,
        @Body requestBody: RequestBody
    ): retrofit2.Response<VoiceResponse>

    @POST("v1/text-to-speech/{voiceId}")
    @Streaming
    suspend fun textToSpeech(
        @Header("xi-api-key") apiKey: String,
        @Path("voiceId") voiceId: String,
        @Body request: TtsRequest
    ): Response<ResponseBody>

    @GET("v1/voices")
    suspend fun getVoices(
        @Header("xi-api-key") apiKey: String
    ): VoicesListResponse

    @DELETE("v1/voices/{voiceId}")
    suspend fun deleteVoice(
        @Header("xi-api-key") apiKey: String,
        @Path("voiceId") voiceId: String
    ): Response<ResponseBody>

    @Multipart
    @POST("v1/speech-to-speech/{voiceId}")
    @Streaming
    suspend fun speechToSpeech(
        @Header("xi-api-key") apiKey: String,
        @Path("voiceId") voiceId: String,
        @Part audio: MultipartBody.Part,
        @Part("model_id") modelId: RequestBody = RequestBody.create(MultipartBody.FORM, "eleven_multilingual_sts_v2")
    ): Response<ResponseBody>
}

data class VoiceResponse(val voice_id: String)

data class VoicesListResponse(val voices: List<VoiceItem>)

data class VoiceItem(val voice_id: String, val name: String, val category: String? = null)

data class TtsRequest(
    val text: String,
    val model_id: String = "eleven_multilingual_v2",
    val voice_settings: VoiceSettings = VoiceSettings()
)

data class VoiceSettings(
    val stability: Float = 0.5f,
    val similarity_boost: Float = 0.75f
)
