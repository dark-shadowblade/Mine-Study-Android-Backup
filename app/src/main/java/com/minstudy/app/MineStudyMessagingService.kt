package com.minstudy.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MineStudyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        subscribeToUpdateTopic()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val versionCode = message.data["versionCode"]?.toLongOrNull() ?: return
        val versionName = message.data["versionName"] ?: "1.0.$versionCode"
        val installedCode = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (_: Exception) { return }
        if (versionCode <= installedCode) return
        val prefs = getSharedPreferences("mine_study_updates", Context.MODE_PRIVATE)
        if (prefs.getLong("push_notified_version", -1L) == versionCode) return
        if (showUpdateNotification(versionName)) {
            prefs.edit().putLong("push_notified_version", versionCode).apply()
        }
    }

    private fun subscribeToUpdateTopic() {
        com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic("mine_study_updates")
    }

    private fun showUpdateNotification(versionName: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!manager.areNotificationsEnabled()) return false
        val channelId = "app_updates"
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(channelId, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Notifications when a Mine Study update is available" }
        )
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_update_settings", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pending = PendingIntent.getActivity(
            this, 7301, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("Mine Study update available")
            .setContentText("Version $versionName is ready. Open the app to update.")
            .setAutoCancel(true).setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
        return try { manager.notify(7301, notification); true } catch (_: SecurityException) { false }
    }

    companion object {
        fun subscribe(context: Context) {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic("mine_study_updates")
        }
    }
}
