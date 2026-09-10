package io.github.warleysr.dechainer

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import androidx.core.content.edit
import io.github.warleysr.dechainer.activities.AccessibilityRequestActivity
import io.github.warleysr.dechainer.activities.BlockedWordActivity
import io.github.warleysr.dechainer.activities.NsfwContentBlockedActivity
import io.github.warleysr.dechainer.activities.ReopeningLimitActivity
import io.github.warleysr.dechainer.activities.TimeUpActivity
import io.github.warleysr.dechainer.utils.NetworkBlockManager
import io.github.warleysr.dechainer.utils.NsfwContentDetector
import io.github.warleysr.dechainer.utils.PlayStoreRatingFetcher
import io.github.warleysr.dechainer.utils.VisualBlockingSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.shared.BuildConfig
import timber.log.Timber
import java.util.concurrent.Executors
import kotlin.math.max

@SuppressLint("AccessibilityPolicy")
class DechainerAccessibilityService : AccessibilityService() {

    // --- SOBER UP & CUSTOM BLOCKING PROPERTIES ---
    @Volatile private var isNetworkBlocking = false
    private var soberUpPrefs: SharedPreferences? = null
    private var isSoberUpEnabled = false
    private var sensitiveKeywordsList: List<String> = emptyList()
    private var browserPackages: Set<String> = emptySet()

    private var wasTyping: Boolean = false
    private var lastTypedText: String = ""

    // --- STANDARD PROPERTIES ---
    private val handler = Handler(Looper.getMainLooper())
    private var currentPackage: String? = null
    private var sessionStartTime: Long = 0
    private var lastCheckDate: String = LocalDate.now().toString()
    private val lastClosedTimes = HashMap<String, Long>()

    private lateinit var limitPrefs: SharedPreferences
    private lateinit var usagePrefs: SharedPreferences
    private lateinit var reopenPrefs: SharedPreferences
    private lateinit var blockedWordsPrefs: SharedPreferences
    private lateinit var securityPrefs: SharedPreferences
    private lateinit var ratingPrefs: SharedPreferences

    // --- VISUAL BLOCKING PROPERTIES ---
    private lateinit var visualBlockingPrefs: SharedPreferences
    private var nsfwDetector: NsfwContentDetector? = null
    private var lastNsfwScanElapsedMs = 0L
    private var nsfwEnabled: Boolean = false
    private var nsfwTargetPackages: Set<String> = VisualBlockingSettings.DEFAULT_TARGET_PACKAGES
    private var nsfwCategories: Set<String> = VisualBlockingSettings.DEFAULT_CATEGORIES
    private var nsfwThreshold: Float = VisualBlockingSettings.DEFAULT_THRESHOLD

