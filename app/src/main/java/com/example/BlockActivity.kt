package com.example

import android.content.Intent
import android.graphics.Bitmap
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.service.AppBlockerService
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import android.util.Base64

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import java.util.Locale

class BlockActivity : ComponentActivity() {
    private var currentIntentState by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        currentIntentState = intent

        setContent {
            val currentIntent = currentIntentState ?: intent
            val packageName = currentIntent.getStringExtra("PACKAGE_NAME") ?: ""
            val challengeType = currentIntent.getStringExtra("CHALLENGE_TYPE") ?: "BLOCK"
            val challengeParam = currentIntent.getStringExtra("CHALLENGE_PARAM") ?: ""
            val allowedTimeMinutes = currentIntent.getIntExtra("ALLOWED_TIME_MINUTES", 5)

            val lockReason = currentIntent.getStringExtra("LOCK_REASON") ?: "قفل الجوال بالكامل"
            val restExpiry = currentIntent.getLongExtra("REST_EXPIRY", 0L)
            val imagePath = currentIntent.getStringExtra("IMAGE_PATH")
            val audioPath = currentIntent.getStringExtra("AUDIO_PATH")
            val isPreview = currentIntent.getBooleanExtra("IS_PREVIEW", false)

            MyApplicationTheme {
                if (challengeType == "DEVICE_LOCK" || packageName == "device_lock_total") {
                    DeviceLockFullOverlay(
                        reason = lockReason,
                        restExpiry = restExpiry,
                        imagePath = imagePath,
                        audioPath = audioPath,
                        isPreview = isPreview,
                        onFinish = { finish() }
                    )
                } else {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        BlockScreen(
                            packageName = packageName,
                            challengeType = challengeType,
                            challengeParam = challengeParam,
                            onUnlock = {
                                AppBlockerService.unlockApp(packageName, allowedTimeMinutes * 60 * 1000L)
                                finish()
                            },
                            onGoHome = {
                                if (packageName == "adult_content_blocked" || packageName.startsWith("website:")) {
                                    AppBlockerService.unlockApp("adult_content_blocked", 5_000L)
                                    if (packageName.startsWith("website:")) {
                                        AppBlockerService.unlockApp(packageName, 5_000L)
                                    }
                                }
                                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                    addCategory(Intent.CATEGORY_HOME)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                startActivity(homeIntent)
                                finish()
                            },
                            onFinish = {
                                if (packageName == "adult_content_blocked" || packageName.startsWith("website:")) {
                                    AppBlockerService.unlockApp("adult_content_blocked", 5_000L)
                                    if (packageName.startsWith("website:")) {
                                        AppBlockerService.unlockApp(packageName, 5_000L)
                                    }
                                }
                                finish()
                            },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentIntentState = intent
    }
}

@Composable
fun BlockScreen(
    packageName: String,
    challengeType: String,
    challengeParam: String,
    onUnlock: () -> Unit,
    onGoHome: () -> Unit,
    onFinish: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler { onFinish() }

    val isAdultBlock = packageName == "adult_content_blocked"

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (isAdultBlock) Icons.Default.Shield else Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = if (isAdultBlock) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (isAdultBlock) "تم حظر محتوى غير لائق" else "Access Blocked",
            style = MaterialTheme.typography.headlineMedium,
            color = if (isAdultBlock) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (isAdultBlock) "درع الحماية يحافظ على نقاء يومك 💪✨" else "Complete the challenge to continue",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isAdultBlock) {
                    Text(
                        text = "«إن السمع والبصر والفؤاد كل أولئك كان عنه مسؤولا»\n\nلقد قمنا بحجب هذا المحتوى تلقائيًا لمساعدتك على الالتزام وحماية عاداتك من المواد غير اللائقة.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(onClick = onGoHome) {
                            Text("الرئيسية")
                        }
                        OutlinedButton(onClick = onFinish) {
                            Text("الرجوع للصفحة السابقة")
                        }
                    }
                } else {
                    when (challengeType) {
                        "MATH" -> MathChallenge(challengeParam, onUnlock)
                        "TYPE" -> TypeChallenge(challengeParam, onUnlock)
                        "WAIT" -> WaitChallenge(challengeParam, onUnlock)
                        "PICTURE" -> PictureChallenge(challengeParam, onUnlock)
                        "AUDIO" -> AudioChallenge(challengeParam, onUnlock)
                        else -> BlockChallenge(onGoHome)
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        TextButton(onClick = onGoHome) {
            Text(if (isAdultBlock) "إغلاق التطبيق" else "Cancel and Go Home")
        }
    }
}

@Composable
fun MathChallenge(difficulty: String, onUnlock: () -> Unit) {
    val (num1, num2) = remember {
        val max = if (difficulty == "HARD") 100 else if (difficulty == "MEDIUM") 50 else 20
        (1..max).random() to (1..max).random()
    }
    var answer by remember { mutableStateOf("") }
    
    Text("Solve the equation to continue:", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(16.dp))
    Text("$num1 + $num2 = ?", style = MaterialTheme.typography.headlineMedium)
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedTextField(
        value = answer,
        onValueChange = { answer = it },
        label = { Text("Answer") },
        singleLine = true
    )
    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick = { if (answer.trim() == (num1 + num2).toString()) onUnlock() },
        enabled = answer.isNotBlank()
    ) {
        Text("Unlock")
    }
}

@Composable
fun TypeChallenge(phrase: String, onUnlock: () -> Unit) {
    val targetPhrase = if (phrase.isBlank()) "I am stronger than my habits." else phrase
    var typed by remember { mutableStateOf("") }
    
    Text("Type the following phrase exactly to continue:", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(16.dp))
    Text("\"$targetPhrase\"", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        label = { Text("Type here") }
    )
    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick = { if (typed.trim() == targetPhrase.trim()) onUnlock() },
        enabled = typed.isNotBlank()
    ) {
        Text("Unlock")
    }
}

