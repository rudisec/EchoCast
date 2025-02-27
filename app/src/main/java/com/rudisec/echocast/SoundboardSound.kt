package com.rudisec.echocast

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.util.concurrent.TimeUnit

data class SoundboardSound(
    val id: String,
    val name: String,
    val uri: Uri
) {
    fun getDuration(context: Context): String {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
            retriever.release()
            
            formatDuration(durationMs)
        } catch (e: Exception) {
            "--:--"
        }
    }
    
    private fun formatDuration(durationMs: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) - TimeUnit.MINUTES.toSeconds(minutes)
        return String.format("%02d:%02d", minutes, seconds)
    }
} 
