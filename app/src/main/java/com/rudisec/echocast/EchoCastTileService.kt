package com.rudisec.echocast

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class EchoCastTileService : TileService() {
    private lateinit var prefs: Preferences

    override fun onCreate() {
        super.onCreate()
        prefs = Preferences(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        prefs.isEnabled = !prefs.isEnabled
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = if (prefs.isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.updateTile()
    }
}
