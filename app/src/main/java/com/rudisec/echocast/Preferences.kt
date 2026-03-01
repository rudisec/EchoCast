package com.rudisec.echocast

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

class Preferences(private val context: Context) {
    companion object {
        const val PREF_ENABLED = "enabled"
        const val PREF_PLAY_MODE = "play_mode" // "single", "loop", "shuffle"
        const val PREF_AUTO_PLAY = "auto_play"
        const val PREF_API_KEY = "api_key"
        const val PREF_SELECTED_VOICE_ID = "selected_voice_id"
        const val PREF_IS_PAUSED = "is_paused"
        const val PREF_VOLUME = "volume" // 0.0 to 1.0
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var selectedVoiceId: String?
        get() = prefs.getString(PREF_SELECTED_VOICE_ID, null)
        set(value) = prefs.edit { putString(PREF_SELECTED_VOICE_ID, value) }

    /**
     * ElevenLabs API Key
     */
    var apiKey: String
        get() = prefs.getString(PREF_API_KEY, "") ?: ""
        set(key) = prefs.edit { putString(PREF_API_KEY, key) }

    /**
     * Whether call audio playback is enabled
     */
    var isEnabled: Boolean
        get() = prefs.getBoolean(PREF_ENABLED, false)
        set(enabled) = prefs.edit { putBoolean(PREF_ENABLED, enabled) }

    /**
     * Play mode: "single" (play once), "loop" (repeat all), "shuffle" (random order)
     */
    var playMode: String
        get() = prefs.getString(PREF_PLAY_MODE, "single") ?: "single"
        set(mode) = prefs.edit { putString(PREF_PLAY_MODE, mode) }

    /**
     * Whether to automatically start playing when a call is active
     */
    var autoPlay: Boolean
        get() = prefs.getBoolean(PREF_AUTO_PLAY, true)
        set(auto) = prefs.edit { putBoolean(PREF_AUTO_PLAY, auto) }

    /**
     * Whether playback is currently paused
     */
    var isPaused: Boolean
        get() = prefs.getBoolean(PREF_IS_PAUSED, false)
        set(paused) = prefs.edit { putBoolean(PREF_IS_PAUSED, paused) }

    /**
     * Playback volume (0.0 to 1.0, where 1.0 = 100%)
     */
    var volume: Float
        get() = prefs.getFloat(PREF_VOLUME, 1.0f)
        set(vol) = prefs.edit { putFloat(PREF_VOLUME, vol.coerceIn(0.0f, 1.0f)) }
    
    /**
     * Whether shuffle playback is paused
     */
    var isShufflePaused: Boolean
        get() = prefs.getBoolean("is_shuffle_paused", true)
        set(paused) = prefs.edit { putBoolean("is_shuffle_paused", paused) }
    
    /**
     * Whether shuffle should loop when all audios have been played
     */
    var isShuffleLoop: Boolean
        get() = prefs.getBoolean("is_shuffle_loop", true)
        set(value) = prefs.edit { putBoolean("is_shuffle_loop", value) }
    
    /**
     * Selected voice ID for Text to Speech
     */
    var selectedVoiceIdTts: String?
        get() = prefs.getString("selected_voice_id_tts", null)
        set(id) = prefs.edit { putString("selected_voice_id_tts", id) }
    
    /**
     * Selected voice name for Text to Speech
     */
    var selectedVoiceNameTts: String?
        get() = prefs.getString("selected_voice_name_tts", null)
        set(name) = prefs.edit { putString("selected_voice_name_tts", name) }
    
    // MiniMax API Key (for celebrity/custom voice TTS)
    var minimaxApiKey: String?
        get() = prefs.getString("minimax_api_key", null)
        set(value) = prefs.edit { putString("minimax_api_key", value) }
    
    // MiniMax cloned voices: "voiceId|voiceName,voiceId|voiceName"
    var minimaxClonedVoices: String?
        get() = prefs.getString("minimax_cloned_voices", null)
        set(value) = prefs.edit { putString("minimax_cloned_voices", value) }
    
    // MiniMax selected voice for TTS
    var minimaxSelectedVoiceId: String?
        get() = prefs.getString("minimax_selected_voice_id", null)
        set(value) = prefs.edit { putString("minimax_selected_voice_id", value) }
    
    var minimaxSelectedVoiceName: String?
        get() = prefs.getString("minimax_selected_voice_name", null)
        set(value) = prefs.edit { putString("minimax_selected_voice_name", value) }
}