@Composable
fun WaitChallenge(challengeParam: String, onUnlock: () -> Unit) {
    val parts = challengeParam.split(":::")
    val secondsStr = parts.getOrNull(0) ?: ""
    val seconds = secondsStr.toIntOrNull() ?: 30
    val customMessage = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
    val imageFile = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
    
    var remaining by remember { mutableIntStateOf(seconds) }
    
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1000)
            remaining--
        }
    }
    
    val context = LocalContext.current
    var bitmapState by remember(imageFile) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }

    LaunchedEffect(imageFile) {
        if (imageFile != null) {
            try {
                val file = context.getFileStreamPath(imageFile)
                if (file.exists()) {
                    bitmapState = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    Text("Wait to unlock...", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    
    if (customMessage != null) {
        Text(
            text = customMessage,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
    
    bitmapState?.let { b ->
        androidx.compose.foundation.Image(
            bitmap = b.asImageBitmap(),
            contentDescription = "Custom wait challenge illustration",
            modifier = Modifier
                .size(150.dp)
                .padding(bottom = 16.dp)
        )
    }

    Text("$remaining seconds", style = MaterialTheme.typography.headlineMedium)
    Spacer(modifier = Modifier.height(16.dp))
    Button(onClick = onUnlock, enabled = remaining <= 0) {
        Text(if (remaining > 0) "Please wait" else "Unlock")
    }
}

@Composable
fun PictureChallenge(targetObject: String, onUnlock: () -> Unit) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b ->
        bitmap = b
        if (b != null) {
            verifying = true
            resultText = "Verifying image with AI..."
            scope.launch {
                val success = verifyImage(b, targetObject)
                verifying = false
                if (success) {
                    onUnlock()
                } else {
                    resultText = "This doesn't look like '$targetObject'. Try again."
                }
            }
        }
    }

    Text("Take a picture of:", style = MaterialTheme.typography.titleMedium)
    Text(targetObject, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(modifier = Modifier.height(16.dp))
    
    bitmap?.let {
        Image(bitmap = it.asImageBitmap(), contentDescription = "Captured image", modifier = Modifier.size(150.dp))
        Spacer(modifier = Modifier.height(16.dp))
    }
    
    if (resultText.isNotEmpty()) {
        Text(resultText, color = if (verifying) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(16.dp))
    }
    
    Button(onClick = { launcher.launch(null) }, enabled = !verifying) {
        Text("Open Camera")
    }
}

@Composable
fun BlockChallenge(onGoHome: () -> Unit) {
    Text(
        text = "تم حظر التطبيق 🚫\n\nلقد استنفذت وقت الاستخدام المسموح به لهذا التطبيق.",
        style = MaterialTheme.typography.titleMedium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
    Spacer(modifier = Modifier.height(20.dp))
    Button(onClick = onGoHome) {
        Text("العودة للشاشة الرئيسية")
    }
}

suspend fun verifyImage(bitmap: Bitmap, targetObject: String): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                // If API key is missing, mock success for development purposes.
                return@withContext true
            }

            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val base64Image = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            val jsonBody = """
            {
              "contents": [
                {
                  "parts": [
                    {"text": "Is this a picture of $targetObject? Answer only YES or NO."},
                    {
                      "inlineData": {
                        "mimeType": "image/jpeg",
                        "data": "$base64Image"
                      }
                    }
                  ]
                }
              ]
            }
            """.trimIndent()

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val client = OkHttpClient()
            val response = client.newCall(request).execute()
            val responseString = response.body?.string() ?: ""
            
            if (response.isSuccessful) {
                val json = JSONObject(responseString)
                val text = json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                return@withContext text.trim().uppercase().contains("YES")
            }
            return@withContext false
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }
}

@Composable
fun AudioChallenge(challengeParam: String, onUnlock: () -> Unit) {
    val context = LocalContext.current
    val parts = challengeParam.split(":::")
    val titleText = parts.getOrNull(0)?.trim()?.ifBlank { "تنبيه صوتي إجباري" } ?: "تنبيه صوتي إجباري"
    val messageText = parts.getOrNull(1)?.trim()?.ifBlank { "يجب الاستماع إلى المقطع الصوتي كاملاً لإلغاء القفل." }
        ?: "يجب الاستماع إلى المقطع الصوتي كاملاً لإلغاء القفل."
    val trackKey = parts.getOrNull(2)?.trim() ?: ""

    var isPlaying by remember { mutableStateOf(false) }
    var isFinished by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    val trackTitle = if (trackKey.startsWith("content://") || trackKey.startsWith("file://") || trackKey.contains("/")) {
        "🎵 مقطع صوتي مخصص من الجهاز"
    } else {
        "🔔 نغمة التنبيه الرئيسية"
    }

    LaunchedEffect(trackKey) {
        isPlaying = true
        var mediaPlayer: android.media.MediaPlayer? = null
        var ringtonePlayer: Ringtone? = null
        var totalDuration = 8000L

        var playedSuccessfully = false

        if (trackKey.isNotBlank() && (trackKey.startsWith("content://") || trackKey.startsWith("file://") || trackKey.contains("/"))) {
            try {
                val targetUri = if (trackKey.startsWith("content://")) {
                    copyUriToCache(context, Uri.parse(trackKey)) ?: Uri.parse(trackKey)
                } else if (trackKey.startsWith("file://")) {
                    Uri.parse(trackKey)
                } else {
                    Uri.fromFile(java.io.File(trackKey))
                }

                mediaPlayer = android.media.MediaPlayer().apply {
                    setDataSource(context, targetUri)
                    prepare()
                    start()
                }
                totalDuration = mediaPlayer.duration.toLong().coerceAtLeast(3000L)
                playedSuccessfully = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (!playedSuccessfully) {
            try {
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ringtonePlayer = RingtoneManager.getRingtone(context, alarmUri)
                ringtonePlayer?.play()
                totalDuration = 8000L
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try {
            val startTime = System.currentTimeMillis()
            while (progress < 1f) {
                delay(100)
                val elapsed = System.currentTimeMillis() - startTime
                progress = (elapsed.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
            }
        } catch (_: Exception) {
            progress = 1f
        } finally {
            try {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer.stop()
                }
                mediaPlayer?.release()
            } catch (_: Exception) {}
            try {
                ringtonePlayer?.stop()
            } catch (_: Exception) {}
            isPlaying = false
            isFinished = true
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.VolumeUp else Icons.Default.GraphicEq,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = if (isFinished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
        )
        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "مقطع صوتي إجباري 🎧",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = if (isFinished) "تم الانتهاء من استماع المقطع الصوتي كاملاً ✅" else "استمع للمقطع الصوتي حتى 100% لفتح القفل",
            style = MaterialTheme.typography.bodySmall,
            color = if (isFinished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Sound Player Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = trackTitle,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = messageText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))
                // Animated Waveform Visualizer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    val barHeights = listOf(24, 40, 18, 48, 30, 42, 20, 36, 50, 22, 38, 28)
                    barHeights.forEachIndexed { i, h ->
                        val animatedHeight = if (isPlaying) {
                            val pulse = ((System.currentTimeMillis() / (100 + i * 20)) % 30).toInt()
                            (h + pulse).coerceIn(10, 55).dp
                        } else {
                            (h / 2).dp
                        }
                        Surface(
                            modifier = Modifier
                                .width(6.dp)
                                .height(animatedHeight),
                            shape = RoundedCornerShape(3.dp),
                            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        ) {}
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "نسبة الاستماع: ${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    progress = 0f
                    isFinished = false
                    isPlaying = true
                }
            ) {
                Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("إعادة التشغيل")
            }

            Button(
                onClick = onUnlock,
                enabled = isFinished || progress >= 0.99f
            ) {
                Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("إلغاء القفل ومتابعة")
            }
        }
    }
}

fun copyUriToCache(context: android.content.Context, uri: Uri): Uri? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val cacheFile = java.io.File(context.cacheDir, "cached_audio_${System.currentTimeMillis()}.mp3")
        cacheFile.outputStream().use { output ->
            inputStream.copyTo(output)
        }
        Uri.fromFile(cacheFile)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

@Composable
fun DeviceLockFullOverlay(
    reason: String,
    restExpiry: Long,
    imagePath: String?,
    audioPath: String?,
    isPreview: Boolean,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    BackHandler { onFinish() }

    // Background Image loading
    var bgBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(imagePath) {
        if (!imagePath.isNullOrEmpty()) {
            val imgFile = java.io.File(imagePath)
            if (imgFile.exists()) {
                withContext(Dispatchers.IO) {
                    try {
                        bgBitmap = android.graphics.BitmapFactory.decodeFile(imgFile.absolutePath)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    // Audio playback in loop
    DisposableEffect(audioPath) {
        var mediaPlayer: android.media.MediaPlayer? = null
        if (!audioPath.isNullOrEmpty()) {
            val audioFile = java.io.File(audioPath)
            if (audioFile.exists()) {
                try {
                    mediaPlayer = android.media.MediaPlayer().apply {
                        setDataSource(audioFile.absolutePath)
                        isLooping = true
                        prepare()
                        start()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        onDispose {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (_: Exception) {}
        }
    }

    // Countdown Timer for Rest Break
    var remainingSeconds by remember {
        mutableLongStateOf(
            if (restExpiry > System.currentTimeMillis()) (restExpiry - System.currentTimeMillis()) / 1000 else 0L
        )
    }

    LaunchedEffect(restExpiry) {
        if (restExpiry > System.currentTimeMillis()) {
            while (true) {
                val diff = (restExpiry - System.currentTimeMillis()) / 1000
                remainingSeconds = maxOf(0L, diff)
                if (diff <= 0) {
                    onFinish()
                    break
                }
                delay(1000)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Image Background or Dark Fallback
        if (bgBitmap != null) {
            Image(
                bitmap = bgBitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF0F172A),
                                Color(0xFF1E293B),
                                Color(0xFF020617)
                            )
                        )
                    )
            )
        }

        // 2. Dark Overlay Scrim for high contrast legibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
        )

        // 3. Foreground Content Card
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                modifier = Modifier.size(96.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PhonelinkLock,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "تم قفل الجوال بالكامل",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = reason,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )

            if (restExpiry > 0L && remainingSeconds > 0) {
                Spacer(modifier = Modifier.height(32.dp))

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "الوقت المتبقي للاستراحة",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val mins = remainingSeconds / 60
                        val secs = remainingSeconds % 60
                        val formattedTime = String.format(Locale.US, "%02d:%02d", mins, secs)
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            if (isPreview) {
                Button(
                    onClick = onFinish,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("إغلاق المعاينة", fontWeight = FontWeight.Bold)
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(homeIntent)
                            onFinish()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.6f))
                    ) {
                        Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("الرئيسية")
                    }

                    Button(
                        onClick = {
                            val dialIntent = Intent(Intent.ACTION_DIAL)
                            context.startActivity(dialIntent)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("طوارئ")
                    }
                }
            }
        }
    }
}
