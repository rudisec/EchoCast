package com.rudisec.echocast

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.telecom.Call
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

class SoundboardPlayer(private val context: Context) {
    companion object {
        private val TAG = SoundboardPlayer::class.java.simpleName
    }

    private val players = ConcurrentHashMap<String, PlayerThread>()

    fun play(sound: SoundboardSound, call: Call) {
        stop(sound.id)
        
        val player = PlayerThread(context, object : PlayerThread.OnPlaybackCompletedListener {
            override fun onPlaybackCompleted(thread: PlayerThread) {
                players.remove(sound.id)
            }

            override fun onPlaybackFailed(thread: PlayerThread, errorMsg: String?) {
                players.remove(sound.id)
                Log.e(TAG, "Failed to play sound ${sound.name}: $errorMsg")
            }
        }, call, sound.uri)
        
        players[sound.id] = player
        player.start()
    }

    fun stop(soundId: String) {
        players[soundId]?.let { player ->
            player.cancel()
            players.remove(soundId)
        }
    }

    fun stopAll() {
        players.forEach { (_, player) ->
            player.cancel()
        }
        players.clear()
    }

    fun isPlaying(soundId: String): Boolean {
        return players.containsKey(soundId)
    }
} 
