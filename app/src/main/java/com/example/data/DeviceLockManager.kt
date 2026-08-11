package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DeviceLockState(
    val isMasterEnabled: Boolean = false,
    val isDailyLimitEnabled: Boolean = false,
    val dailyLimitMinutes: Int = 120,
    val isScheduleEnabled: Boolean = false,
    val scheduleStartHour: Int = 22,
    val scheduleStartMinute: Int = 0,
    val scheduleEndHour: Int = 6,
    val scheduleEndMinute: Int = 0,
    val isPeriodicEnabled: Boolean = false,
    val periodicUsageMinutes: Int = 60,
    val periodicRestMinutes: Int = 10,
    val imagePath: String? = null,
    val audioPath: String? = null,
    val todayUsageSeconds: Long = 0L,
    val periodicSessionSeconds: Long = 0L,
    val periodicRestExpiry: Long = 0L
)

class DeviceLockManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("device_lock_prefs", Context.MODE_PRIVATE)

    fun getSettings(): DeviceLockState {
        checkAndResetDailyUsage()
        return DeviceLockState(
            isMasterEnabled = prefs.getBoolean("master_enabled", false),
            isDailyLimitEnabled = prefs.getBoolean("daily_enabled", false),
            dailyLimitMinutes = prefs.getInt("daily_limit_minutes", 120),
            isScheduleEnabled = prefs.getBoolean("schedule_enabled", false),
            scheduleStartHour = prefs.getInt("schedule_start_hour", 22),
            scheduleStartMinute = prefs.getInt("schedule_start_minute", 0),
            scheduleEndHour = prefs.getInt("schedule_end_hour", 6),
            scheduleEndMinute = prefs.getInt("schedule_end_minute", 0),
            isPeriodicEnabled = prefs.getBoolean("periodic_enabled", false),
            periodicUsageMinutes = prefs.getInt("periodic_usage_minutes", 60),
            periodicRestMinutes = prefs.getInt("periodic_rest_minutes", 10),
            imagePath = prefs.getString("image_path", null),
            audioPath = prefs.getString("audio_path", null),
            todayUsageSeconds = prefs.getLong("today_usage_seconds", 0L),
            periodicSessionSeconds = prefs.getLong("periodic_session_seconds", 0L),
            periodicRestExpiry = prefs.getLong("periodic_rest_expiry", 0L)
        )
    }

    fun updateMasterEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("master_enabled", enabled).apply()
    }

    fun updateDailyLimit(enabled: Boolean, limitMinutes: Int) {
        prefs.edit()
            .putBoolean("daily_enabled", enabled)
            .putInt("daily_limit_minutes", limitMinutes)
            .apply()
    }

    fun updateSchedule(enabled: Boolean, startH: Int, startM: Int, endH: Int, endM: Int) {
        prefs.edit()
            .putBoolean("schedule_enabled", enabled)
            .putInt("schedule_start_hour", startH)
            .putInt("schedule_start_minute", startM)
            .putInt("schedule_end_hour", endH)
            .putInt("schedule_end_minute", endM)
            .apply()
    }

    fun updatePeriodic(enabled: Boolean, usageMins: Int, restMins: Int) {
        prefs.edit()
            .putBoolean("periodic_enabled", enabled)
            .putInt("periodic_usage_minutes", usageMins)
            .putInt("periodic_rest_minutes", restMins)
            .apply()
    }

    fun saveMediaFromUri(uri: Uri, isAudio: Boolean): String? {
        return try {
            val fileName = if (isAudio) "device_lock_audio.mp3" else "device_lock_bg.jpg"
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val outFile = File(context.filesDir, fileName)
            outFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            val path = outFile.absolutePath
            if (isAudio) {
                prefs.edit().putString("audio_path", path).apply()
            } else {
                prefs.edit().putString("image_path", path).apply()
            }
            path
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun clearMedia(isAudio: Boolean) {
        if (isAudio) {
            val path = prefs.getString("audio_path", null)
            if (path != null) {
                File(path).delete()
            }
            prefs.edit().remove("audio_path").apply()
        } else {
            val path = prefs.getString("image_path", null)
            if (path != null) {
                File(path).delete()
            }
            prefs.edit().remove("image_path").apply()
        }
    }

    fun recordUsageSecond() {
        checkAndResetDailyUsage()
        val currentDaily = prefs.getLong("today_usage_seconds", 0L) + 1L
        val currentSession = prefs.getLong("periodic_session_seconds", 0L) + 1L
        prefs.edit()
            .putLong("today_usage_seconds", currentDaily)
            .putLong("periodic_session_seconds", currentSession)
            .apply()
    }

    fun resetPeriodicSession() {
        prefs.edit().putLong("periodic_session_seconds", 0L).apply()
    }

    fun setPeriodicRestExpiry(expiryMs: Long) {
        prefs.edit().putLong("periodic_rest_expiry", expiryMs).apply()
    }

    private fun checkAndResetDailyUsage() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val savedDate = prefs.getString("last_usage_date", "")
        if (savedDate != todayStr) {
            prefs.edit()
                .putString("last_usage_date", todayStr)
                .putLong("today_usage_seconds", 0L)
                .putLong("periodic_session_seconds", 0L)
                .putLong("periodic_rest_expiry", 0L)
                .apply()
        }
    }

    fun isScheduleActiveNow(startH: Int, startM: Int, endH: Int, endM: Int): Boolean {
        val cal = Calendar.getInstance()
        val currentMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMins = startH * 60 + startM
        val endMins = endH * 60 + endM

        return if (startMins <= endMins) {
            currentMins in startMins..endMins
        } else {
            currentMins >= startMins || currentMins <= endMins
        }
    }
}
