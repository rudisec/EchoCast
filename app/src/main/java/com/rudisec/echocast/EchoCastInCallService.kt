package com.rudisec.echocast

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
class EchoCastInCallService : InCallService(), MultiAudioPlayer.OnPlaybackCompletedListener, SingleAudioPlayer.AudioPlayerListener {
    companion object {
        private val TAG = EchoCastInCallService::class.java.simpleName
    }

    private lateinit var prefs: Preferences
    private lateinit var playlist: AudioPlaylist
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Active calls
     */
    private val activeCalls = HashMap<String, Call>()

    /**
     * Player threads for each active call
     */
    private val players = HashMap<Call, Any>()

    /**
     * Number of threads pending exit after the call has been disconnected
     */
    private var pendingExit = 0

    private var failedNotificationId = 2

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            Log.d(TAG, "onStateChanged: $call, $state")
            handleStateChange(call)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Preferences(this)
        playlist = AudioPlaylist(this)
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PLAY_AUDIO" -> {
                val audioId = intent.getStringExtra("audio_id")
                val audioUriString = intent.getStringExtra("audio_uri")
                val playMode = intent.getStringExtra("play_mode")
                
                if (audioId != null && audioUriString != null) {
                    val audioUri = android.net.Uri.parse(audioUriString)
                    val audioItem = AudioItem.create(audioUri)
                    
                    // Find active call
                    val activeCall = getActiveCall()
                    if (activeCall != null) {
                        playSingleAudio(activeCall, audioItem, playMode)
                    } else {
                        Log.w(TAG, "No active call to play audio")
                    }
                }
            }
            "STOP_AUDIO" -> {
                // Stop all players for now (can be improved to stop specific audio)
                players.values.forEach { player ->
                    when (player) {
                        is MultiAudioPlayer -> player.cancel()
                        is SingleAudioPlayer -> player.cancel()
                    }
                }
                players.clear()
                updateForegroundState()
                // Notify adapter to reset play button
                sendBroadcast(Intent("com.rudisec.echocast.AUDIO_STOPPED"))
            }
            "START_SHUFFLE" -> {
                val activeCall = getActiveCall()
                if (activeCall != null) {
                    startShufflePlayback(activeCall)
                } else {
                    Log.w(TAG, "No active call to start shuffle")
                    sendBroadcast(Intent("com.rudisec.echocast.SHUFFLE_FAILED").apply {
                        putExtra("reason", "no_call")
                    })
                }
            }
            "PAUSE_SHUFFLE" -> {
                prefs.isShufflePaused = true
            }
            "RESUME_SHUFFLE" -> {
                prefs.isShufflePaused = false
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun getActiveCall(): Call? {
        // Return the first active call
        return activeCalls.values.firstOrNull { call ->
            val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                call.details.state
            } else {
                @Suppress("DEPRECATION")
                call.state
            }
            state == Call.STATE_ACTIVE
        }
    }

    private fun playSingleAudio(call: Call, audioItem: AudioItem, playMode: String? = null) {
        // Cancel any existing player for this call
        players[call]?.let { existingPlayer ->
            when (existingPlayer) {
                is MultiAudioPlayer -> existingPlayer.cancel()
                is SingleAudioPlayer -> existingPlayer.cancel()
            }
        }
        
        val mode = playMode ?: prefs.playMode
        
        when (mode) {
            "loop" -> {
                // Play in loop until paused
                val player = SingleAudioPlayer(
                    this,
                    this,
                    call,
                    audioItem,
                    loop = true
                )
                players[call] = player
                updateForegroundState()
                player.start()
            }
            "shuffle" -> {
                // Start shuffle playback
                startShufflePlayback(call)
            }
            else -> {
                // Manual mode: play once
                val player = SingleAudioPlayer(
                    this,
                    this,
                    call,
                    audioItem,
                    loop = false
                )
                players[call] = player
                updateForegroundState()
                player.start()
            }
        }
    }
    
    private fun startShufflePlayback(call: Call) {
        playlist.reload()
        val enabledItems = playlist.getEnabledItems()
        if (enabledItems.isEmpty()) {
            Log.w(TAG, "No enabled items for shuffle playback")
            sendBroadcast(Intent("com.rudisec.echocast.SHUFFLE_FAILED").apply {
                putExtra("reason", "no_audios")
            })
            return
        }
        
        // Reserve slot so paused-state polling works (we overwrite with real player)
        players[call] = this
        
        // Shuffle the list
        val shuffled = enabledItems.shuffled().toMutableList()
        var currentIndex = 0
        
        fun playNext() {
            if (prefs.isShufflePaused) {
                // Wait until resumed
                handler.postDelayed({ 
                    if (players.containsKey(call)) {
                        playNext()
                    }
                }, 100)
                return
            }
            
            if (currentIndex >= shuffled.size) {
                if (prefs.isShuffleLoop) {
                    currentIndex = 0
                    // Re-shuffle for variety when looping
                    shuffled.shuffle()
                } else {
                    // Stop shuffle playback
                    players.remove(call)
                    updateForegroundState()
                    sendBroadcast(Intent("com.rudisec.echocast.AUDIO_STOPPED"))
                    return
                }
            }
            
            val item = shuffled[currentIndex]
            val player = SingleAudioPlayer(
                this,
                object : SingleAudioPlayer.AudioPlayerListener {
                    override fun onPlaybackCompleted() {
                        if (players.containsKey(call)) {
                            currentIndex++
                            playNext()
                        }
                    }
                    
                    override fun onPlaybackFailed(errorMsg: String?) {
                        Log.e(TAG, "Shuffle playback failed: $errorMsg")
                        if (players.containsKey(call)) {
                            currentIndex++
                            playNext()
                        }
                    }
                },
                call,
                item,
                loop = false
            )
            players[call] = player
            updateForegroundState()
            player.start()
        }
        
        playNext()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d(TAG, "onCallAdded: $call")
        call.registerCallback(callback)
        activeCalls[call.toString()] = call
        handleStateChange(call)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved: $call")
        call.unregisterCallback(callback)
        activeCalls.remove(call.toString())
        handleStateChange(call)
    }

    private fun handleStateChange(call: Call) {
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            call.details.state
        } else {
            @Suppress("DEPRECATION")
            call.state
        }

        if (state == Call.STATE_ACTIVE) {
            // Don't auto-start playback, wait for user to press play on individual items
            if (!prefs.isEnabled) {
                Log.v(TAG, "Call audio playback is disabled")
            } else if (!Permissions.haveRequired(this)) {
                Log.v(TAG, "Required permissions have not been granted")
            }
            // Note: We don't auto-start playback anymore - user must press play on each audio
        } else if (state == Call.STATE_DISCONNECTING || state == Call.STATE_DISCONNECTED) {
            val player = players[call]
            when (player) {
                is MultiAudioPlayer -> {
                    player.cancel()
                    players.remove(call)
                    ++pendingExit
                }
                is SingleAudioPlayer -> {
                    player.cancel()
                    players.remove(call)
                    ++pendingExit
                }
                else -> {
                    // Shuffle placeholder or other
                    players.remove(call)
                }
            }
        }
    }

    private fun updateForegroundState() {
        if (players.isEmpty() && pendingExit == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            startForeground(1, createPersistentNotification())
        }
    }

    private fun createPersistentNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val audioCount = playlist.getEnabledItems().size
        val contentText = if (audioCount > 0) {
            "Playing $audioCount audio file(s)"
        } else {
            "No audio files in playlist"
        }

        return Notification.Builder(this, EchoCastApplication.CHANNEL_ID_PERSISTENT).run {
            setContentTitle(getText(R.string.notification_playback_in_progress))
            setContentText(contentText)
            setSmallIcon(R.drawable.ic_launcher_quick_settings)
            setContentIntent(pendingIntent)
            setOngoing(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
            build()
        }
    }

    private fun createPlaybackFailedNotification(errorMsg: String?): Notification =
        Notification.Builder(this, EchoCastApplication.CHANNEL_ID_ALERTS).run {
            val text = errorMsg?.trim() ?: "Unknown error"

            setContentTitle(getString(R.string.notification_playback_failed))
            setContentText(text)
            setSmallIcon(R.drawable.ic_launcher_quick_settings)
            style = Notification.BigTextStyle().bigText(text)
            build()
        }

    private fun onThreadExited() {
        --pendingExit
        updateForegroundState()
    }

    override fun onPlaybackCompleted(thread: MultiAudioPlayer) {
        Log.i(TAG, "Playback completed: ${thread.id}")
        handler.post {
            // Remove from players map
            players.entries.removeAll { it.value == thread }
            onThreadExited()
        }
    }

    override fun onPlaybackFailed(thread: MultiAudioPlayer, errorMsg: String?) {
        Log.w(TAG, "Playback failed: ${thread.id}: $errorMsg")
        handler.post {
            // Remove from players map
            players.entries.removeAll { it.value == thread }
            onThreadExited()

            val notification = createPlaybackFailedNotification(errorMsg)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(failedNotificationId, notification)
            ++failedNotificationId
        }
    }
    
    override fun onPlaybackCompleted() {
        Log.i(TAG, "Single audio playback completed")
        handler.post {
            // Notify adapter to reset play button
            sendBroadcast(Intent("com.rudisec.echocast.AUDIO_STOPPED"))
            onThreadExited()
        }
    }
    
    override fun onPlaybackFailed(errorMsg: String?) {
        Log.w(TAG, "Single audio playback failed: $errorMsg")
        handler.post {
            onThreadExited()

            val notification = createPlaybackFailedNotification(errorMsg)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(failedNotificationId, notification)
            ++failedNotificationId
        }
    }
}
