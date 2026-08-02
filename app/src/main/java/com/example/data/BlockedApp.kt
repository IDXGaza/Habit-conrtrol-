package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_apps")
data class BlockedApp(
    @PrimaryKey val packageName: String,
    val appName: String,
    val challengeType: String, // "MATH", "TYPE", "WAIT", "BLOCK", "PICTURE"
    val challengeParam: String, // e.g. Math difficulty, phrase to type, wait time, or object to picture
    val allowedTimeMinutes: Int = 5, // time allowed after passing challenge
    val isTimeScheduleEnabled: Boolean = false,
    val startHour: Int = 0,
    val startMinute: Int = 0,
    val endHour: Int = 0,
    val endMinute: Int = 0
) {
    fun isTimeBlocked(currentHour: Int, currentMinute: Int): Boolean {
        if (!isTimeScheduleEnabled) return true // Block always if schedule is disabled
        val currentMinutes = currentHour * 60 + currentMinute
        val startMinutes = startHour * 60 + startMinute
        val endMinutes = endHour * 60 + endMinute
        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes..endMinutes
        } else {
            // crosses midnight
            currentMinutes >= startMinutes || currentMinutes <= endMinutes
        }
    }
}