    private val nsfwDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "NsfwDetector") }.asCoroutineDispatcher()

    @Volatile
    private var nsfwScanInFlight = false

    private var forbiddenPatterns: Map<String, Regex> = emptyMap()
    private var passiveForbiddenPatterns: Map<String, Map<String, Regex>> = emptyMap()
    private var targetPackages: Set<String> = emptySet()

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun updateSensitiveWords() {
        val prefs = soberUpPrefs ?: return
        isSoberUpEnabled = prefs.getBoolean("enabled", false)

        if (!prefs.getBoolean("defaults_loaded", false)) {
            val baseKeywords = try {
                resources.openRawResource(R.raw.block_keywords_expanded)
                    .bufferedReader().readLines()
                    .map { it.trim().lowercase() }
                    .filter { it.isNotBlank() }
                    .toSet()
            } catch (e: Exception) { emptySet() }

            prefs.edit {
                putStringSet("sensitive_words", baseKeywords)
                putBoolean("defaults_loaded", true)
            }
            sensitiveKeywordsList = baseKeywords.toList()
        } else {
            sensitiveKeywordsList = prefs.getStringSet("sensitive_words", emptySet())?.toList() ?: emptyList()
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        @RequiresPermission(anyOf = [Manifest.permission.REQUEST_DELETE_PACKAGES, Manifest.permission.DELETE_PACKAGES])
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_PACKAGE_ADDED) return
            val packageName = intent.data?.encodedSchemeSpecificPart ?: return

            // --- NEW: Intercept forbidden installations ---
            val installPrefs = applicationContext.getSharedPreferences("install_blocker", MODE_PRIVATE)
            if (installPrefs.getBoolean(packageName, false)) {
                try {
                    val packageInstaller = applicationContext.packageManager.packageInstaller
                    val pendingIntent = PendingIntent.getBroadcast(
                        applicationContext,
                        0,
                        Intent("io.github.warleysr.dechainer.UNINSTALL_APP"),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    packageInstaller.uninstall(packageName, pendingIntent.intentSender)
                } catch (e: Exception) { e.printStackTrace() }
                return
            }
            // ----------------------------------------------

            val manager = BrowserRestrictionsManager(applicationContext)
            val isBrowser = manager.isBrowser(packageName)

            if (isBrowser) {
                val supportsRestrictions = manager.supportsRestrictions(packageName)
                if (supportsRestrictions) {
                    manager.applyRestrictions(installed = true)
                    return
                }
                suspendPackage(packageName)
            } else {
                val isTorrentApp = manager.isTorrentApp(packageName)
                if (isTorrentApp && securityPrefs.getBoolean("block_torrents", false))
                    suspendPackage(packageName)
            }

            val isUpdate = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (!isUpdate) {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val info = PlayStoreRatingFetcher.fetch(packageName)
                        if (info.hasExplicitContent && info.contentRating == "Rated 18+")
                            withContext(Dispatchers.Main) {
                                suspendPackage(packageName)
                            }
                        ratingPrefs.edit { putBoolean(packageName, info.hasExplicitContent) }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private var lastForegroundPackage: String? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    lastForegroundPackage = currentPackage
                    stopTrackingAndSave(screenOff = true)
                    currentPackage = null
                }
                Intent.ACTION_SCREEN_ON -> {
                    lastForegroundPackage?.let {
                        currentPackage = it
                        sessionStartTime = SystemClock.elapsedRealtime()
                        checkDateReset()
                        startTracking(it)
                    }
                }
            }
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (prefs) {
            limitPrefs -> {
                if (key == currentPackage) {
                    handler.removeCallbacks(blockRunnable)
                    currentPackage?.let { startTracking(it) }
                }
            }
            blockedWordsPrefs -> updateForbiddenPatterns()
            visualBlockingPrefs -> updateVisualBlockingSettings()
            soberUpPrefs -> updateSensitiveWords()
        }
    }

    private fun updateVisualBlockingSettings() {
        nsfwEnabled = visualBlockingPrefs.getBoolean(VisualBlockingSettings.KEY_ENABLED, false)
        nsfwTargetPackages = visualBlockingPrefs.getStringSet(
            VisualBlockingSettings.KEY_TARGET_PACKAGES, VisualBlockingSettings.DEFAULT_TARGET_PACKAGES
        ) ?: VisualBlockingSettings.DEFAULT_TARGET_PACKAGES
        nsfwCategories = visualBlockingPrefs.getStringSet(
            VisualBlockingSettings.KEY_CATEGORIES, VisualBlockingSettings.DEFAULT_CATEGORIES
        ) ?: VisualBlockingSettings.DEFAULT_CATEGORIES
        nsfwThreshold = visualBlockingPrefs.getFloat(
            VisualBlockingSettings.KEY_THRESHOLD, VisualBlockingSettings.DEFAULT_THRESHOLD
        )
    }

    private fun updateForbiddenPatterns() {
        browserPackages = BrowserRestrictionsManager(applicationContext).getPossibleBrowsers().map { it.activityInfo.packageName }.toSet()
        val words = blockedWordsPrefs.getStringSet("blocked_words", emptySet()) ?: emptySet()
        forbiddenPatterns = words.associateWith { word ->
            Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(word)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
        }
        targetPackages = blockedWordsPrefs.getStringSet("target_packages", emptySet()) ?: emptySet()

        val all = blockedWordsPrefs.all
        val passiveMap = mutableMapOf<String, Map<String, Regex>>()
        all.forEach { (key, value) ->
            if (key.startsWith("passive_words_")) {
                val pkg = key.substringAfter("passive_words_")
                val wordsSet = (value as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
                if (wordsSet.isNotEmpty()) {
                    passiveMap[pkg] = wordsSet.associateWith { word ->
                        Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(word)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
                    }
                }
            }
        }
        passiveForbiddenPatterns = passiveMap
    }

    data class ScreenExtraction(val text: String, val isTyping: Boolean)

    private fun extractSearchBoxTextAndFocus(): ScreenExtraction {
        val rootNode = rootInActiveWindow ?: return ScreenExtraction("", false)
        val sb = StringBuilder()
        var isTyping = false

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isEditable || node.className?.contains("EditText") == true) {
                if (node.isFocused) isTyping = true
                node.text?.let { sb.append(it).append(" ") }
            }
            for (i in 0 until node.childCount) traverse(node.getChild(i))
        }

        traverse(rootNode)
        rootNode.recycle()
        return ScreenExtraction(sb.toString().lowercase(), isTyping)
    }

    private val blockRunnable = Runnable { executeBlocking() }

    companion object {
        private const val NSFW_SCAN_INTERVAL_MS = 2000L
        private val NSFW_MEDIA_CLASS_KEYWORDS = listOf("Image", "Photo", "Video", "Media", "Player", "Gif", "TextureView", "SurfaceView", "WebView")
        private val NSFW_MEDIA_KEYWORDS = listOf("imagem", "image", "picture", "foto", "photo", "vídeo", "video", "gif", "media", "mídia")
        private val NSFW_MEDIA_DESCRIPTION_PATTERNS = listOf(
            Regex("""^\d{1,2}\+,"""),
            Regex("postado (no|em)", RegexOption.IGNORE_CASE),
            Regex("posted (in|to)", RegexOption.IGNORE_CASE)
        )
        private const val NSFW_FALLBACK_MIN_ASPECT = 0.4f
        private const val NSFW_FALLBACK_MAX_ASPECT = 3.0f
        private const val NSFW_FALLBACK_MAX_CANDIDATES = 5
        private const val NSFW_MIN_MEDIA_SIZE_DP = 96
        private const val NSFW_DIAG_MIN_SIZE_DP = 48
        private const val NSFW_DIAG_MAX_NODES = 40

        var isRunning by mutableStateOf(false)
            private set
        var disablingService = false
            private set
        var checkingRating = false
            private set

        fun prepareServiceDisable() { disablingService = true }

        val accessedActivities = mutableStateListOf<ActivityLog>()

        data class ActivityLog(val packageName: String, val className: String, val timestamp: Long = System.currentTimeMillis())

        private fun addLog(packageName: String, className: String) {
            if (accessedActivities.any { it.packageName == packageName && it.className == className }) {
                accessedActivities.removeIf { it.packageName == packageName && it.className == className }
            }
            accessedActivities.add(0, ActivityLog(packageName, className))
            if (accessedActivities.size > 100) accessedActivities.removeAt(accessedActivities.lastIndex)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        disablingService = false

        limitPrefs = getSharedPreferences("app_limits", MODE_PRIVATE)
        usagePrefs = getSharedPreferences("internal_usage_stats", MODE_PRIVATE)
        reopenPrefs = getSharedPreferences("reopen_times", MODE_PRIVATE)
        blockedWordsPrefs = getSharedPreferences("blocked_words_prefs", MODE_PRIVATE)
        securityPrefs = getSharedPreferences("security_prefs", MODE_PRIVATE)
        ratingPrefs = getSharedPreferences("app_ratings", MODE_PRIVATE)
        visualBlockingPrefs = getSharedPreferences(VisualBlockingSettings.PREFS_NAME, MODE_PRIVATE)
        soberUpPrefs = getSharedPreferences("sober_up_prefs", MODE_PRIVATE)

        limitPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        blockedWordsPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        visualBlockingPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        soberUpPrefs?.registerOnSharedPreferenceChangeListener(prefsListener)

        updateForbiddenPatterns()
        updateVisualBlockingSettings()
        updateSensitiveWords()

        val blockedPackages = getControlledPackages()
        suspendPackages(blockedPackages, false)

        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, DechainerDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
        }

        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, packageFilter)

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, screenFilter)

        if (BuildConfig.DEBUG) {
            val filter = IntentFilter("io.github.warleysr.dechainer.DEBUG_INSTALL")
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    disablingService = true
                    disableSelf()
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        unregisterReceiver(packageReceiver)
        unregisterReceiver(screenReceiver)
        limitPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        blockedWordsPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        visualBlockingPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        soberUpPrefs?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        stopTrackingAndSave()

        val detectorToClose = nsfwDetector
        nsfwDetector = null
        if (detectorToClose != null) {
            serviceScope.launch(nsfwDispatcher) { detectorToClose.close() }
        }
        nsfwDispatcher.close()
        isRunning = false

        if (!disablingService) {
            val blockedPackages = getControlledPackages()
            suspendPackages(blockedPackages)

            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, DechainerDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
            }

            val intentActivity = Intent(
                this@DechainerAccessibilityService, AccessibilityRequestActivity::class.java
            ).apply { flags = FLAG_ACTIVITY_NEW_TASK }
            startActivity(intentActivity)
        }

        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(blockRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName == packageName) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val newPackage = event.packageName?.toString() ?: return
            val className = event.className?.toString() ?: return
            if (newPackage == "com.android.systemui") return
            if (className.contains("InputMethodService", ignoreCase = true)
                || className.contains("SoftInputWindow", ignoreCase = true)
            ) return

            if (newPackage != currentPackage) {
                stopTrackingAndSave()
                currentPackage = newPackage
                wasTyping = false
                lastTypedText = ""
                sessionStartTime = SystemClock.elapsedRealtime()
                checkDateReset()
                startTracking(newPackage)

                if (nsfwEnabled && newPackage in nsfwTargetPackages) {
                    warmUpNsfwDetector()
                }
            }

            if (className.contains("Activity", ignoreCase = true)) {
                addLog(newPackage, className)
                val blockerPrefs = getSharedPreferences("activity_blocker_prefs", MODE_PRIVATE)
                val blockedActivities = blockerPrefs.getStringSet("blocked_activities", emptySet()) ?: emptySet()

                if (blockedActivities.contains(className))
                    performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
        else if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            if (event.source?.isEditable == false) return

            val pkg = currentPackage ?: return
            if (!targetPackages.contains(pkg)) return

            val text = event.text.joinToString(" ")
            val forbiddenWord = checkForbiddenWord(text) ?: return

            val arguments = Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text.replace(forbiddenWord, "")
            )
            event.source?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

            showBlockedActivity(forbiddenWord)
        }
        else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val pkg = currentPackage ?: event.packageName?.toString() ?: return

            // --- SOBER UP: Browser & Search Block ---
            if (pkg == "com.android.chrome" || browserPackages.contains(pkg)) {
                if (isSoberUpEnabled) {
                    val extraction = extractSearchBoxTextAndFocus()
                    if (extraction.isTyping) {
                        wasTyping = true
                        lastTypedText = extraction.text
                    } else if (wasTyping) {
                        wasTyping = false
                        val textToCheck = if (extraction.text.isNotBlank()) extraction.text else lastTypedText
                        val matchedWord = sensitiveKeywordsList.firstOrNull { textToCheck.contains(it) }

                        if (matchedWord != null && !isNetworkBlocking) {
                            isNetworkBlocking = true
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            performGlobalAction(GLOBAL_ACTION_BACK)

                            NetworkBlockManager.triggerBlock(applicationContext)

                            val intentActivity = Intent(this, BlockedWordActivity::class.java).apply {
                                flags = FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                putExtra("word", matchedWord)
                                putExtra("isNetworkBlock", true)
                            }
                            startActivity(intentActivity)

                            handler.postDelayed({ isNetworkBlocking = false }, 2000)
                            return
                        }
                    }
                }
            }

            // --- WHATSAPP UPDATES TAB BLOCKER ---
            if (pkg == "com.whatsapp" && securityPrefs.getBoolean("block_whatsapp_updates", false)) {
                val rootNode = rootInActiveWindow
                var isUpdatesTabActive = false

                fun checkWhatsAppTab(node: AccessibilityNodeInfo?) {
                    if (node == null || isUpdatesTabActive) return
                    val contentDesc = node.contentDescription?.toString() ?: ""
                    val text = node.text?.toString() ?: ""
                    if ((contentDesc.contains("Updates", ignoreCase = true) && node.isSelected) ||
                        (text == "Channels" && node.className?.contains("TextView") == true)) {
                        isUpdatesTabActive = true
                    }
                    for (i in 0 until node.childCount) checkWhatsAppTab(node.getChild(i))
                }

                rootNode?.let {
                    checkWhatsAppTab(it)
                    it.recycle()
                }

                if (isUpdatesTabActive) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    return
                }
            }

            // --- VISUAL BLOCKING SCANNER ---
            if (nsfwEnabled && pkg in nsfwTargetPackages && event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                maybeScanForNsfwContent(pkg)
            }

            if (!targetPackages.contains(pkg)) return

            if (!ratingPrefs.contains(pkg) && !checkingRating) {
                checkingRating = true
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val info = PlayStoreRatingFetcher.fetch(pkg)
                        if (info.hasExplicitContent && info.contentRating == "Rated 18+")
                            withContext(Dispatchers.Main) {
                                suspendPackage(pkg)
                            }
                        ratingPrefs.edit { putBoolean(pkg, info.hasExplicitContent) }
                        checkingRating = false
                    } catch (e: Exception) {
                        e.printStackTrace()
                        checkingRating = false
                    }
                }
            }

            val passivePatterns = passiveForbiddenPatterns[pkg] ?: return
            val screenText = buildScreenText(event) ?: return

            passivePatterns.forEach { (word, regex) ->
                if (regex.containsMatchIn(screenText)) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    showBlockedActivity(word)
                    return@forEach
                }
            }
        }
    }

    private fun buildScreenText(event: AccessibilityEvent): String? {
        val sb = StringBuilder()
        event.text.forEach { sb.append(it).append(' ') }
        event.source?.also { root ->
            fun traverse(node: AccessibilityNodeInfo?) {
                node ?: return
                node.text?.let { sb.append(it).append(' ') }
                node.contentDescription?.let { sb.append(it).append(' ') }
                for (i in 0 until node.childCount) traverse(node.getChild(i))
            }
            traverse(root)
            root.recycle()
        }
        return sb.toString().trim().takeIf { it.isNotBlank() }
    }

    private fun showBlockedActivity(forbiddenWord: String) {
        serviceScope.launch {
            withContext(Dispatchers.Main) {
                val intentActivity = Intent(
                    this@DechainerAccessibilityService, BlockedWordActivity::class.java
                ).apply {
                    flags = FLAG_ACTIVITY_NEW_TASK
                    putExtra("word", forbiddenWord)
                }
                startActivity(intentActivity)
            }
        }
    }

    private fun warmUpNsfwDetector() {
        if (nsfwDetector != null) return
        serviceScope.launch(nsfwDispatcher) {
            if (nsfwDetector == null) {
                nsfwDetector = NsfwContentDetector(applicationContext)
            }
        }
    }

    private fun maybeScanForNsfwContent(pkg: String) {
        if (nsfwScanInFlight) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNsfwScanElapsedMs < NSFW_SCAN_INTERVAL_MS) return
        lastNsfwScanElapsedMs = now
        nsfwScanInFlight = true

        serviceScope.launch {
            val mediaRegions = findNsfwMediaRegions()
            if (mediaRegions.isEmpty()) {
                nsfwScanInFlight = false
                return@launch
            }

            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    result.hardwareBuffer.close()

                    if (hardwareBitmap == null) {
                        Timber.w("NSFW scan: failed to wrap screenshot hardware buffer")
                        nsfwScanInFlight = false
                        return
                    }

                    serviceScope.launch(nsfwDispatcher) {
                        try {
                            val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBitmap.recycle()

                            val detector = nsfwDetector ?: NsfwContentDetector(applicationContext).also { nsfwDetector = it }

                            var maxUnsafeScore = 0f
                            for (region in mediaRegions) {
                                val crop = cropToBitmap(softwareBitmap, region) ?: continue

                                val startMs = SystemClock.elapsedRealtime()
                                val scores = detector.predict(crop)
                                val elapsedMs = SystemClock.elapsedRealtime() - startMs
                                crop.recycle()

                                val categories = nsfwCategories
                                val threshold = nsfwThreshold
                                val unsafeScore = detector.unsafeScore(scores, categories)
                                val scoresText = NsfwContentDetector.LABELS.zip(scores.toList())
                                    .joinToString { (label, score) -> "$label=${"%.3f".format(score)}" }
                                Timber.d("NSFW scan region=$region (${elapsedMs}ms): $scoresText, unsafe(${categories.joinToString("+")})=${"%.3f".format(unsafeScore)}")

                                if (unsafeScore > maxUnsafeScore) maxUnsafeScore = unsafeScore
                                if (unsafeScore > threshold) break
                            }
                            softwareBitmap.recycle()

                            if (maxUnsafeScore > nsfwThreshold) {
                                Timber.d("NSFW scan: blocking (max unsafe score=${"%.3f".format(maxUnsafeScore)})")
                                withContext(Dispatchers.Main) {
                                    if (currentPackage in nsfwTargetPackages) {
                                        suspendPackage(pkg)
                                        suspendPackage(pkg, suspend = false)
                                        showNsfwBlockedActivity()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "NSFW scan failed")
                        } finally {
                            nsfwScanInFlight = false
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Timber.w("NSFW scan: takeScreenshot failed, errorCode=$errorCode")
                    nsfwScanInFlight = false
                }
            })
        }
    }

    private fun findNsfwMediaRegions(): List<Rect> {
        val root = rootInActiveWindow ?: return emptyList()
        val minSizePx = (NSFW_MIN_MEDIA_SIZE_DP * resources.displayMetrics.density).toInt()
        val diagMinSizePx = (NSFW_DIAG_MIN_SIZE_DP * resources.displayMetrics.density).toInt()
        val regions = mutableListOf<Rect>()
        val fallbackCandidates = mutableListOf<Rect>()
        val diagnostics = mutableListOf<String>()

        fun traverse(node: AccessibilityNodeInfo?) {
            node ?: return
            val className = node.className?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val text = node.text?.toString().orEmpty()
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val bigEnough = rect.width() >= minSizePx && rect.height() >= minSizePx

            val matchesClass = NSFW_MEDIA_CLASS_KEYWORDS.any { className.contains(it, ignoreCase = true) }
            val matchesDescription = description.isNotEmpty() && (
                    NSFW_MEDIA_KEYWORDS.any { description.contains(it, ignoreCase = true) } ||
                            NSFW_MEDIA_DESCRIPTION_PATTERNS.any { it.containsMatchIn(description) }
                    )
            val matchesText = NSFW_MEDIA_KEYWORDS.any { text.startsWith(it, ignoreCase = true) }

            if ((matchesClass || matchesDescription || matchesText) && bigEnough && regions.none { it == rect }) {
                regions.add(rect)
            } else if (node.childCount == 0 && description.isEmpty() && text.isEmpty() && bigEnough) {
                val aspect = rect.width().toFloat() / rect.height().toFloat()
                if (aspect in NSFW_FALLBACK_MIN_ASPECT..NSFW_FALLBACK_MAX_ASPECT && fallbackCandidates.none { it == rect }) {
                    fallbackCandidates.add(rect)
                }
            }

            if (diagnostics.size < NSFW_DIAG_MAX_NODES && rect.width() >= diagMinSizePx && rect.height() >= diagMinSizePx) {
                diagnostics.add("class=$className bounds=$rect desc=\"${description.take(50)}\" text=\"${text.take(30)}\"")
            }

            for (i in 0 until node.childCount) traverse(node.getChild(i))
        }
        traverse(root)
        root.recycle()

        if (regions.isEmpty() && fallbackCandidates.isNotEmpty()) {
            val capped = fallbackCandidates
                .sortedByDescending { it.width().toLong() * it.height() }
                .take(NSFW_FALLBACK_MAX_CANDIDATES)
            Timber.d("NSFW scan: no labeled media found, using ${capped.size}/${fallbackCandidates.size} geometric fallback candidate(s): $capped")
            regions.addAll(capped)
        }

        if (regions.isEmpty()) {
            Timber.d("NSFW scan: no media regions found. Large nodes on screen (>=${NSFW_DIAG_MIN_SIZE_DP}dp):\n" + diagnostics.joinToString("\n"))
        } else {
            Timber.d("NSFW scan: found ${regions.size} candidate region(s): $regions")
        }
        return regions
    }

    private fun cropToBitmap(source: Bitmap, rect: Rect): Bitmap? {
        val left = rect.left.coerceIn(0, source.width)
        val top = rect.top.coerceIn(0, source.height)
        val right = rect.right.coerceIn(left, source.width)
        val bottom = rect.bottom.coerceIn(top, source.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null

        return try {
            Bitmap.createBitmap(source, left, top, width, height)
        } catch (e: Exception) {
            Timber.w(e, "NSFW scan: failed to crop region $rect")
            null
        }
    }

    private fun showNsfwBlockedActivity() {
        startActivity(Intent(this, NsfwContentBlockedActivity::class.java).apply {
            flags = FLAG_ACTIVITY_NEW_TASK
        })
    }

    private fun suspendPackages(packages: Array<String>, suspend: Boolean = true) {
        val dpm = applicationContext.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(applicationContext, DechainerDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) return
        dpm.setPackagesSuspended(admin, packages, suspend)
    }

    private fun suspendPackage(packageName: String, suspend: Boolean = true) = suspendPackages(arrayOf(packageName), suspend)

    private fun getControlledPackages(): Array<String> {
        return targetPackages
            .union(passiveForbiddenPatterns.keys)
            .union(limitPrefs.all.keys)
            .union(reopenPrefs.all.keys)
            .toTypedArray()
    }

    private fun checkForbiddenWord(text: String): String? {
        forbiddenPatterns.forEach { (word: String, regex: Regex) ->
            if (regex.containsMatchIn(text)) return word
        }
        return null
    }

    private fun startTracking(pkg: String) {
        val limitMinutes = limitPrefs.getInt(pkg, 0)
        val remainingSecondsReopening = getRemainingSecondsToReopen(pkg)
        if (limitMinutes <= 0 && remainingSecondsReopening <= 0) return

        val limitMillis = TimeUnit.MINUTES.toMillis(limitMinutes.toLong())

        if (limitMillis > 0) {
            val alreadyUsedMillis = usagePrefs.getLong(pkg, 0L)
            val remainingMillis = limitMillis - alreadyUsedMillis

            if (remainingMillis <= 0) {
                executeBlocking()
                return
            } else handler.postDelayed(blockRunnable, remainingMillis)
        }

        if (remainingSecondsReopening > 0)
            executeBlocking(reopening = true, remainingSeconds = remainingSecondsReopening)
    }

    private fun stopTrackingAndSave(screenOff: Boolean = false) {
        handler.removeCallbacks(blockRunnable)
        val pkg = currentPackage ?: return

        if (getRemainingSecondsToReopen(pkg) == 0 && !screenOff)
            lastClosedTimes[pkg] = SystemClock.elapsedRealtime()

        if (sessionStartTime == 0L) return
        if (limitPrefs.getInt(pkg, 0) == 0) return

        val currentSessionMillis = SystemClock.elapsedRealtime() - sessionStartTime
        val total = usagePrefs.getLong(pkg, 0L) + currentSessionMillis

        usagePrefs.edit { putLong(pkg, total) }
        sessionStartTime = 0
    }

    private fun executeBlocking(reopening: Boolean = false, remainingSeconds: Int = 0) {
        val pkg = currentPackage ?: return

        stopTrackingAndSave()
        currentPackage = null

        val appName = try {
            packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
        } catch (e: Exception) { pkg }

        val activityClass = if (reopening) ReopeningLimitActivity::class.java else TimeUpActivity::class.java
        val limit = if (reopening) remainingSeconds else limitPrefs.getInt(pkg, 0)

        startActivity(Intent(this, activityClass).apply {
            flags = FLAG_ACTIVITY_NEW_TASK
            putExtra("appName", appName)
            putExtra("limit", limit)
        })
    }

    private fun checkDateReset() {
        val today = LocalDate.now().toString()
        if (today != lastCheckDate) {
            usagePrefs.edit { clear() }
            lastCheckDate = today
        }
    }

    private fun getRemainingSecondsToReopen(pkg: String): Int {
        val reopenSeconds = reopenPrefs.getInt(pkg, 0)
        val lastClosedTime = lastClosedTimes.getOrDefault(pkg, 0L)
        if (reopenSeconds > 0 && lastClosedTime > 0) {
            val elapsed = SystemClock.elapsedRealtime() - lastClosedTime
            val elapsedSeconds = TimeUnit.MILLISECONDS.toSeconds(elapsed)
            if (elapsedSeconds <= reopenSeconds)
                return max(reopenSeconds - elapsedSeconds.toInt(), 0)
        }
        return 0
    }
}