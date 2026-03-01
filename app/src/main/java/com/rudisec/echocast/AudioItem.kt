package com.rudisec.echocast

import android.net.Uri

/**
 * Represents a single audio item in the playlist/soundboard
 */
data class AudioItem(
    val id: String,
    val name: String,
    val uri: Uri,
    val duration: Long = 0L, // Duration in milliseconds
    var isEnabled: Boolean = true
) {
    companion object {
        fun create(uri: Uri, name: String? = null): AudioItem {
            val id = uri.toString().hashCode().toString()
            return AudioItem(
                id = id,
                name = name ?: uri.lastPathSegment ?: "Unknown",
                uri = uri
            )
        }
    }
}
