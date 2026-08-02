package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_notifications")
data class CustomNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val message: String,
    val hour: Int,
    val minute: Int,
    val isEnabled: Boolean = true,
    val repeatDaily: Boolean = true,
    val hasAudio: Boolean = true,
    val requireFullListening: Boolean = false,
    val audioTrack: String = "MOTIVATION_1"
)
