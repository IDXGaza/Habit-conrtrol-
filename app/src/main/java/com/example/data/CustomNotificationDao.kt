package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomNotificationDao {
    @Query("SELECT * FROM custom_notifications ORDER BY hour ASC, minute ASC")
    fun getAllNotifications(): Flow<List<CustomNotification>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: CustomNotification): Long

    @Update
    suspend fun updateNotification(notification: CustomNotification)

    @Delete
    suspend fun deleteNotification(notification: CustomNotification)

    @Query("SELECT * FROM custom_notifications WHERE isEnabled = 1")
    suspend fun getEnabledNotifications(): List<CustomNotification>
}
