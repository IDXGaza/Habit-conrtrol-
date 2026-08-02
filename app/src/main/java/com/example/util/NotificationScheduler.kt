package com.example.util

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.data.CustomNotification
import com.example.receiver.NotificationReceiver
import java.util.Calendar

object NotificationScheduler {

    const val CHANNEL_ID = "habit_control_custom_notifications"

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "إشعارات التذكير المخصصة",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "إشعارات التذكيرات المخصصة لضبط العادات والتركيز"
                enableVibration(true)
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun getTimeRemainingText(hour: Int, minute: Int): String {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val diffMillis = target.timeInMillis - now.timeInMillis
        val totalMinutes = (diffMillis / (1000 * 60)).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours > 0 && minutes > 0 -> "$hours ساعة و $minutes دقيقة"
            hours > 0 -> "$hours ساعة"
            minutes > 0 -> "$minutes دقيقة"
            else -> "أقل من دقيقة"
        }
    }

    fun scheduleNotification(context: Context, notification: CustomNotification) {
        ensureNotificationChannel(context)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra("NOTIF_ID", notification.id)
            putExtra("NOTIF_TITLE", notification.title)
            putExtra("NOTIF_MESSAGE", notification.message)
            putExtra("NOTIF_HOUR", notification.hour)
            putExtra("NOTIF_MINUTE", notification.minute)
            putExtra("NOTIF_HAS_AUDIO", notification.hasAudio)
            putExtra("NOTIF_REQUIRE_LISTENING", notification.requireFullListening)
            putExtra("NOTIF_AUDIO_TRACK", notification.audioTrack)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notification.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (!notification.isEnabled) {
            alarmManager.cancel(pendingIntent)
            return
        }

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, notification.hour)
            set(Calendar.MINUTE, notification.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        try {
            // setAlarmClock is guaranteed to deliver exact alarm on all Android OEMs
            val clockInfo = AlarmManager.AlarmClockInfo(calendar.timeInMillis, pendingIntent)
            alarmManager.setAlarmClock(clockInfo, pendingIntent)
        } catch (e: Exception) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                }
            } catch (secEx: SecurityException) {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }
        }
    }

    fun cancelNotification(context: Context, notificationId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotificationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun triggerTestNotification(context: Context, title: String, message: String, hasAudio: Boolean = true, requireFullListening: Boolean = false, audioTrack: String = "MOTIVATION_1") {
        ensureNotificationChannel(context)

        val intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra("NOTIF_ID", (System.currentTimeMillis() % 10000))
            putExtra("NOTIF_TITLE", title)
            putExtra("NOTIF_MESSAGE", message)
            putExtra("NOTIF_HAS_AUDIO", hasAudio)
            putExtra("NOTIF_REQUIRE_LISTENING", requireFullListening)
            putExtra("NOTIF_AUDIO_TRACK", audioTrack)
        }
        context.sendBroadcast(intent)
    }
}

