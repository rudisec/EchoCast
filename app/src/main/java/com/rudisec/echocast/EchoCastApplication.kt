package com.rudisec.echocast

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.android.material.color.DynamicColors

class EchoCastApplication : Application() {
    companion object {
        const val CHANNEL_ID_PERSISTENT = "persistent"
        const val CHANNEL_ID_ALERTS = "alerts"
    }

    override fun onCreate() {
        super.onCreate()

        try {
            // Enable Material You colors
            DynamicColors.applyToActivitiesIfAvailable(this)
        } catch (e: Exception) {
            // Ignore if DynamicColors is not available
            android.util.Log.w("EchoCast", "DynamicColors not available", e)
        }

        createPersistentChannel()
        createAlertsChannel()
    }

    private fun createPersistentChannel() {
        val name = getString(R.string.notification_channel_persistent_name)
        val description = getString(R.string.notification_channel_persistent_desc)
        val channel = NotificationChannel(
            CHANNEL_ID_PERSISTENT, name, NotificationManager.IMPORTANCE_LOW)
        channel.description = description

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createAlertsChannel() {
        val name = getString(R.string.notification_channel_alerts_name)
        val description = getString(R.string.notification_channel_alerts_desc)
        val channel = NotificationChannel(
            CHANNEL_ID_ALERTS, name, NotificationManager.IMPORTANCE_HIGH)
        channel.description = description

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
