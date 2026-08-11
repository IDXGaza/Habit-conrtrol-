package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.BlockedApp
import com.example.data.CustomNotification
import com.example.util.NotificationScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppInfo(
    val packageName: String,
    val name: String
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val blockedAppDao = database.blockedAppDao()
    private val customNotificationDao = database.customNotificationDao()

    val blockedApps: StateFlow<List<BlockedApp>> = blockedAppDao.getAllBlockedApps()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val customNotifications: StateFlow<List<CustomNotification>> = customNotificationDao.getAllNotifications()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps

    private val deviceLockManager = com.example.data.DeviceLockManager(application)
    private val _deviceLockState = MutableStateFlow(deviceLockManager.getSettings())
    val deviceLockState: StateFlow<com.example.data.DeviceLockState> = _deviceLockState

    init {
        loadInstalledApps()
    }

    fun refreshDeviceLockState() {
        _deviceLockState.value = deviceLockManager.getSettings()
    }

    fun updateDeviceLockMaster(enabled: Boolean) {
        deviceLockManager.updateMasterEnabled(enabled)
        refreshDeviceLockState()
    }

    fun updateDeviceLockDaily(enabled: Boolean, limitMinutes: Int) {
        deviceLockManager.updateDailyLimit(enabled, limitMinutes)
        refreshDeviceLockState()
    }

    fun updateDeviceLockSchedule(enabled: Boolean, startH: Int, startM: Int, endH: Int, endM: Int) {
        deviceLockManager.updateSchedule(enabled, startH, startM, endH, endM)
        refreshDeviceLockState()
    }

    fun updateDeviceLockPeriodic(enabled: Boolean, usageMins: Int, restMins: Int) {
        deviceLockManager.updatePeriodic(enabled, usageMins, restMins)
        refreshDeviceLockState()
    }

    fun saveDeviceLockMedia(uri: android.net.Uri, isAudio: Boolean) {
        deviceLockManager.saveMediaFromUri(uri, isAudio)
        refreshDeviceLockState()
    }

    fun clearDeviceLockMedia(isAudio: Boolean) {
        deviceLockManager.clearMedia(isAudio)
        refreshDeviceLockState()
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            val pm = getApplication<Application>().packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            val apps = resolveInfos.map { info ->
                AppInfo(
                    packageName = info.activityInfo.packageName,
                    name = info.loadLabel(pm).toString()
                )
            }.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
            _installedApps.value = apps
        }
    }

    fun addBlockedApp(app: BlockedApp) {
        viewModelScope.launch {
            blockedAppDao.insertBlockedApp(app)
        }
    }

    fun removeBlockedApp(app: BlockedApp) {
        viewModelScope.launch {
            blockedAppDao.deleteBlockedApp(app)
        }
    }

    fun saveCustomNotification(notification: CustomNotification) {
        viewModelScope.launch {
            val id = customNotificationDao.insertNotification(notification)
            val savedNotif = if (notification.id == 0L) notification.copy(id = id) else notification
            if (savedNotif.isEnabled) {
                NotificationScheduler.scheduleNotification(getApplication(), savedNotif)
            } else {
                NotificationScheduler.cancelNotification(getApplication(), savedNotif.id)
            }
        }
    }

    fun toggleCustomNotification(notification: CustomNotification, isEnabled: Boolean) {
        viewModelScope.launch {
            val updated = notification.copy(isEnabled = isEnabled)
            customNotificationDao.updateNotification(updated)
            if (isEnabled) {
                NotificationScheduler.scheduleNotification(getApplication(), updated)
            } else {
                NotificationScheduler.cancelNotification(getApplication(), updated.id)
            }
        }
    }

    fun deleteCustomNotification(notification: CustomNotification) {
        viewModelScope.launch {
            customNotificationDao.deleteNotification(notification)
            NotificationScheduler.cancelNotification(getApplication(), notification.id)
        }
    }

    fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        var accessibilityEnabled = 0
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                context.applicationContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) {
            // Error
        }
        val textUtils = TextUtils.SimpleStringSplitter(':')

        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                context.applicationContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                textUtils.setString(settingValue)
                while (textUtils.hasNext()) {
                    val accessibilityService = textUtils.next()
                    if (accessibilityService.contains(context.packageName, ignoreCase = true) &&
                        accessibilityService.contains(service.simpleName, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }
}
