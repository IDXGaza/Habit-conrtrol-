package com.example.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.util.NotificationScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val notifications = db.customNotificationDao().getEnabledNotifications()
                    for (notif in notifications) {
                        NotificationScheduler.scheduleNotification(context, notif)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }

        val notifId = intent.getLongExtra("NOTIF_ID", 0L).toInt()
        val title = intent.getStringExtra("NOTIF_TITLE") ?: "تذكير Habit Control"
        val message = intent.getStringExtra("NOTIF_MESSAGE") ?: "لا تنسَ أهدافك اليومية وطاعتك!"
        val requireListening = intent.getBooleanExtra("NOTIF_REQUIRE_LISTENING", false)
        val hasAudio = intent.getBooleanExtra("NOTIF_HAS_AUDIO", false)
        val audioTrack = intent.getStringExtra("NOTIF_AUDIO_TRACK") ?: "MOTIVATION_1"

        showNotification(context, notifId, title, message)

        if (requireListening) {
            val blockIntent = Intent(context, com.example.BlockActivity::class.java).apply {
                putExtra("PACKAGE_NAME", "notification_lock_$notifId")
                putExtra("CHALLENGE_TYPE", "AUDIO")
                putExtra("CHALLENGE_PARAM", "$title ::: $message ::: $audioTrack")
                putExtra("ALLOWED_TIME_MINUTES", 60)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(blockIntent)
        }

        val hour = intent.getIntExtra("NOTIF_HOUR", -1)
        val minute = intent.getIntExtra("NOTIF_MINUTE", -1)
        if (hour != -1 && minute != -1) {
            val dbNotifId = intent.getLongExtra("NOTIF_ID", 0L)
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val notifications = db.customNotificationDao().getEnabledNotifications()
                    val target = notifications.find { it.id == dbNotifId }
                    if (target != null && target.isEnabled) {
                        NotificationScheduler.scheduleNotification(context, target)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun showNotification(context: Context, id: Int, title: String, message: String) {
        val channelId = "habit_control_custom_notifications"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "إشعارات التذكير المخصصة",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "إشعارات التذكيرات المخصصة لضبط العادات والتركيز"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        notificationManager.notify(id, builder.build())
    }
}
