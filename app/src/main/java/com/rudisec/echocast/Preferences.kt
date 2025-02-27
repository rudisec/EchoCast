package com.rudisec.echocast

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

class Preferences(private val context: Context) {
    companion object {
        const val PREF_ENABLED = "enabled"
        const val PREF_AUDIO_FILE = "audio_file"
        const val PREF_VERSION = "version"
        const val PREF_SOUNDBOARD_SOUNDS = "soundboard_sounds"
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    /**
     * Whether call audio playback is enabled.
     */
    var isEnabled: Boolean
        get() = prefs.getBoolean(PREF_ENABLED, false)
        set(enabled) = prefs.edit { putBoolean(PREF_ENABLED, enabled) }

    var audioFile: Uri?
        get() = prefs.getString(PREF_AUDIO_FILE, null)?.let { Uri.parse(it) }
        set(uri) {
            val oldUri = audioFile
            if (oldUri == uri) {
                // URI is the same as before or both are null
                return
            }

            prefs.edit {
                if (uri != null) {
                    // Persist permissions for the new URI first
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    putString(PREF_AUDIO_FILE, uri.toString())
                } else {
                    remove(PREF_AUDIO_FILE)
                }
            }

            // Release persisted permissions on the old directory only after the new URI is set to
            // guarantee atomicity
            if (oldUri != null) {
                context.contentResolver.releasePersistableUriPermission(
                    oldUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }

    fun getSoundboardSounds(): List<SoundboardSound> {
        val jsonStr = prefs.getString(PREF_SOUNDBOARD_SOUNDS, "[]")
        val jsonArray = JSONArray(jsonStr)
        val sounds = mutableListOf<SoundboardSound>()
        
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            sounds.add(SoundboardSound(
                obj.getString("id"),
                obj.getString("name"),
                Uri.parse(obj.getString("uri"))
            ))
        }
        
        return sounds
    }

    fun addSoundboardSound(name: String, uri: Uri) {
        val sounds = getSoundboardSounds().toMutableList()
        val id = UUID.randomUUID().toString()
        
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        
        sounds.add(SoundboardSound(id, name, uri))
        saveSoundboardSounds(sounds)
    }

    fun removeSoundboardSound(id: String) {
        val sounds = getSoundboardSounds().toMutableList()
        val sound = sounds.find { it.id == id }
        
        if (sound != null) {
            context.contentResolver.releasePersistableUriPermission(
                sound.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            sounds.removeIf { it.id == id }
            saveSoundboardSounds(sounds)
        }
    }

    private fun saveSoundboardSounds(sounds: List<SoundboardSound>) {
        val jsonArray = JSONArray()
        
        for (sound in sounds) {
            val obj = JSONObject()
            obj.put("id", sound.id)
            obj.put("name", sound.name)
            obj.put("uri", sound.uri.toString())
            jsonArray.put(obj)
        }
        
        prefs.edit {
            putString(PREF_SOUNDBOARD_SOUNDS, jsonArray.toString())
        }
    }
}
