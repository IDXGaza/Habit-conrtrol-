package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.BlockedApp
import com.example.data.CustomNotification
import com.example.service.AppBlockerService
import com.example.ui.AppInfo
import com.example.ui.MainViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.util.NotificationScheduler

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MyApplicationTheme {
                    var selectedTab by remember { mutableIntStateOf(0) }
                    
                    val blockedApps by viewModel.blockedApps.collectAsStateWithLifecycle()
                    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
                    val customNotifications by viewModel.customNotifications.collectAsStateWithLifecycle()

                    var showAppSelector by remember { mutableStateOf(false) }
                    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
                    var showNotificationDialog by remember { mutableStateOf(false) }
                    var notificationToEdit by remember { mutableStateOf<CustomNotification?>(null) }

                    // Notification Permission Request for Android 13+
                    val context = LocalContext.current
                    val notifPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        if (!isGranted) {
                            Toast.makeText(context, "يلزم السماح بالإشعارات لإرسال التذكيرات المخصصة", Toast.LENGTH_SHORT).show()
                        }
                    }

                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    }

                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        topBar = {
                            CenterAlignedTopAppBar(
                                title = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Shield,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "Habit Control | تحكم بعادتك",
                                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            )
                        },
                        bottomBar = {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                NavigationBarItem(
                                    selected = selectedTab == 0,
                                    onClick = { selectedTab = 0 },
                                    icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                                    label = { Text("التطبيقات") }
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1 },
                                    icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                                    label = { Text("التذكيرات") }
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 2,
                                    onClick = { selectedTab = 2 },
                                    icon = { Icon(Icons.Default.Shield, contentDescription = null) },
                                    label = { Text("درع الأمان") }
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 3,
                                    onClick = { selectedTab = 3 },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    label = { Text("الحالة") }
                                )
                            }
                        },
                        floatingActionButton = {
                            when (selectedTab) {
                                0 -> {
                                    FloatingActionButton(
                                        onClick = { showAppSelector = true },
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("إضافة حظر", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                1 -> {
                                    FloatingActionButton(
                                        onClick = {
                                            notificationToEdit = null
                                            showNotificationDialog = true
                                        },
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("تذكير جديد", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .padding(innerPadding)
                                .fillMaxSize()
                        ) {
                            // Main Navigation Views
                            when (selectedTab) {
                                0 -> BlockedAppsTab(
                                    blockedApps = blockedApps,
                                    viewModel = viewModel,
                                    onAddClick = { showAppSelector = true }
                                )
                                1 -> CustomNotificationsTab(
                                    notifications = customNotifications,
                                    viewModel = viewModel,
                                    onEdit = { notif ->
                                        notificationToEdit = notif
                                        showNotificationDialog = true
                                    },
                                    onAddClick = {
                                        notificationToEdit = null
                                        showNotificationDialog = true
                                    }
                                )
                                2 -> ShieldTab(viewModel = viewModel)
                                3 -> SettingsAndPermissionsTab(viewModel = viewModel)
                            }
                        }
                    }

                    // Bottom Sheet App Selector
                    if (showAppSelector) {
                        ModalBottomSheet(
                            onDismissRequest = { showAppSelector = false },
                            containerColor = MaterialTheme.colorScheme.surface,
                        ) {
                            AppSelectorScreen(
                                apps = installedApps,
                                onAppSelected = { app ->
                                    selectedApp = app
                                    showAppSelector = false
                                }
                            )
                        }
                    }

                    // Dialog for app challenge configuration
                    selectedApp?.let { app ->
                        ChallengeConfigDialog(
                            appName = app.name,
                            onDismiss = { selectedApp = null },
                            onSave = { challengeType, param, timeLimit, isSchedule, startH, startM, endH, endM ->
                                viewModel.addBlockedApp(
                                    BlockedApp(
                                        packageName = app.packageName,
                                        appName = app.name,
                                        challengeType = challengeType,
                                        challengeParam = param,
                                        allowedTimeMinutes = timeLimit,
                                        isTimeScheduleEnabled = isSchedule,
                                        startHour = startH,
                                        startMinute = startM,
                                        endHour = endH,
                                        endMinute = endM
                                    )
                                )
                                selectedApp = null
                            }
                        )
                    }

                    // Dialog for Notification creation/editing
                    if (showNotificationDialog) {
                        NotificationEditorDialog(
                            initialNotification = notificationToEdit,
                            onDismiss = { showNotificationDialog = false },
                            onSave = { notif ->
                                viewModel.saveCustomNotification(notif)
                                showNotificationDialog = false
                                Toast.makeText(context, "تم حفظ التذكير بنجاح", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BlockedAppsTab(
    blockedApps: List<BlockedApp>,
    viewModel: MainViewModel,
    onAddClick: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var appToEdit by remember { mutableStateOf<BlockedApp?>(null) }

    val filteredApps = blockedApps.filter {
        it.appName.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Stats Overview Banner
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "التطبيقات والمواقع المحظورة",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "إجمالي العناصر المحظورة: ${blockedApps.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "${blockedApps.size}",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        // Search Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            placeholder = { Text("بحث في القائمة المحظورة...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (searchQuery.isBlank()) "لا توجد تطبيقات أو مواقع محظورة حالياً." else "لا توجد نتائج مطابقة للبحث.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "اضغط على زر (إضافة حظر) لتقييد أي تطبيق أو موقع.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filteredApps) { app ->
                    BlockedAppItem(
                        app = app,
                        onEdit = { appToEdit = app },
                        onRemove = { viewModel.removeBlockedApp(app) }
                    )
                }
            }
        }

        appToEdit?.let { app ->
            ChallengeConfigDialog(
                appName = app.appName,
                initialType = app.challengeType,
                initialParam = app.challengeParam,
                initialTimeLimit = app.allowedTimeMinutes,
                initialIsTimeScheduleEnabled = app.isTimeScheduleEnabled,
                initialStartHour = app.startHour,
                initialStartMinute = app.startMinute,
                initialEndHour = app.endHour,
                initialEndMinute = app.endMinute,
                onDismiss = { appToEdit = null },
                onSave = { challengeType, param, timeLimit, isSchedule, startH, startM, endH, endM ->
                    viewModel.addBlockedApp(
                        app.copy(
                            challengeType = challengeType,
                            challengeParam = param,
                            allowedTimeMinutes = timeLimit,
                            isTimeScheduleEnabled = isSchedule,
                            startHour = startH,
                            startMinute = startM,
                            endHour = endH,
                            endMinute = endM
                        )
                    )
                    appToEdit = null
                }
            )
        }
    }
}

@Composable
fun CustomNotificationsTab(
    notifications: List<CustomNotification>,
    viewModel: MainViewModel,
    onEdit: (CustomNotification) -> Unit,
    onAddClick: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Banner
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "التذكيرات والإشعارات المخصصة",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "جدول إشعارات تحفيزية يومية لتغيير العادات والالتزام بالطاعات.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }

        if (notifications.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Alarm,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "لا توجد إشعارات مخصصة حتى الآن",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "قم بإنشاء تذكير مخصص وحدد الوقت المناسب للتنبيه.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onAddClick) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("إضافة تذكير جديد")
                    }
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(notifications) { notif ->
                    NotificationItemCard(
                        notification = notif,
                        onToggle = { isChecked ->
                            viewModel.toggleCustomNotification(notif, isChecked)
                            if (isChecked) {
                                val remaining = NotificationScheduler.getTimeRemainingText(notif.hour, notif.minute)
                                Toast.makeText(context, "تم تفعيل التنبيه! متبقي عليه: $remaining ⏰", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "تم إيقاف التنبيه ⏹️", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onEdit = { onEdit(notif) },
                        onDelete = { viewModel.deleteCustomNotification(notif) },
                        onTestNow = {
                            NotificationScheduler.triggerTestNotification(
                                context,
                                notif.title,
                                notif.message,
                                notif.hasAudio,
                                notif.requireFullListening,
                                notif.audioTrack
                            )
                            Toast.makeText(context, "تم إرسال إشعار تجريبي الآن 🔔", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun NotificationItemCard(
    notification: CustomNotification,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTestNow: () -> Unit
) {
    val formattedTime = remember(notification.hour, notification.minute) {
        val h = notification.hour
        val m = String.format("%02d", notification.minute)
        val period = if (h >= 12) "م" else "ص"
        val displayHour = if (h % 12 == 0) 12 else h % 12
        "$displayHour:$m $period"
    }

    val remainingText = remember(notification.hour, notification.minute, notification.isEnabled) {
        if (notification.isEnabled) {
            NotificationScheduler.getTimeRemainingText(notification.hour, notification.minute)
        } else ""
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (notification.isEnabled) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (notification.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = notification.message,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (notification.isEnabled) "⏰ كم باقي و يرن المنبه: $remainingText" else "غير مفعل",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (notification.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
                Switch(
                    checked = notification.isEnabled,
                    onCheckedChange = onToggle
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (notification.hasAudio) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("🔊 تذكير صوتي", style = MaterialTheme.typography.labelSmall) }
                    )
                }
                if (notification.requireFullListening) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("🔒 إجبار الاستماع كاملًا", style = MaterialTheme.typography.labelSmall) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                            labelColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onTestNow) {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("تجربة الإشعار", style = MaterialTheme.typography.labelMedium)
                }
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "تعديل", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VerticalWheelColumn(
    items: List<String>,
    initialIndex: Int,
    onSelectedIndexChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState = lazyListState)

    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (!lazyListState.isScrollInProgress) {
            val centerIndex = lazyListState.firstVisibleItemIndex.coerceIn(0, items.size - 1)
            onSelectedIndexChanged(centerIndex)
        }
    }

    LazyColumn(
        state = lazyListState,
        flingBehavior = snapFlingBehavior,
        contentPadding = PaddingValues(vertical = 68.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.height(180.dp)
    ) {
        itemsIndexed(items) { index, item ->
            val isSelected = remember(lazyListState.firstVisibleItemIndex) {
                lazyListState.firstVisibleItemIndex == index
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item,
                    style = if (isSelected) {
                        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    }
                )
            }
        }
    }
}

@Composable
fun WheelTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onTimeSelected: (Int, Int) -> Unit
) {
    val initialH12 = if (initialHour % 12 == 0) 12 else initialHour % 12
    var selectedHour12 by remember { mutableIntStateOf(initialH12) }
    var selectedMinute by remember { mutableIntStateOf(initialMinute) }
    var isPm by remember { mutableStateOf(initialHour >= 12) }

    val hours = (1..12).toList()
    val minutes = (0..59).toList()
    val amPmList = listOf("صباحاً (ص)", "مساءً (م)")

    val current24Hour = remember(selectedHour12, isPm) {
        when {
            isPm && selectedHour12 < 12 -> selectedHour12 + 12
            !isPm && selectedHour12 == 12 -> 0
            else -> selectedHour12
        }
    }

    val remainingTimeText = remember(current24Hour, selectedMinute) {
        NotificationScheduler.getTimeRemainingText(current24Hour, selectedMinute)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    onTimeSelected(current24Hour, selectedMinute)
                }
            ) {
                Text("تأكيد الوقت ⏰")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        },
        title = {
            Text(
                "تحديد وقت التنبيه ⏰",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⏳ كم باقي و يرن المنبه: $remainingTimeText",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Highlight capsule for centered selection
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ) {}

                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Hour Wheel
                        VerticalWheelColumn(
                            items = hours.map { String.format("%02d", it) },
                            initialIndex = hours.indexOf(selectedHour12).coerceAtLeast(0),
                            onSelectedIndexChanged = { idx ->
                                if (idx in hours.indices) selectedHour12 = hours[idx]
                            },
                            modifier = Modifier.weight(1f)
                        )

                        Text(":", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))

                        // Minute Wheel
                        VerticalWheelColumn(
                            items = minutes.map { String.format("%02d", it) },
                            initialIndex = selectedMinute.coerceIn(0, 59),
                            onSelectedIndexChanged = { idx ->
                                if (idx in minutes.indices) selectedMinute = minutes[idx]
                            },
                            modifier = Modifier.weight(1f)
                        )

                        // AM/PM Wheel
                        VerticalWheelColumn(
                            items = amPmList,
                            initialIndex = if (isPm) 1 else 0,
                            onSelectedIndexChanged = { idx ->
                                isPm = (idx == 1)
                            },
                            modifier = Modifier.weight(1.3f)
                        )
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationEditorDialog(
    initialNotification: CustomNotification?,
    onDismiss: () -> Unit,
    onSave: (CustomNotification) -> Unit
) {
    val context = LocalContext.current
    var message by remember { mutableStateOf(initialNotification?.message ?: "") }
    var hour by remember { mutableIntStateOf(initialNotification?.hour ?: 8) }
    var minute by remember { mutableIntStateOf(initialNotification?.minute ?: 0) }
    var repeatDaily by remember { mutableStateOf(initialNotification?.repeatDaily ?: true) }
    var hasAudio by remember { mutableStateOf(initialNotification?.hasAudio ?: true) }
    var requireFullListening by remember { mutableStateOf(initialNotification?.requireFullListening ?: false) }
    var selectedAudioTrack by remember { mutableStateOf(initialNotification?.audioTrack ?: "") }
    var showTimePicker by remember { mutableStateOf(false) }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedAudioTrack = copyUriToInternalFile(context, uri, "notif_audio")
        }
    }

    if (showTimePicker) {
        WheelTimePickerDialog(
            initialHour = hour,
            initialMinute = minute,
            onDismiss = { showTimePicker = false },
            onTimeSelected = { selectedH, selectedM ->
                hour = selectedH
                minute = selectedM
                showTimePicker = false
            }
        )
    }

    val presets = listOf(
        "سبحان الله وبحمده، أستغفر الله وأتوب إليه.",
        "حان وقت إغلاق الهاتف والتركيز في عملك أو دراستك!",
        "أرح عينيك وخذ نفساً عميقاً وابتعد عن الهاتف.",
        "لا تُشغل نفسك بالدنيا وتذكر صلاتك ووردك اليومي."
    )

    val quickTimes = listOf(
        "7:00 ص" to (7 to 0),
        "12:30 م" to (12 to 30),
        "5:00 م" to (17 to 0),
        "9:00 م" to (21 to 0),
        "11:00 م" to (23 to 0)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialNotification == null) "إضافة تذكير مخصص" else "تعديل التذكير",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                Text("اختر نصاً جاهزاً أو اكتب نصك الخاص:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 140.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(presets) { preset ->
                        SuggestionChip(
                            onClick = { message = preset },
                            label = { Text(preset, style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("نص الإشعار والتنبيه") },
                    placeholder = { Text("مثال: حان وقت الابتعاد عن الهاتف والبدء بالدراسة") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text("وقت التنبيه ⏰:", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(8.dp))

                // Interactive Clock Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTimePicker = true }
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccessTime,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val period = if (hour >= 12) "مساءً (م)" else "صباحاً (ص)"
                            val displayHour = if (hour % 12 == 0) 12 else hour % 12
                            val formattedMinute = String.format("%02d", minute)
                            Text(
                                text = "$displayHour:$formattedMinute $period",
                                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        val timeRemainingStr = NotificationScheduler.getTimeRemainingText(hour, minute)
                        Text(
                            text = "⏳ كم باقي و يرن المنبه: $timeRemainingStr",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        FilledTonalButton(
                            onClick = { showTimePicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تعديل الوقت 🕐")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text("أوقات سريعة:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickTimes.forEach { (label, hMinutes) ->
                        FilterChip(
                            selected = (hour == hMinutes.first && minute == hMinutes.second),
                            onClick = {
                                hour = hMinutes.first
                                minute = hMinutes.second
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("تكرار الإشعار يومياً", modifier = Modifier.weight(1f))
                    Switch(
                        checked = repeatDaily,
                        onCheckedChange = { repeatDaily = it }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🔊 تشغيل المقطع الصوتي", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                        Text("تشغيل المقطع الصوتي المختار والتنبيه عند الوقت المحدد.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(
                        checked = hasAudio,
                        onCheckedChange = { hasAudio = it }
                    )
                }

                if (hasAudio || requireFullListening) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("المقطع الصوتي للتنبيه (من جهازك):", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedButton(
                        onClick = { audioPickerLauncher.launch("audio/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (selectedAudioTrack.isNotBlank() && (selectedAudioTrack.startsWith("content://") || selectedAudioTrack.startsWith("file://"))) {
                                "تم اختيار مقطع مخصص (اضغط للتغيير) 🎵"
                            } else {
                                "اختيار مقطع صوتي من الجهاز 📁"
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🔒 إجبار الاستماع كاملاً قبل استعمال الهاتف", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.error)
                        Text("تظهر شاشة قفل إجبارية يستمع فيها المستخدم للمقطع حتى 100% قبل فتح الجهاز.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = requireFullListening,
                        onCheckedChange = {
                            requireFullListening = it
                            if (it) hasAudio = true
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (message.isNotBlank()) {
                        val derivedTitle = message.trim().take(30)
                        val notif = CustomNotification(
                            id = initialNotification?.id ?: 0L,
                            title = derivedTitle,
                            message = message.trim(),
                            hour = hour,
                            minute = minute,
                            isEnabled = true,
                            repeatDaily = repeatDaily,
                            hasAudio = hasAudio,
                            requireFullListening = requireFullListening,
                            audioTrack = selectedAudioTrack
                        )
                        val remaining = NotificationScheduler.getTimeRemainingText(hour, minute)
                        Toast.makeText(context, "تم جدولة التنبيه بنجاح! متبقي عليه: $remaining ⏰", Toast.LENGTH_LONG).show()
                        onSave(notif)
                    }
                },
                enabled = message.isNotBlank()
            ) {
                Text("حفظ التجديل")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
fun ShieldTab(viewModel: MainViewModel) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("habit_control_prefs", Context.MODE_PRIVATE) }
    var isAdultEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("adult_shield_enabled", false)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (isAdultEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (isAdultEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "درع المحتوى الأجنبي والإباحي",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = if (isAdultEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isAdultEnabled) "الحماية نشطة ومفعلة" else "الحماية متوقفة",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isAdultEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                    Switch(
                        checked = isAdultEnabled,
                        onCheckedChange = { checked ->
                            isAdultEnabled = checked
                            sharedPrefs.edit().putBoolean("adult_shield_enabled", checked).apply()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "مميزات درع الأمان الذكي:",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(10.dp))

                ShieldFeatureItem(
                    icon = Icons.Default.Memory,
                    title = "فلترة سريعة ومحلية (Local AI Engine)"
                )
                Spacer(modifier = Modifier.height(10.dp))

                ShieldFeatureItem(
                    icon = Icons.Default.AutoAwesome,
                    title = "تحليل الذكاء الاصطناعي السحابي (Gemini AI)"
                )
                Spacer(modifier = Modifier.height(10.dp))

                ShieldFeatureItem(
                    icon = Icons.Default.MenuBook,
                    title = "حظر مواقع المانهوا والمانجا الأجنبية"
                )
            }
        }
    }
}

@Composable
fun ShieldFeatureItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
fun SettingsAndPermissionsTab(viewModel: MainViewModel) {
    PermissionsBanner(viewModel = viewModel)
}

@Composable
fun PermissionsBanner(viewModel: MainViewModel) {
    val context = LocalContext.current
    var isAccessibilityEnabled by remember {
        mutableStateOf(viewModel.isAccessibilityServiceEnabled(context, AppBlockerService::class.java))
    }
    var isOverlayEnabled by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }

    DisposableEffect(Unit) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = viewModel.isAccessibilityServiceEnabled(context, AppBlockerService::class.java)
                isOverlayEnabled = Settings.canDrawOverlays(context)
            }
        }
        val lifecycle = (context as ComponentActivity).lifecycle
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "حالة أذونات وصحة التطبيق",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Accessibility Service Permission Card
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (isAccessibilityEnabled) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isAccessibilityEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isAccessibilityEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "خدمة إمكانية الوصول (Accessibility Service)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (isAccessibilityEnabled) "الخدمة تعمل بنجاح وجاهزة للحظر" else "مطلوبة لرصد التطبيقات والمواقع المحظورة",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (!isAccessibilityEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("تفعيل الخدمة من الإعدادات")
                    }
                }
            }
        }

        // Overlay Permission Card
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (isOverlayEnabled) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isOverlayEnabled) Icons.Default.CheckCircle else Icons.Default.Layers,
                        contentDescription = null,
                        tint = if (isOverlayEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "الظهور فوق التطبيقات الأخرى (Overlay Permission)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (isOverlayEnabled) "الإذن مفعل بنجاح" else "مطلوب لإظهار شاشة التحدي فوق التطبيقات",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (!isOverlayEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }) {
                        Text("تفعيل إذن الظهور")
                    }
                }
            }
        }
    }
}

@Composable
fun BlockedAppItem(app: BlockedApp, onEdit: () -> Unit, onRemove: () -> Unit) {
    val challengeTitle = when (app.challengeType) {
        "MATH" -> "مسألة رياضية 🧮"
        "TYPE" -> "كتابة نص تحفيزي ✍️"
        "WAIT" -> "مؤقت انتظار وتدبر ⏳"
        "PICTURE" -> "التقاط صورة لكائن 📸"
        else -> "حظر تام 🚫"
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                val scheduleText = if (app.isTimeScheduleEnabled) {
                    val startFormatted = String.format("%02d:%02d", app.startHour, app.startMinute)
                    val endFormatted = String.format("%02d:%02d", app.endHour, app.endMinute)
                    " • المواعيد: $startFormatted - $endFormatted"
                } else {
                    ""
                }
                Text(
                    text = "$challengeTitle • المهلة: ${app.allowedTimeMinutes} دقيقة$scheduleText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "تعديل",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "إزالة",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun AppSelectorScreen(apps: List<AppInfo>, onAppSelected: (AppInfo) -> Unit) {
    var customUrl by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxHeight(0.85f)) {
        Text(
            "اختر تطبيقاً أو أدخل موقعاً إلكترونياً",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(16.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = customUrl,
                onValueChange = { customUrl = it },
                label = { Text("رابط موقع مخصص (مثل example.com)") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (customUrl.isNotBlank()) {
                        val cleaned = customUrl.trim()
                            .lowercase()
                            .removePrefix("http://")
                            .removePrefix("https://")
                            .removePrefix("www.")
                            .trimEnd('/')
                        if (cleaned.isNotBlank()) {
                            onAppSelected(
                                AppInfo(
                                    packageName = "website:$cleaned",
                                    name = "موقع: $cleaned"
                                )
                            )
                        }
                    }
                },
                enabled = customUrl.isNotBlank()
            ) {
                Text("إضافة")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        LazyColumn {
            items(apps) { app ->
                ListItem(
                    headlineContent = { Text(app.name, style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = { Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) },
                    leadingContent = { Icon(Icons.Default.Android, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { onAppSelected(app) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChallengeConfigDialog(
    appName: String,
    initialType: String = "MATH",
    initialParam: String = "EASY",
    initialTimeLimit: Int = 5,
    initialIsTimeScheduleEnabled: Boolean = false,
    initialStartHour: Int = 0,
    initialStartMinute: Int = 0,
    initialEndHour: Int = 0,
    initialEndMinute: Int = 0,
    onDismiss: () -> Unit,
    onSave: (String, String, Int, Boolean, Int, Int, Int, Int) -> Unit
) {
    val context = LocalContext.current
    var selectedType by remember { mutableStateOf(initialType) }
    var param by remember { mutableStateOf(initialParam) }
    var allowedTimeStr by remember { mutableStateOf(initialTimeLimit.toString()) }

    var isTimeScheduleEnabled by remember { mutableStateOf(initialIsTimeScheduleEnabled) }
    var startHourStr by remember { mutableStateOf(if (initialIsTimeScheduleEnabled) String.format("%02d", initialStartHour) else "") }
    var startMinuteStr by remember { mutableStateOf(if (initialIsTimeScheduleEnabled) String.format("%02d", initialStartMinute) else "") }
    var endHourStr by remember { mutableStateOf(if (initialIsTimeScheduleEnabled) String.format("%02d", initialEndHour) else "") }
    var endMinuteStr by remember { mutableStateOf(if (initialIsTimeScheduleEnabled) String.format("%02d", initialEndMinute) else "") }

    var waitSeconds by remember {
        mutableStateOf(
            if (initialType == "WAIT") {
                initialParam.split(":::").getOrNull(0) ?: "30"
            } else {
                "30"
            }
        )
    }
    var waitMessage by remember {
        mutableStateOf(
            if (initialType == "WAIT") {
                initialParam.split(":::").getOrNull(1) ?: ""
            } else {
                ""
            }
        )
    }
    var waitImageFile by remember {
        mutableStateOf(
            if (initialType == "WAIT") {
                initialParam.split(":::").getOrNull(2) ?: ""
            } else {
                ""
            }
        )
    }

    var audioTrackUri by remember {
        mutableStateOf(
            if (initialType == "AUDIO") {
                initialParam.split(":::").getOrNull(2) ?: ""
            } else {
                ""
            }
        )
    }

    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            audioTrackUri = copyUriToInternalFile(context, uri, "challenge_audio")
        }
    }

    val typeOptions = listOf(
        "MATH" to "مسألة رياضية 🧮",
        "TYPE" to "كتابة نص تحفيزي ✍️",
        "WAIT" to "مؤقت انتظار وتدبر ⏳",
        "AUDIO" to "استماع صوتي إجباري 🎧",
        "BLOCK" to "حظر تام 🚫",
        "PICTURE" to "التقاط صورة لكائن 📸"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إعداد تحدي الحظر") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("التطبيق أو الموقع: $appName", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(16.dp))

                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = typeOptions.find { it.first == selectedType }?.second ?: selectedType,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("نوع التحدي") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        typeOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.second) },
                                onClick = {
                                    selectedType = option.first
                                    expanded = false
                                    param = when(option.first) {
                                        "MATH" -> "EASY"
                                        "TYPE" -> "أنا أحترم وقتي وأركز في أهدافي"
                                        "WAIT" -> "30"
                                        "AUDIO" -> "تنبيه صوتي إجباري ::: يجب الاستماع للتنبيه كاملاً لإلغاء القفل."
                                        "PICTURE" -> "Cup"
                                        else -> ""
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (selectedType) {
                    "MATH" -> {
                        Text("مستوى الصعوبة:")
                        Row {
                            listOf("EASY" to "سهل", "MEDIUM" to "متوسط", "HARD" to "صعب").forEach { diff ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = param == diff.first, onClick = { param = diff.first })
                                    Text(diff.second)
                                }
                            }
                        }
                    }
                    "TYPE" -> {
                        OutlinedTextField(
                            value = param,
                            onValueChange = { param = it },
                            label = { Text("الجملة المطلوب كتابتها بالضبط") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    "WAIT" -> {
                        OutlinedTextField(
                            value = waitSeconds,
                            onValueChange = { waitSeconds = it },
                            label = { Text("مدة الانتظار (بالثواني)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = waitMessage,
                            onValueChange = { waitMessage = it },
                            label = { Text("رسالة تذكيرية أثناء الانتظار") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    "PICTURE" -> {
                        OutlinedTextField(
                            value = param,
                            onValueChange = { param = it },
                            label = { Text("اسم الكائن المطلوب تصويره (مثل Cup)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    "AUDIO" -> {
                        Column {
                            Text("اختيار المقطع الصوتي للتحدي من جهازك 🎵:", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { audioLauncher.launch("audio/*") },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (audioTrackUri.isNotBlank() && (audioTrackUri.startsWith("content://") || audioTrackUri.startsWith("file://"))) {
                                        "تم اختيار مقطع مخصص (اضغط للتغيير) 🎵"
                                    } else {
                                        "اختيار مقطع صوتي من الجهاز 📁"
                                    }
                                )
                            }
                        }
                    }
                    "BLOCK" -> {
                        Text("سيتم حظر التطبيق تماماً بدون إمكانية تخطي.")
                    }
                }

                if (selectedType != "BLOCK") {
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = allowedTimeStr,
                        onValueChange = { allowedTimeStr = it },
                        label = { Text("المهلة المسموحة بعد فتح الحظر (بالدقائق)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "تقييد الحظر بأوقات محددة يومياً",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = isTimeScheduleEnabled,
                        onCheckedChange = { isTimeScheduleEnabled = it }
                    )
                }

                if (isTimeScheduleEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("وقت البداية", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedTextField(
                                    value = startHourStr,
                                    onValueChange = { if (it.isEmpty() || (it.toIntOrNull() in 0..23)) startHourStr = it },
                                    label = { Text("س") },
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = startMinuteStr,
                                    onValueChange = { if (it.isEmpty() || (it.toIntOrNull() in 0..59)) startMinuteStr = it },
                                    label = { Text("د") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text("وقت النهاية", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedTextField(
                                    value = endHourStr,
                                    onValueChange = { if (it.isEmpty() || (it.toIntOrNull() in 0..23)) endHourStr = it },
                                    label = { Text("س") },
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = endMinuteStr,
                                    onValueChange = { if (it.isEmpty() || (it.toIntOrNull() in 0..59)) endMinuteStr = it },
                                    label = { Text("د") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val time = allowedTimeStr.toIntOrNull() ?: 5
                val finalParam = when (selectedType) {
                    "WAIT" -> "$waitSeconds:::$waitMessage:::$waitImageFile"
                    "AUDIO" -> "تنبيه صوتي إجباري ::: يجب الاستماع للمقطع الصوتي كاملاً لإلغاء القفل ::: $audioTrackUri"
                    else -> param
                }
                val startHour = startHourStr.toIntOrNull() ?: 0
                val startMinute = startMinuteStr.toIntOrNull() ?: 0
                val endHour = endHourStr.toIntOrNull() ?: 0
                val endMinute = endMinuteStr.toIntOrNull() ?: 0

                onSave(selectedType, finalParam, time, isTimeScheduleEnabled, startHour, startMinute, endHour, endMinute)
            }) {
                Text("حفظ التغييرات")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

fun copyUriToInternalFile(context: android.content.Context, uri: Uri, prefix: String): String {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return uri.toString()
        val file = java.io.File(context.filesDir, "${prefix}_${System.currentTimeMillis()}.mp3")
        file.outputStream().use { output ->
            inputStream.copyTo(output)
        }
        Uri.fromFile(file).toString()
    } catch (e: Exception) {
        e.printStackTrace()
        uri.toString()
    }
}
