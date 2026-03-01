package com.rudisec.echocast

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages the playlist of audio items
 */
class AudioPlaylist(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "echocast_playlist"
        private const val KEY_PLAYLIST = "playlist_json"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val items = mutableListOf<AudioItem>()

    init {
        loadPlaylist()
    }

    /**
     * Get all audio items
     */
    fun getItems(): List<AudioItem> = items.toList()
    
    /**
     * Reload playlist from storage
     */
    fun reload() {
        loadPlaylist()
    }

    /**
     * Add an audio item to the playlist
     */
    fun addItem(item: AudioItem) {
        if (!items.any { it.id == item.id }) {
            items.add(item)
            savePlaylist()
        }
    }

    /**
     * Remove an audio item from the playlist
     */
    fun removeItem(itemId: String) {
        items.removeAll { it.id == itemId }
        savePlaylist()
    }

    /**
     * Update an audio item
     */
    fun updateItem(item: AudioItem) {
        val index = items.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            items[index] = item
            savePlaylist()
        }
    }

    /**
     * Get enabled audio items
     */
    fun getEnabledItems(): List<AudioItem> = items.filter { it.isEnabled }

    /**
     * Clear all items
     */
    fun clear() {
        items.clear()
        savePlaylist()
    }

    /**
     * Reorder items
     */
    fun reorderItems(newOrder: List<AudioItem>) {
        items.clear()
        items.addAll(newOrder)
        savePlaylist()
    }

    private fun savePlaylist() {
        val jsonArray = JSONArray()
        items.forEach { item ->
            val json = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("uri", item.uri.toString())
                put("duration", item.duration)
                put("isEnabled", item.isEnabled)
            }
            jsonArray.put(json)
        }
        // Use commit() for synchronous write to ensure data is persisted immediately
        prefs.edit().putString(KEY_PLAYLIST, jsonArray.toString()).commit()
    }

    private fun loadPlaylist() {
        items.clear()
        val jsonString = prefs.getString(KEY_PLAYLIST, null) ?: return

        try {
            if (jsonString.isBlank()) return
            
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                try {
                    val json = jsonArray.getJSONObject(i)
                    val uriString = json.getString("uri")
                    if (uriString.isBlank()) continue
                    
                    val item = AudioItem(
                        id = json.getString("id"),
                        name = json.getString("name"),
                        uri = Uri.parse(uriString),
                        duration = json.optLong("duration", 0L),
                        isEnabled = json.optBoolean("isEnabled", true)
                    )
                    items.add(item)
                } catch (e: Exception) {
                    // Skip invalid items, continue with next
                    continue
                }
            }
        } catch (e: Exception) {
            // If parsing fails, start with empty playlist
            items.clear()
            // Clear corrupted data
            prefs.edit {
                remove(KEY_PLAYLIST)
            }
        }
    }
}
