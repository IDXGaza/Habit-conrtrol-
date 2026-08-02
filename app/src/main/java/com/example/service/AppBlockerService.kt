package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.BlockActivity
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray

class AppBlockerService : AccessibilityService() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var database: AppDatabase
    
    // Store temporarily unlocked apps in memory so we don't block them immediately again.
    // Map of packageName to expiry timestamp (Long).
    companion object {
        val unlockedApps = mutableMapOf<String, Long>()
        val usedUpApps = mutableSetOf<String>()
        
        fun unlockApp(packageName: String, durationMillis: Long) {
            unlockedApps[packageName] = System.currentTimeMillis() + durationMillis
            usedUpApps.remove(packageName)
        }
    }

    @Volatile
    private var blockedAppsCache: List<com.example.data.BlockedApp> = emptyList()

    // Floating overlay window management
    private var windowManager: android.view.WindowManager? = null
    private var timerView: android.view.View? = null
    private var currentTimerKey: String? = null
    private var currentTimerExpiry: Long = 0L
    private var activeBrowserPackage: String? = null
    
    @Volatile
    private var isAdultShieldEnabled: Boolean = false
    private val classificationCache = mutableMapOf<String, Boolean>()
    private var analysisJob: Job? = null

    private var adultBlockJob: Job? = null
    private var isKeywordBlockActive = false
    private var isGeminiBlockActive = false
    private var lastGeminiExplicitText = ""
    private var softLockOverlayView: android.view.View? = null
    private var currentToast: android.widget.Toast? = null
    
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateTimerUi()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        database = AppDatabase.getDatabase(applicationContext)
        serviceScope.launch {
            val prefs = applicationContext.getSharedPreferences("habit_control_prefs", Context.MODE_PRIVATE)
            isAdultShieldEnabled = prefs.getBoolean("adult_shield_enabled", false)
            
            database.blockedAppDao().getAllBlockedApps().collect {
                blockedAppsCache = it
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) return // Don't block ourselves

        // 1. Refresh adult content shield state on window state changes
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val prefs = applicationContext.getSharedPreferences("habit_control_prefs", Context.MODE_PRIVATE)
            isAdultShieldEnabled = prefs.getBoolean("adult_shield_enabled", false)
        }

        // 2. Perform Adult Content Shield analysis if enabled
        if (isAdultShieldEnabled) {
            val isSystemApp = packageName == "com.android.systemui" ||
                              packageName.contains("launcher") ||
                              packageName.contains("settings") ||
                              packageName == applicationContext.packageName

            val isTargetApp = packageName.contains("chrome") || 
                              packageName.contains("browser") || 
                              packageName.contains("youtube") || 
                              packageName.contains("firefox") || 
                              packageName.contains("opera") || 
                              packageName.contains("duckduckgo") || 
                              packageName.contains("edge") || 
                              packageName.contains("brave") || 
                              packageName.contains("sbrowser") || 
                              packageName.contains("kiwi") || 
                              packageName.contains("ucmobile") || 
                              packageName.contains("via") || 
                              packageName.contains("tor") || 
                              packageName.contains("facebook") || 
                              packageName.contains("twitter") || 
                              packageName.contains("instagram") || 
                              packageName.contains("tiktok") || 
                              packageName.contains("reddit") || 
                              packageName.contains("pinterest") || 
                              packageName.contains("manhwa") || 
                              packageName.contains("manga")

            if (isSystemApp || !isTargetApp) {
                if (isKeywordBlockActive || isGeminiBlockActive) {
                    isKeywordBlockActive = false
                    isGeminiBlockActive = false
                    lastGeminiExplicitText = ""
                    handleSafeContent()
                }
            } else {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    val screenTexts = mutableListOf<String>()
                    extractScreenTexts(rootNode, screenTexts)
                    rootNode.recycle()

                    var isExplicitFound = false
                    for (text in screenTexts) {
                        if (containsExplicitKeyword(text)) {
                            isExplicitFound = true
                            break
                        }
                    }

                    val adultUnlockExpiry = unlockedApps["adult_content_blocked"]
                    val isAdultInGracePeriod = adultUnlockExpiry != null && System.currentTimeMillis() <= adultUnlockExpiry

                    if (isExplicitFound) {
                        if (isAdultInGracePeriod) {
                            showGracePeriodToast()
                        } else {
                            isKeywordBlockActive = true
                            triggerSoftLock()
                            return
                        }
                    } else {
                        if (!isGeminiBlockActive) {
                            handleSafeContent()
                        }
                    }

                    val meaningfulTexts = screenTexts.filter { text ->
                        text.length > 5 && 
                        !text.contains("AM") && !text.contains("PM") && 
                        !text.matches(Regex("\\d{1,2}:\\d{2}")) &&
                        text != "Habit Control"
                    }
                    if (meaningfulTexts.isNotEmpty()) {
                        val combinedText = meaningfulTexts.take(3).joinToString(" | ")
                        debounceScreenAnalysis(combinedText)
                    }
                }
            }
        }

        var activeUnlockedKey: String? = null
        var activeExpiry: Long = 0L

        val calendar = java.util.Calendar.getInstance()
        val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(java.util.Calendar.MINUTE)

        // Check for direct app block first
        val blockedApp = blockedAppsCache.find { it.packageName == packageName }
        if (blockedApp != null && (!blockedApp.isTimeScheduleEnabled || blockedApp.isTimeBlocked(currentHour, currentMinute))) {
            val unlockExpiry = unlockedApps[packageName]
            val hasExpired = unlockExpiry != null && System.currentTimeMillis() > unlockExpiry
            if (hasExpired) {
                usedUpApps.add(packageName)
            }
            val isUsedUp = usedUpApps.contains(packageName)

            if (isUsedUp) {
                launchBlockActivity(packageName, "BLOCK", blockedApp.challengeParam, blockedApp.allowedTimeMinutes)
                hideFloatingTimer()
                return
            } else if (unlockExpiry == null) {
                launchBlockActivity(packageName, blockedApp.challengeType, blockedApp.challengeParam, blockedApp.allowedTimeMinutes)
                hideFloatingTimer()
                return
            } else {
                activeUnlockedKey = packageName
                activeExpiry = unlockExpiry
            }
        }
        
        // Check for website blocks in any browser or web view
        if (activeUnlockedKey == null) {
            val websiteBlocks = blockedAppsCache.filter { it.packageName.startsWith("website:") }
            if (websiteBlocks.isNotEmpty()) {
                var foundUrl: String? = null

                // First check event text or contentDescription
                val eventText = event.text?.joinToString(" ") { it.toString() } ?: ""
                val eventDesc = event.contentDescription?.toString() ?: ""

                if (isUrlText(eventText)) {
                    foundUrl = eventText
                } else if (isUrlText(eventDesc)) {
                    foundUrl = eventDesc
                }

                if (foundUrl == null) {
                    val rootNode = rootInActiveWindow
                    if (rootNode != null) {
                        foundUrl = findUrl(rootNode)
                        rootNode.recycle()
                    }
                }

                if (foundUrl == null) {
                    val sourceNode = event.source
                    if (sourceNode != null) {
                        foundUrl = findUrl(sourceNode)
                        sourceNode.recycle()
                    }
                }

                if (foundUrl != null) {
                    val cleanCurrentUrl = cleanUrl(foundUrl)
                    if (cleanCurrentUrl.isNotEmpty()) {
                        var matchedBlock = false
                        for (websiteBlock in websiteBlocks) {
                            val rawTarget = websiteBlock.packageName.removePrefix("website:")
                            val cleanTarget = cleanUrl(rawTarget)

                            if (cleanTarget.isNotEmpty() && cleanCurrentUrl.contains(cleanTarget)) {
                                if (!websiteBlock.isTimeScheduleEnabled || websiteBlock.isTimeBlocked(currentHour, currentMinute)) {
                                    matchedBlock = true
                                    val unlockExpiry = unlockedApps[websiteBlock.packageName]
                                    val hasExpired = unlockExpiry != null && System.currentTimeMillis() > unlockExpiry
                                    if (hasExpired) {
                                        usedUpApps.add(websiteBlock.packageName)
                                    }
                                    val isUsedUp = usedUpApps.contains(websiteBlock.packageName)

                                    if (isUsedUp) {
                                        launchBlockActivity(websiteBlock.packageName, "BLOCK", websiteBlock.challengeParam, websiteBlock.allowedTimeMinutes)
                                        hideFloatingTimer()
                                        return
                                    } else if (unlockExpiry == null) {
                                        launchBlockActivity(websiteBlock.packageName, websiteBlock.challengeType, websiteBlock.challengeParam, websiteBlock.allowedTimeMinutes)
                                        hideFloatingTimer()
                                        return
                                    } else {
                                        activeUnlockedKey = websiteBlock.packageName
                                        activeExpiry = unlockExpiry
                                        activeBrowserPackage = packageName
                                        break
                                    }
                                }
                            }
                        }
                        if (!matchedBlock) {
                            // Navigated away to a non-blocked website
                            activeBrowserPackage = null
                        }
                    }
                } else {
                    // No URL found in this event. Keep timer if we are still in same browser package
                    if (packageName == activeBrowserPackage && currentTimerKey?.startsWith("website:") == true) {
                        val prevKey = currentTimerKey!!
                        val unlockExpiry = unlockedApps[prevKey]
                        if (unlockExpiry != null && System.currentTimeMillis() <= unlockExpiry) {
                            activeUnlockedKey = prevKey
                            activeExpiry = unlockExpiry
                        }
                    }
                }
            }
        }

        if (activeUnlockedKey != null) {
            showFloatingTimer(activeUnlockedKey, activeExpiry)
        } else {
            hideFloatingTimer()
        }
    }

    private fun cleanUrl(rawUrl: String): String {
        var cleaned = rawUrl.trim().lowercase()
        if (cleaned.startsWith("http://")) cleaned = cleaned.substring(7)
        if (cleaned.startsWith("https://")) cleaned = cleaned.substring(8)
        if (cleaned.startsWith("www.")) cleaned = cleaned.substring(4)
        return cleaned.trimEnd('/')
    }

    private fun isUrlText(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty() || t.contains(" ") || t.length < 3) return false
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("www.")) return true
        
        val parts = t.split('/')
        val domain = parts.firstOrNull() ?: return false
        if (!domain.contains(".")) return false
        
        val dotIndex = domain.lastIndexOf('.')
        if (dotIndex > 0 && dotIndex < domain.length - 2) {
            val tld = domain.substring(dotIndex + 1)
            return tld.all { it.isLetter() } && tld.length in 2..6
        }
        return false
    }

    private fun findUrl(nodeInfo: AccessibilityNodeInfo?): String? {
        if (nodeInfo == null) return null

        try {
            val viewId = nodeInfo.viewIdResourceName ?: ""
            val text = nodeInfo.text?.toString()
            val contentDesc = nodeInfo.contentDescription?.toString()
            val textOrDesc = text?.ifEmpty { null } ?: contentDesc

            if (viewId.contains("url", ignoreCase = true) ||
                viewId.contains("address", ignoreCase = true) ||
                viewId.contains("location", ignoreCase = true) ||
                viewId.contains("search", ignoreCase = true) ||
                viewId.contains("domain", ignoreCase = true) ||
                viewId.contains("host", ignoreCase = true)) {
                if (!textOrDesc.isNullOrEmpty() && isUrlText(textOrDesc)) {
                    return textOrDesc
                }
            }

            if (!textOrDesc.isNullOrEmpty() && isUrlText(textOrDesc)) {
                return textOrDesc
            }

            for (i in 0 until nodeInfo.childCount) {
                try {
                    val childNode = nodeInfo.getChild(i)
                    if (childNode != null) {
                        val url = findUrl(childNode)
                        if (url != null) {
                            childNode.recycle()
                            return url
                        }
                        childNode.recycle()
                    }
                } catch (e: Exception) {
                    // Ignore exception on individual child node access and keep traversing
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        return null
    }

    private fun launchBlockActivity(packageName: String, challengeType: String, challengeParam: String, allowedTimeMinutes: Int) {
        if (packageName.startsWith("website:") || packageName == "adult_content_blocked") {
            try {
                performGlobalAction(GLOBAL_ACTION_BACK)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        val intent = Intent(this, BlockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("PACKAGE_NAME", packageName)
            putExtra("CHALLENGE_TYPE", challengeType)
            putExtra("CHALLENGE_PARAM", challengeParam)
            putExtra("ALLOWED_TIME_MINUTES", allowedTimeMinutes)
        }
        startActivity(intent)
    }

    // Floating overlay UI
    private fun showFloatingTimer(key: String, expiry: Long) {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            return
        }
        
        currentTimerKey = key
        currentTimerExpiry = expiry
        
        if (timerView == null) {
            try {
                windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
                
                // Create custom programmatically styled layout
                val container = android.widget.FrameLayout(this)
                
                val backgroundDrawable = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(20).toFloat()
                    setColor(android.graphics.Color.parseColor("#E6212121")) // Semi-transparent dark gray
                    setStroke(dpToPx(1), android.graphics.Color.parseColor("#4DFFFFFF")) // Light border
                }
                container.background = backgroundDrawable
                
                // Horizontal layout
                val linearLayout = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val paddingHorizontal = dpToPx(14)
                    val paddingVertical = dpToPx(8)
                    setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
                }
                
                val iconView = android.widget.TextView(this).apply {
                    text = "⏳"
                    textSize = 14f
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(0, 0, dpToPx(6), 0)
                }
                
                val textView = android.widget.TextView(this).apply {
                    text = "00:00"
                    textSize = 13f
                    setTextColor(android.graphics.Color.WHITE)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                
                linearLayout.addView(iconView)
                linearLayout.addView(textView)
                container.addView(linearLayout)
                
                timerView = container
                
                val params = android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        android.view.WindowManager.LayoutParams.TYPE_PHONE
                    },
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                    y = dpToPx(80)
                }
                
                // Add touch drag support
                var initialX = 0
                var initialY = 0
                var initialTouchX = 0f
                var initialTouchY = 0f
                
                container.setOnTouchListener { v, event ->
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            true
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            try {
                                windowManager?.updateViewLayout(v, params)
                            } catch (e: Exception) {
                                // ignore
                            }
                            true
                        }
                        else -> false
                    }
                }
                
                windowManager?.addView(container, params)
                
                handler.removeCallbacks(timerRunnable)
                handler.post(timerRunnable)
            } catch (e: Exception) {
                e.printStackTrace()
                timerView = null
            }
        } else {
            handler.removeCallbacks(timerRunnable)
            handler.post(timerRunnable)
        }
    }

    private fun updateTimerUi() {
        val view = timerView ?: return
        val expiry = currentTimerExpiry
        val remainingMillis = expiry - System.currentTimeMillis()
        
        if (remainingMillis <= 0) {
            hideFloatingTimer()
            
            val key = currentTimerKey ?: return
            val blockedApp = blockedAppsCache.find { it.packageName == key }
            if (blockedApp != null) {
                launchBlockActivity(key, blockedApp.challengeType, blockedApp.challengeParam, blockedApp.allowedTimeMinutes)
            }
            return
        }
        
        val seconds = (remainingMillis / 1000) % 60
        val minutes = (remainingMillis / (1000 * 60)) % 60
        val hours = remainingMillis / (1000 * 60 * 60)
        
        val timeString = if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
        
        try {
            val frameLayout = view as? android.widget.FrameLayout
            val linearLayout = frameLayout?.getChildAt(0) as? android.widget.LinearLayout
            val textView = linearLayout?.getChildAt(1) as? android.widget.TextView
            textView?.text = timeString
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun hideFloatingTimer() {
        handler.removeCallbacks(timerRunnable)
        val view = timerView
        if (view != null) {
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                // ignore
            }
            timerView = null
        }
        currentTimerKey = null
        currentTimerExpiry = 0L
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    override fun onInterrupt() {
        // Handle interrupt
    }

    private fun extractScreenTexts(nodeInfo: AccessibilityNodeInfo?, texts: MutableList<String>) {
        if (nodeInfo == null || texts.size >= 15) return
        try {
            val text = nodeInfo.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && text.length in 4..150) {
                if (!texts.contains(text)) {
                    texts.add(text)
                }
            }
            for (i in 0 until nodeInfo.childCount) {
                try {
                    val child = nodeInfo.getChild(i)
                    if (child != null) {
                        extractScreenTexts(child, texts)
                        child.recycle()
                    }
                } catch (e: Exception) {
                    // Ignore exception on individual child node access and keep traversing
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun containsExplicitKeyword(text: String): Boolean {
        return onDeviceLocalAIClassify(text)
    }

    private fun onDeviceLocalAIClassify(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.isBlank()) return false
        
        val normalizedArabic = normalizeArabicFully(t)
        val normalizedEnglish = normalizeEnglishFully(t)
        
        // 1. Strict explicit keywords (always blocked, slang, and specific sites)
        val strictWords = setOf(
            // English / Western explicit
            "porn", "pornhub", "xnxx", "xvideos", "hentai", "onlyfans", "porno", "xhamster",
            "brazzers", "spankbang", "beeg", "eporner", "redtube", "youporn", "hqporner", "tube8",
            "cuckold", "milf", "blowjob", "threesome", "gangbang", "peeing", "orgasm", "masturbate",
            "striptease", "playboy", "camgirl", "erotic", "nsfw", "naked", "nude", "hardcore sex",
            "sexy", "bitch", "pussy", "vagina", "penis", "clitoris", "ejaculation", "lesbian", 
            "gay sex", "erotica", "strip club", "webcam sex", "mature tube", "shemale", 
            "transsexual", "swinger", "fetish", "bdsm", "softcore sex", "adult toys",
            "boobs", "boob", "tits", "dick", "cock", "asshole", "fucking", "fuck",
            "slut", "whore", "bastard", "ballsack", "semen", "sperm", "testicle", "testicles",
            
            // Foreign Manhwa / Manga / Manhua sites & domains
            "mangadex", "asurascans", "asuratoon", "asuramanga", "asuratscans", "reaperscans", "reaper-scans", 
            "flamecomics", "flamescans", "voidscans", "hivetoon", "manganato", "mangakakalot", 
            "batoto", "toonily", "mangasee", "mangahere", "mangapark", "manhuaplus", "luminousscans", 
            "luminous-scans", "nightscans", "realmscans", "zeroscans", "zero-scans", "kunmanga", 
            "mangagg", "manhwatop", "topmanhua", "tappytoon", "lezhin", "toomics", 
            "webcomics", "mangabuddy", "mangafox", "mangaowl", "mangapill", "mangafreak", 
            "manhwatime", "manhwa18", "rawdevart", "mangazuki", "mangarock", 
            "mangatype", "manhwaclan", "manhwaindo", "mangadread", "vyvymanga", "zinmanga", 
            "manhwascan", "manhwafull", "manhwasmut", "mangahub", "manganelo", 
            "mangafast", "mangakik", "novelcool", "mangaclash", "mangasail", "manhuaus", 
            "manhuascan", "mangaonlineteam", "mangakatana", "manhwanew"
        )
        
        for (word in strictWords) {
            if (t.contains(word)) {
                return true
            }
        }
        
        // Match isolated short words safely
        val shortStrictWords = setOf("sex", "ass", "cum", "tit", "jav")
        val englishWords = t.split(Regex("[^a-zA-Z]+"))
        for (w in englishWords) {
            if (shortStrictWords.contains(w)) {
                return true
            }
        }
        
        // 2. Regex patterns to catch spaced or obfuscated spellings (e.g. s e x, p*o*r*n, س ك س)
        val patterns = listOf(
            Regex("\\bp\\s*o\\s*r\\s*n\\b"),
            Regex("\\bs\\s*e\\s*x\\b"),
            Regex("\\bf\\s*u\\s*c\\s*k\\b"),
            Regex("\\bx\\s*x\\s*x\\b"),
            Regex("\\bn\\s*u\\s*d\\s*e\\b"),
            Regex("(^|\\s)س\\s*ك\\s*س($|\\s)"),
            Regex("(^|\\s)ن\\s*ي\\s*ك($|\\s)"),
            Regex("(^|\\s)ا\\s*ب\\s*ا\\s*ح\\s*ي($|\\s)"),
            Regex("(^|\\s)ط\\s*ي\\s*ز($|\\s)"),
            Regex("(^|\\s)ق\\s*ض\\s*ي\\s*ب($|\\s)"),
            Regex("(^|\\s)ك\\s*س($|\\s)")
        )
        for (pattern in patterns) {
            if (pattern.containsMatchIn(t)) {
                return true
            }
        }
        
        // 3. Smart local on-device semantic parsing (Suggestive word + Booster word combinations)
        val suggestiveWords = setOf(
            "جنس", "ثدي", "مؤخرة", "مؤخره", "نهود", "بزاز", "فرج", "عاهره", "عاهرات", "مومس", "مومسات",
            "شذوذ", "مثليين", "عارية", "عاريه", "تعري", "اغراء", "قبلات"
        )
        
        val boosters = setOf(
            "افلام", "فيديو", "صور", "شاهد", "فيلم", "مقاطع", "تحميل", "كامل", "تنزيل", "تسريب", "فضيحه", 
            "فضيحة", "فضايح", "مترجم", "بدون حذف", "ساخن", "مثير", "حار",
            "لقطات", "مقطع", "رذيله", "رذيلة", "حميمية", "حميميه"
        )
        
        var suggestiveCount = 0
        var boosterCount = 0
        
        val wordsInText = t.split(Regex("\\s+")).map { normalizeArabicFully(it) }
        for (w in wordsInText) {
            if (suggestiveWords.contains(w)) {
                suggestiveCount++
            }
            if (boosters.contains(w)) {
                boosterCount++
            }
        }
        
        if (suggestiveCount >= 1 && boosterCount >= 1) {
            return true
        }
        if (suggestiveCount >= 2) {
            return true
        }
        
        // 4. Multi-word phrase matching
        val explicitPhrases = listOf(
            "علاقه جنسيه", "علاقات جنسيه", "ممارسه الجنس", "علاقه حميميه", "ممارسه الرذيله", "لقطات ساخنه",
            "افلام اغراء", "صور فاضحه", "فضيحه جنسيه", "وضعيات مثيره", "وضعيات الجماع", "سرير النوم",
            "read manhwa", "read manga", "read manhua", "manhwa online", "manga online", "manhua online",
            "manhwa raw", "manga raw", "manhua raw", "manhwa chapter", "manga chapter", "manhua chapter",
            "manga scan", "manhwa scan", "read webtoon", "webtoon online", "raw manhwa", "free manhwa",
            "free manga", "manhwa reading", "manga reading", "manga reader", "manhwa reader", "foreign manhwa",
            "foreign manga", "english manhwa", "english manga"
        )
        for (phrase in explicitPhrases) {
            if (normalizedArabic.contains(phrase) || t.contains(phrase)) {
                return true
            }
        }
        
        return false
    }

    private fun normalizeArabicFully(input: String): String {
        return input
            // Remove kashida/tatweel
            .replace("ـ".toRegex(), "")
            // Remove Arabic harakat (diacritics)
            .replace("[ًٌٍَُِّْ]".toRegex(), "")
            // Normalize letters
            .replace("[أإآٱ]".toRegex(), "ا")
            .replace("ة".toRegex(), "ه")
            .replace("ى".toRegex(), "ي")
            .replace("[ؤئ]".toRegex(), "ء")
    }

    private fun normalizeEnglishFully(input: String): String {
        return input.replace("[^a-zA-Z0-9]".toRegex(), "")
    }

    private fun triggerSoftLock() {
        if (adultBlockJob == null || adultBlockJob?.isActive != true) {
            adultBlockJob = serviceScope.launch {
                var consecSafeChecks = 0
                for (halfSecsLeft in 10 downTo 1) {
                    val secondsLeft = (halfSecsLeft + 1) / 2
                    showSoftLockOverlay(secondsLeft)
                    delay(500)
                    
                    // Re-check current screen state to see if they resolved/deleted the explicit content
                    val currentTexts = mutableListOf<String>()
                    val rootNode = rootInActiveWindow
                    if (rootNode != null) {
                        extractScreenTexts(rootNode, currentTexts)
                        rootNode.recycle()
                    }
                    
                    val isStillExplicit = currentTexts.any { containsExplicitKeyword(it) } || 
                        (isGeminiBlockActive && currentTexts.any { it.contains(lastGeminiExplicitText.split(" | ").firstOrNull() ?: "___") })
                        
                    if (!isStillExplicit) {
                        consecSafeChecks++
                    } else {
                        consecSafeChecks = 0
                    }
                    
                    // If they kept it clean for at least 500ms (1 check), cancel!
                    if (consecSafeChecks >= 1) {
                        handler.post {
                            hideSoftLockOverlay()
                            showSoftLockCanceledToast()
                        }
                        adultBlockJob = null
                        isKeywordBlockActive = false
                        isGeminiBlockActive = false
                        lastGeminiExplicitText = ""
                        return@launch
                    }
                }
                
                // If countdown finishes and still blocked:
                handler.post {
                    launchBlockActivity("adult_content_blocked", "BLOCK", "", 0)
                    hideSoftLockOverlay()
                    hideFloatingTimer()
                }
                adultBlockJob = null
                isKeywordBlockActive = false
                isGeminiBlockActive = false
                lastGeminiExplicitText = ""
            }
        }
    }

    private fun handleSafeContent() {
        if (adultBlockJob != null && adultBlockJob?.isActive == true) {
            adultBlockJob?.cancel()
            adultBlockJob = null
            hideSoftLockOverlay()
            showSoftLockCanceledToast()
        }
    }

    private fun showSoftLockOverlay(secondsRemaining: Int) {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            // Fallback to toast if no overlay permission
            showSoftLockToast(secondsRemaining)
            return
        }
        
        handler.post {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
                if (softLockOverlayView == null) {
                    val container = android.widget.FrameLayout(this)
                    val backgroundDrawable = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = dpToPx(12).toFloat()
                        setColor(android.graphics.Color.parseColor("#F2B71C1C")) // Semi-transparent warning red
                        setStroke(dpToPx(2), android.graphics.Color.parseColor("#FFFFD54F")) // Gold border
                    }
                    container.background = backgroundDrawable
                    
                    val linearLayout = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        val paddingHorizontal = dpToPx(16)
                        val paddingVertical = dpToPx(12)
                        setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
                    }
                    
                    val iconView = android.widget.TextView(this).apply {
                        text = "⚠️"
                        textSize = 18f
                        setPadding(0, 0, dpToPx(8), 0)
                    }
                    
                    val textView = android.widget.TextView(this).apply {
                        text = "محتوى غير لائق! الحظر خلال $secondsRemaining ثوانٍ... احذفه فوراً لتجنب الحظر"
                        textSize = 14f
                        setTextColor(android.graphics.Color.WHITE)
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        gravity = android.view.Gravity.CENTER
                    }
                    
                    linearLayout.addView(iconView)
                    linearLayout.addView(textView)
                    container.addView(linearLayout)
                    
                    softLockOverlayView = container
                    
                    val params = android.view.WindowManager.LayoutParams(
                        android.view.WindowManager.LayoutParams.MATCH_PARENT,
                        android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        } else {
                            @Suppress("DEPRECATION")
                            android.view.WindowManager.LayoutParams.TYPE_PHONE
                        },
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        android.graphics.PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                        y = dpToPx(40)
                        x = 0
                        width = resources.displayMetrics.widthPixels - dpToPx(32) // Leave margins
                    }
                    
                    wm.addView(container, params)
                } else {
                    // Update text only
                    val container = softLockOverlayView as? android.widget.FrameLayout
                    val linearLayout = container?.getChildAt(0) as? android.widget.LinearLayout
                    val textView = linearLayout?.getChildAt(1) as? android.widget.TextView
                    textView?.text = "محتوى غير لائق! الحظر خلال $secondsRemaining ثوانٍ... احذفه فوراً لتجنب الحظر"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun hideSoftLockOverlay() {
        handler.post {
            val view = softLockOverlayView
            if (view != null) {
                try {
                    val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
                    wm.removeView(view)
                } catch (e: Exception) {
                    // ignore
                }
                softLockOverlayView = null
            }
        }
    }

    private fun showSoftLockToast(seconds: Int) {
        handler.post {
            try {
                currentToast?.cancel()
                val toast = android.widget.Toast.makeText(
                    applicationContext,
                    "⚠️ تم كشف محتوى غير لائق! سيتم الحظر خلال $seconds ثوانٍ... يرجى التراجع أو حذف النص لتجنب الحظر.",
                    android.widget.Toast.LENGTH_SHORT
                )
                currentToast = toast
                toast.show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showSoftLockCanceledToast() {
        handler.post {
            try {
                currentToast?.cancel()
                val toast = android.widget.Toast.makeText(
                    applicationContext,
                    "✅ تم إلغاء الحظر. شكراً لك على تراجعك! 💪✨",
                    android.widget.Toast.LENGTH_SHORT
                )
                currentToast = toast
                toast.show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun debounceScreenAnalysis(combinedText: String) {
        analysisJob?.cancel()
        analysisJob = serviceScope.launch {
            delay(1000) // Debounce for 1 second
            checkWithGeminiAsync(combinedText)
        }
    }

    private var lastGraceToastTime = 0L

    private fun showGracePeriodToast() {
        val now = System.currentTimeMillis()
        if (now - lastGraceToastTime > 4000) {
            lastGraceToastTime = now
            val adultUnlockExpiry = unlockedApps["adult_content_blocked"] ?: 0L
            val secondsRemaining = ((adultUnlockExpiry - now) / 1000).coerceAtLeast(1)
            handler.post {
                try {
                    currentToast?.cancel()
                    val toast = android.widget.Toast.makeText(
                        applicationContext,
                        "⏳ مهلة 20 ثانية: يرجى إغلاق التبويب أو حذف الكلمة قبل إعادة الحظر ($secondsRemaining ث)",
                        android.widget.Toast.LENGTH_SHORT
                    )
                    currentToast = toast
                    toast.show()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun checkWithGeminiAsync(textToAnalyze: String) {
        if (textToAnalyze.isBlank() || textToAnalyze.length < 5) return
        if (classificationCache.containsKey(textToAnalyze)) return

        // Prevent duplicate checks for the same text
        classificationCache[textToAnalyze] = false

        // Check locally first with on-device smart AI analyzer (works instantly offline)
        val isLocallyExplicit = onDeviceLocalAIClassify(textToAnalyze)
        if (isLocallyExplicit) {
            classificationCache[textToAnalyze] = true
            handler.post {
                val adultUnlockExpiry = unlockedApps["adult_content_blocked"]
                val isAdultInGracePeriod = adultUnlockExpiry != null && System.currentTimeMillis() <= adultUnlockExpiry
                if (isAdultInGracePeriod) {
                    showGracePeriodToast()
                } else {
                    isGeminiBlockActive = true
                    lastGeminiExplicitText = textToAnalyze
                    triggerSoftLock()
                }
            }
            return
        }

        serviceScope.launch {
            val apiKey = try {
                com.example.BuildConfig.GEMINI_API_KEY
            } catch (e: Exception) {
                ""
            }
            if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
                return@launch
            }

            val isExplicit = performGeminiClassification(apiKey, textToAnalyze)
            classificationCache[textToAnalyze] = isExplicit
            if (isExplicit) {
                handler.post {
                    val adultUnlockExpiry = unlockedApps["adult_content_blocked"]
                    val isAdultInGracePeriod = adultUnlockExpiry != null && System.currentTimeMillis() <= adultUnlockExpiry
                    if (isAdultInGracePeriod) {
                        showGracePeriodToast()
                    } else {
                        isGeminiBlockActive = true
                        lastGeminiExplicitText = textToAnalyze
                        triggerSoftLock()
                    }
                }
            }
        }
    }

    private suspend fun performGeminiClassification(apiKey: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val systemInstruction = "You are a content safety filter. Analyze the given webpage title, search query, video title, or URL and determine if it represents, references, or leads to pornographic/sexually explicit/NSFW content, OR any foreign Manhwa, Manga, Manhua, or Webtoon reading website or online aggregator (such as MangaDex, Asura Scans, Reaper Scans, Toonily, MangaNato, Bato.to, etc.). Respond with only one word: BLOCK or ALLOW. Do not provide any explanation or extra text."
        
        val requestBodyJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "Text/URL to classify: \"$text\"")
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.0)
                put("maxOutputTokens", 5)
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", systemInstruction)
                    })
                })
            })
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey")
            .post(requestBodyJson.toString().toRequestBody(mediaType))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""
                    val jsonResponse = JSONObject(bodyString)
                    val candidates = jsonResponse.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val firstCandidate = candidates.getJSONObject(0)
                        val content = firstCandidate.optJSONObject("content")
                        if (content != null) {
                            val parts = content.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                val resultText = parts.getJSONObject(0).optString("text", "ALLOW").trim().uppercase()
                                return@withContext resultText.contains("BLOCK")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
    }

    override fun onDestroy() {
        super.onDestroy()
        hideFloatingTimer()
        hideSoftLockOverlay()
        serviceJob.cancel()
    }
}
