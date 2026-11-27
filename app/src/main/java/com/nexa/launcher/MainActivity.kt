package com.nexa.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private const val BACKGROUND = 0xFF0D0F14.toInt()
private const val SURFACE = 0xFF171B24.toInt()
private const val SURFACE_HIGH = 0xFF202633.toInt()
private const val TEXT = 0xFFF5F6FC.toInt()
private const val MUTED = 0xFF929BAD.toInt()
private const val ACCENT = 0xFF9D8CFF.toInt()
private val NEXA_EASE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

data class LauncherApp(
    val label: String,
    val packageName: String
)

class MainActivity : Activity() {
    private lateinit var appAdapter: AppAdapter
    private lateinit var appList: RecyclerView
    private lateinit var search: EditText
    private lateinit var recentContainer: LinearLayout
    private lateinit var favoriteContainer: LinearLayout
    private lateinit var appCount: TextView
    private lateinit var categoryTabs: LinearLayout
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var batteryLabel: TextView
    private lateinit var launcherRoot: LinearLayout
    private var selectedBackground = BACKGROUND
    private var selectedAccent = ACCENT
    private var batteryReceiverRegistered = false
    private var packageReceiverRegistered = false
    private val batteryChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateBatteryStatus(intent)
        }
    }
    private var allApps: List<LauncherApp> = emptyList()
    private var currentQuery = ""
    private var currentCategory = "All Apps"
    private val categories = listOf("All Apps", "Games", "Movies & TV", "Music", "Tools")
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val packageRemovedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED &&
                !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            ) {
                loadApps()
            }
        }
    }
    private val clockTick = object : Runnable {
        override fun run() {
            if (::clock.isInitialized) {
                clock.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
                date.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
                mainHandler.postDelayed(this, 30_000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply { preferredRefreshRate = 120f }
        }
        launcherRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(selectedBackground)
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val brand = TextView(this).apply {
            text = "NEXA"
            textSize = 25f
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = 0.12f
        }

        val settings = TextView(this).apply {
            text = "⚙"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(TEXT)
            background = roundedBackground(SURFACE, 15)
            setOnClickListener { showSettingsScreen() }
            setSmoothPressFeedback()
            setOnLongClickListener {
                showSettingsScreen()
                true
            }
        }

        top.addView(brand, LinearLayout.LayoutParams(0, dp(44), 1f))
        top.addView(settings, LinearLayout.LayoutParams(dp(44), dp(44)))
        launcherRoot.addView(top)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(SURFACE, 22)
            setPadding(dp(20), dp(17), dp(20), dp(17))
        }

        clock = TextView(this).apply {
            text = "9:41"
            textSize = 43f
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
        }

        date = TextView(this).apply {
            text = "Today"
            textSize = 13f
            setTextColor(MUTED)
        }

        hero.addView(clock)
        val heroInfo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroInfo.addView(date, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        batteryLabel = TextView(this).apply {
            text = "Battery --%"
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            background = roundedBackground(SURFACE_HIGH, 12)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        heroInfo.addView(batteryLabel)
        hero.addView(heroInfo, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(5) })
        launcherRoot.addView(hero, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(18)
            bottomMargin = dp(18)
        })
        hero.alpha = 0f
        hero.translationY = dp(7).toFloat()

        search = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(MUTED)
            setTextColor(TEXT)
            textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = roundedBackground(SURFACE_HIGH, 16)
            setPadding(dp(16), 0, dp(16), 0)
        }
        launcherRoot.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52)
        ).apply { bottomMargin = dp(20) })

        launcherRoot.addView(sectionHeader("Favorites", "Hold an app to manage it"))
        favoriteContainer = horizontalStrip()
        launcherRoot.addView(favoriteContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48)
        ).apply {
            topMargin = dp(9)
            bottomMargin = dp(18)
        })

        launcherRoot.addView(sectionHeader("Recently opened", ""))
        recentContainer = horizontalStrip()
        launcherRoot.addView(recentContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48)
        ).apply {
            topMargin = dp(9)
            bottomMargin = dp(18)
        })

        val appsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appsHeader.addView(
            sectionTitle("All apps"),
            LinearLayout.LayoutParams(0, dp(32), 1f)
        )
        appCount = TextView(this).apply {
            text = "Loading"
            textSize = 12f
            setTextColor(MUTED)
        }
        appsHeader.addView(appCount)
        launcherRoot.addView(appsHeader)

        val categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        categoryTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        categoryScroll.addView(categoryTabs)
        launcherRoot.addView(categoryScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(42)
        ).apply { topMargin = dp(5) })

        buildCategoryTabs()

        appAdapter = AppAdapter(this, { app -> launchApp(app) }, { app -> showAppActions(app) })
        appList = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, gridColumns())
            adapter = appAdapter
            setHasFixedSize(true)
            itemAnimator = null
            setItemViewCacheSize(12)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        launcherRoot.addView(appList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { topMargin = dp(8) })
        appList.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            updateGridColumns(view.width)
        }

        setContentView(launcherRoot)
        applySavedAppearance()
        window.decorView.post { if (!isFinishing && !isDestroyed) hideSystemBars() }

        hero.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(260)
            .setInterpolator(NEXA_EASE)
            .start()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString().orEmpty()
                showFilteredApps()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                appList.requestFocus()
                true
            } else false
        }

        val packageFilter = IntentFilter(Intent.ACTION_PACKAGE_REMOVED).apply {
            addDataScheme("package")
        }
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent = try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(packageRemovedReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED)
                packageReceiverRegistered = true
                registerReceiver(batteryChangedReceiver, batteryFilter, Context.RECEIVER_NOT_EXPORTED).also {
                    batteryReceiverRegistered = true
                }
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(packageRemovedReceiver, packageFilter)
                packageReceiverRegistered = true
                @Suppress("DEPRECATION")
                registerReceiver(batteryChangedReceiver, batteryFilter).also {
                    batteryReceiverRegistered = true
                }
            }
        } catch (_: Exception) {
            null
        }
        updateBatteryStatus(batteryIntent)

        clockTick.run()
        loadApps()
    }

    private fun updateBatteryStatus(intent: Intent?) {
        if (!::batteryLabel.isInitialized) return
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) {
            batteryLabel.text = "Battery --%"
            return
        }
        val percent = (level * 100f / scale).toInt()
        val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
        batteryLabel.text = if (charging) "⚡ $percent%" else "Battery $percent%"
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    override fun onResume() {
        super.onResume()
        if (::recentContainer.isInitialized && allApps.isNotEmpty()) {
            renderQuickSections()
        }
        if (::clock.isInitialized) {
            mainHandler.removeCallbacks(clockTick)
            clockTick.run()
        }
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(clockTick)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (packageReceiverRegistered) {
            unregisterReceiver(packageRemovedReceiver)
            packageReceiverRegistered = false
        }
        if (batteryReceiverRegistered) {
            unregisterReceiver(batteryChangedReceiver)
            batteryReceiverRegistered = false
        }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun loadApps() {
        appCount.text = "Loading"
        worker.execute {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val loaded = packageManager.queryIntentActivities(intent, 0)
                .map {
                    LauncherApp(
                        it.loadLabel(packageManager).toString(),
                        it.activityInfo.packageName
                    )
                }
                .distinctBy { it.packageName }
                .filter {
                    it.packageName != this@MainActivity.packageName &&
                        it.packageName != "com.android.settings"
                }
                .sortedBy { it.label.lowercase(Locale.getDefault()) }

            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                allApps = loaded
                showFilteredApps()
                renderQuickSections()
            }
        }
    }

    private fun showFilteredApps(animate: Boolean = false) {
        val query = currentQuery.trim()
        val hidden = getHiddenPackages()
        val visibleApps = allApps.filter { it.packageName !in hidden }
        val categoryApps = visibleApps.filter { matchesCategory(it, currentCategory) }
        val filtered = if (query.isBlank()) categoryApps else categoryApps.filter {
            it.label.contains(query, true) || it.packageName.contains(query, true)
        }
        appAdapter.submitList(filtered, animate)
        appCount.text = if (query.isBlank()) {
            "${filtered.size} apps"
        } else {
            "${filtered.size} results"
        }
    }

    private fun buildCategoryTabs() {
        categoryTabs.removeAllViews()
        categories.forEach { category ->
            val selected = category == currentCategory
            val tab = TextView(this).apply {
                text = category
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (selected) BACKGROUND else TEXT)
                gravity = Gravity.CENTER
                background = roundedBackground(if (selected) selectedAccent else SURFACE_HIGH, 18)
                setPadding(dp(15), 0, dp(15), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    currentCategory = category
                    buildCategoryTabs()
                    showFilteredApps(animate = true)
                }
                setSmoothPressFeedback()
            }
            categoryTabs.addView(tab, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34)
            ).apply { marginEnd = dp(8) })
        }
    }

    private fun matchesCategory(app: LauncherApp, category: String): Boolean {
        if (category == "All Apps") return true
        val label = app.label.lowercase(Locale.ROOT)
        val pkg = app.packageName.lowercase(Locale.ROOT)
        val identity = "$label $pkg"
        val appCategory = try {
            packageManager.getApplicationInfo(app.packageName, 0).category
        } catch (_: Exception) {
            android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED
        }
        val gameKeywords = listOf(
            "game", "games", "minecraft", "roblox", "pubg", "mobile legends",
            "freefire", "free fire", "garena", "genshin", "asphalt", "callofduty",
            "call of duty", "clash", "among us", "stumble", "brawl stars",
            "fortnite", "pokemon", "wild rift", "league of legends", "honor of kings"
        )
        val videoKeywords = listOf(
            "netflix", "youtube", "youtube tv", "disney", "prime video",
            "amazon video", "hbo", "plex", "vlc", "mx player", "movie",
            "movies", "film", "streaming", "video player", "iqiyi", "viu",
            "wetv", "trueid", "one31", "viaplay", "tubi", "crunchyroll"
        )
        val musicKeywords = listOf(
            "spotify", "youtube music", "music", "audio", "soundcloud",
            "apple music", "tidal", "deezer", "shazam", "podcast", "radio"
        )
        val toolKeywords = listOf(
            "calculator", "calendar", "clock", "file manager", "files",
            "weather", "notes", "notepad", "recorder", "contacts",
            "camera", "scanner", "compass", "browser", "downloads",
            "keyboard", "cleaner"
        )
        val isGame = appCategory == android.content.pm.ApplicationInfo.CATEGORY_GAME ||
            gameKeywords.any { identity.contains(it) }
        if (category == "Games") return isGame
        if (isGame) return false
        return when (category) {
            "Movies & TV" -> videoKeywords.any { identity.contains(it) }
            "Music" -> musicKeywords.any { identity.contains(it) }
            "Tools" -> toolKeywords.any { identity.contains(it) }
            else -> true
        }
    }

    private fun renderQuickSections() {
        renderAppStrip(favoriteContainer, getFavorites(), "Pin apps here")
        val recent = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
            .getString("recent_apps", "")
            .orEmpty()
            .split("|")
            .filter { it.isNotBlank() }
            .mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
            .take(8)
        renderAppStrip(recentContainer, recent, "Your recent apps appear here")
    }

    private fun renderAppStrip(container: LinearLayout, apps: List<LauncherApp>, emptyText: String) {
        container.removeAllViews()
        if (apps.isEmpty()) {
            container.addView(TextView(this).apply {
                text = emptyText
                textSize = 12f
                setTextColor(MUTED)
                gravity = Gravity.CENTER_VERTICAL
            })
            return
        }
        apps.forEach { app ->
            val chip = TextView(this).apply {
                text = app.label
                textSize = 12f
                setTextColor(TEXT)
                maxLines = 1
                gravity = Gravity.CENTER
                background = roundedBackground(SURFACE_HIGH, 14)
                setPadding(dp(15), 0, dp(15), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { launchApp(app) }
                setSmoothPressFeedback()
                setOnLongClickListener {
                    showAppActions(app)
                    true
                }
            }
            container.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40)
            ).apply { marginEnd = dp(8) })
        }
    }

    private fun getFavorites(): List<LauncherApp> {
        val favorites = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
            .getString("favorite_apps", "")
            .orEmpty()
            .split("|")
            .filter { it.isNotBlank() }
        return favorites.mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
    }

    private fun getHiddenPackages(): Set<String> {
        return getSharedPreferences("nexa_launcher", MODE_PRIVATE)
            .getString("hidden_apps", "")
            .orEmpty()
            .split("|")
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun hideApp(app: LauncherApp) {
        val hidden = getHiddenPackages().toMutableSet()
        hidden.add(app.packageName)
        getSharedPreferences("nexa_launcher", MODE_PRIVATE).edit()
            .putString("hidden_apps", hidden.joinToString("|"))
            .apply()
        showFilteredApps()
    }

    private fun showHiddenApps() {
        val hidden = getHiddenPackages()
            .mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
        if (hidden.isEmpty()) {
            showMessage("No hidden apps.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Hidden apps")
            .setItems(hidden.map { it.label }.toTypedArray()) { _, index ->
                val selected = hidden[index]
                val updated = getHiddenPackages().toMutableSet()
                updated.remove(selected.packageName)
                getSharedPreferences("nexa_launcher", MODE_PRIVATE).edit()
                    .putString("hidden_apps", updated.joinToString("|"))
                    .apply()
                showFilteredApps()
                showHiddenApps()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun toggleFavorite(app: LauncherApp) {
        val favorites = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
            .getString("favorite_apps", "")
            .orEmpty()
            .split("|")
            .filter { it.isNotBlank() }
            .toMutableList()
        if (app.packageName in favorites) {
            favorites.remove(app.packageName)
        } else {
            favorites.add(0, app.packageName)
        }
        getSharedPreferences("nexa_launcher", MODE_PRIVATE).edit()
            .putString("favorite_apps", favorites.distinct().take(12).joinToString("|"))
            .apply()
        renderQuickSections()
    }

    private fun launchApp(app: LauncherApp) {
        val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
        val recent = prefs.getString("recent_apps", "")
            .orEmpty()
            .split("|")
            .filter { it.isNotBlank() && it != app.packageName }
            .toMutableList()
        recent.add(0, app.packageName)
        prefs.edit().putString("recent_apps", recent.take(8).joinToString("|")).apply()

        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent != null) {
            renderQuickSections()
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }
    }

    private fun showAppActions(app: LauncherApp) {
        val favorite = getFavorites().any { it.packageName == app.packageName }
        val options = arrayOf(
            if (favorite) "Remove from favorites" else "Add to favorites",
            "App info",
            "Uninstall",
            "Hide app"
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleFavorite(app)
                    1 -> openAppInfo(app)
                    2 -> uninstallApp(app)
                    3 -> hideApp(app)
                }
            }
            .show()
    }

    private fun openAppInfo(app: LauncherApp) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${app.packageName}")
            })
        } catch (_: Exception) {
            showMessage("Unable to open app info.")
        }
    }

    private fun uninstallApp(app: LauncherApp) {
        try {
            val uninstallIntent = Intent(
                Intent.ACTION_UNINSTALL_PACKAGE,
                Uri.parse("package:${app.packageName}")
            ).apply {
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            startActivity(uninstallIntent)
        } catch (_: Exception) {
            showMessage("Android could not open the uninstall screen. Try App info, then Uninstall.")
        }
    }

    private fun openAndroidSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: Exception) {
            showMessage("Android Settings could not be opened.")
        }
    }

    private fun showSettingsScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            setBackgroundColor(selectedBackground)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            background = roundedBackground(SURFACE, 15)
            setOnClickListener { setContentView(launcherRoot) }
            setSmoothPressFeedback()
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        heading.addView(TextView(this).apply {
            text = "Settings"
            textSize = 25f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(TEXT)
        })
        heading.addView(TextView(this).apply {
            text = "Make NEXA feel like yours"
            textSize = 12f
            setTextColor(MUTED)
        })
        header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val done = TextView(this).apply {
            text = "Done"
            textSize = 13f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            background = roundedBackground(selectedAccent, 14)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { setContentView(launcherRoot) }
            setSmoothPressFeedback()
        }
        header.addView(done)
        page.addView(header)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(22), 0, dp(20))
        }
        fun section(name: String, detail: String) {
            content.addView(TextView(this).apply {
                text = name
                textSize = 17f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(TEXT)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(18)
                bottomMargin = dp(4)
            })
            content.addView(TextView(this).apply {
                text = detail
                textSize = 12f
                setTextColor(MUTED)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }
        fun item(title: String, detail: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBackground(SURFACE, 16)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
                setSmoothPressFeedback()
            }
            row.addView(TextView(this).apply {
                text = title
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(TEXT)
            })
            row.addView(TextView(this).apply {
                text = detail
                textSize = 11f
                setTextColor(MUTED)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
            content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(9)
            })
        }
        section("Personalization", "Change the look, colors, and wallpaper")
        item("Appearance studio", "Wallpapers, colors, transparency, and styles") { showAppearanceSettings() }
        section("Launcher", "Manage your apps and home screen")
        item("Refresh apps", "Rescan installed applications") { loadApps(); showMessage("Refreshing apps…") }
        item("Hidden apps", "Choose which apps appear in NEXA") { showHiddenApps() }
        item("Default home app", "Choose NEXA as your Android launcher") {
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (_: Exception) {
                showMessage("Open Android Settings and choose Default apps, then Home app.")
            }
        }
        section("System", "Android settings and information")
        item("Android Settings", "Open your device settings") { openAndroidSettings() }
        item("About NEXA", "Version 0.4.0 · Lightweight Android launcher") {
            showMessage("NEXA Launcher is designed to keep your home screen fast, smooth, and customizable.")
        }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(page)
    }

    private fun showAppearanceSettings() {
        val options = arrayOf(
            "Choose wallpaper",
            "Background color",
            "Accent color",
            "Wallpaper visibility",
            "Custom background color",
            "Custom accent color",
            "Remove wallpaper"
        )
        AlertDialog.Builder(this)
            .setTitle("Customize appearance")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "image/*"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        }
                        try {
                            startActivityForResult(intent, 4101)
                        } catch (_: Exception) {
                            showMessage("No image picker is available.")
                        }
                    }
                    1 -> showColorChoices(
                        "Background color",
                        listOf(
                            "Midnight" to 0xFF0D0F14.toInt(),
                            "Deep purple" to 0xFF171126.toInt(),
                            "Ocean" to 0xFF0B1722.toInt(),
                            "Forest" to 0xFF0D1A16.toInt(),
                            "Warm charcoal" to 0xFF211A18.toInt()
                        )
                    ) { color ->
                        selectedBackground = color
                        getSharedPreferences("nexa_launcher", MODE_PRIVATE).edit()
                            .putInt("background_color", color)
                            .apply()
                        applySavedAppearance()
                    }
                    2 -> showColorChoices(
                        "Accent color",
                        listOf(
                            "Violet" to 0xFF9D8CFF.toInt(),
                            "Cyan" to 0xFF54D6E8.toInt(),
                            "Mint" to 0xFF65D6A4.toInt(),
                            "Orange" to 0xFFFFAD66.toInt(),
                            "Pink" to 0xFFFF82B2.toInt()
                        )
                    ) { color ->
                        selectedAccent = color
                        getSharedPreferences("nexa_launcher", MODE_PRIVATE).edit()
                            .putInt("accent_color", color)
                            .apply()
                        buildCategoryTabs()
                    }
                    3 -> showColorChoices(
                        "Wallpaper visibility",
                        listOf("Subtle" to 45, "Balanced" to 82, "Vivid" to 135, "Strong" to 190)
                            .map { it.first to it.second }
                    ) { alpha ->
                        getSharedPreferences("nexa_launcher", MODE_PRIVATE).edit()
                            .putInt("wallpaper_alpha", alpha)
                            .apply()
                        applySavedAppearance()
                    }
                    4 -> showCustomColor("Custom background color", selectedBackground) { color ->
                        selectedBackground = color
                        getSharedPreferences("nexa_launcher", MODE_PRIVATE).edit()
                            .putInt("background_color", color)
                            .apply()
                        applySavedAppearance()
                    }
                    5 -> showCustomColor("Custom accent color", selectedAccent) { color ->
                        selectedAccent = color
                        getSharedPreferences("nexa_launcher", MODE_PRIVATE).edit()
                            .putInt("accent_color", color)
                            .apply()
                        buildCategoryTabs()
                    }
                    6 -> {
                        getSharedPreferences("nexa_launcher", MODE_PRIVATE).edit()
                            .remove("wallpaper_uri")
                            .apply()
                        applySavedAppearance()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showCustomColor(title: String, current: Int, onSelected: (Int) -> Unit) {
        val field = EditText(this).apply {
            hint = "#RRGGBB"
            setSingleLine(true)
            setText(String.format("#%06X", 0xFFFFFF and current))
            setTextColor(TEXT)
            setHintTextColor(MUTED)
        }
        val wrapper = LinearLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrapper)
            .setPositiveButton("Apply") { _, _ ->
                try {
                    val value = field.text.toString().trim()
                    val color = Color.parseColor(if (value.startsWith("#")) value else "#$value")
                    onSelected(color)
                } catch (_: Exception) {
                    showMessage("Enter a valid color such as #8B7CFF.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showColorChoices(title: String, colors: List<Pair<String, Int>>, onSelected: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(colors.map { it.first }.toTypedArray()) { _, which ->
                onSelected(colors[which].second)
            }
            .show()
    }

    private fun applySavedAppearance() {
        val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
        selectedBackground = prefs.getInt("background_color", BACKGROUND)
        selectedAccent = prefs.getInt("accent_color", ACCENT)
        val uriText = prefs.getString("wallpaper_uri", null)
        if (uriText.isNullOrBlank()) {
            launcherRoot.setBackgroundColor(selectedBackground)
            return
        }
        try {
            val uri = Uri.parse(uriText)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val maxDimension = 1600
            var sample = 1
            while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
                sample *= 2
            }
            val bitmap = BitmapFactory.Options().let {
                it.inSampleSize = sample
                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, it)
                }
            }
            if (bitmap == null) {
                launcherRoot.setBackgroundColor(selectedBackground)
                return
            }
            val image = BitmapDrawable(resources, bitmap).apply { alpha = prefs.getInt("wallpaper_alpha", 82) }
            launcherRoot.background = LayerDrawable(arrayOf(ColorDrawable(selectedBackground), image))
        } catch (_: Exception) {
            launcherRoot.setBackgroundColor(selectedBackground)
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 4101 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            getSharedPreferences("nexa_launcher", MODE_PRIVATE).edit()
                .putString("wallpaper_uri", uri.toString())
                .apply()
            applySavedAppearance()
        } catch (_: Exception) {
            showMessage("Unable to use that image. Try choosing another picture.")
        }
    }

    private fun showMessage(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun sectionHeader(title: String, detail: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(sectionTitle(title), LinearLayout.LayoutParams(0, dp(28), 1f))
        if (detail.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = detail
                textSize = 10f
                setTextColor(MUTED)
            })
        }
        return row
    }

    private fun horizontalStrip() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun roundedBackground(color: Int, radius: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun gridColumns(): Int {
        val width = resources.displayMetrics.widthPixels - dp(40)
        return (width / dp(112)).coerceIn(3, 7)
    }

    private fun updateGridColumns(widthPx: Int) {
        if (widthPx <= 0 || !::appAdapter.isInitialized) return
        val columns = (widthPx / dp(112)).coerceIn(3, 7)
        val manager = appList.layoutManager as? GridLayoutManager ?: return
        if (manager.spanCount != columns) manager.spanCount = columns
        appAdapter.updateCellWidth(widthPx / columns)
    }
}

private class AppAdapter(
    private val context: Context,
    private val onClick: (LauncherApp) -> Unit,
    private val onLongClick: (LauncherApp) -> Unit
) : RecyclerView.Adapter<AppAdapter.AppHolder>() {
    private val items = ArrayList<LauncherApp>()
    private val iconCache = object : LruCache<String, Drawable.ConstantState>(96) {}
    private val executor = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())
    private var animationGeneration = 0
    private var cellWidthPx = context.dp(112)
    private var iconSizePx = context.dp(48)

    fun updateCellWidth(width: Int) {
        val nextWidth = width.coerceAtLeast(context.dp(80))
        val nextIconSize = (nextWidth * 0.52f).toInt().coerceIn(context.dp(44), context.dp(66))
        if (cellWidthPx == nextWidth && iconSizePx == nextIconSize) return
        cellWidthPx = nextWidth
        iconSizePx = nextIconSize
        notifyDataSetChanged()
    }

    init {
        setHasStableIds(true)
    }

    fun submitList(apps: List<LauncherApp>, animate: Boolean = false) {
        val next = apps.toList()
        if (animate) {
            animationGeneration++
            items.clear()
            items.addAll(next)
            notifyDataSetChanged()
            return
        }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                items[oldItemPosition].packageName == next[newItemPosition].packageName
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                items[oldItemPosition] == next[newItemPosition]
        })
        items.clear()
        items.addAll(next)
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemId(position: Int): Long =
        items[position].packageName.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppHolder {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(context.dp(5), context.dp(9), context.dp(5), context.dp(8))
            minimumHeight = (cellWidthPx * 0.88f).toInt()
            background = context.roundedBackground(SURFACE, 16)
            isClickable = true
            isFocusable = true
        }

        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(icon, LinearLayout.LayoutParams(iconSizePx, iconSizePx))

        val label = TextView(context).apply {
            textSize = 11f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(label, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.dp(34)
        ).apply { topMargin = context.dp(7) })

        return AppHolder(root, icon, label)
    }

    override fun onBindViewHolder(holder: AppHolder, position: Int) {
        val app = items[position]
        holder.label.text = app.label
        holder.icon.layoutParams = holder.icon.layoutParams.apply {
            width = iconSizePx
            height = iconSizePx
        }
        holder.itemView.minimumHeight = (cellWidthPx * 0.88f).toInt()
        holder.icon.setImageDrawable(null)
        holder.itemView.tag = app.packageName
        holder.itemView.animate().cancel()
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
        if (holder.animationGeneration != animationGeneration && position < 24) {
            holder.animationGeneration = animationGeneration
            holder.itemView.alpha = 0f
            holder.itemView.translationY = context.dp(9).toFloat()
            holder.itemView.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(position * 18L)
                .setDuration(250)
                .setInterpolator(NEXA_EASE)
                .start()
        } else {
            holder.animationGeneration = animationGeneration
            holder.itemView.alpha = 1f
            holder.itemView.translationY = 0f
        }
        holder.itemView.setOnClickListener { onClick(app) }
        holder.itemView.setSmoothPressFeedback()
        holder.itemView.setOnLongClickListener {
            onLongClick(app)
            true
        }

        val cached = iconCache.get(app.packageName)
        if (cached != null) {
            holder.icon.setImageDrawable(cached.newDrawable(context.resources).mutate())
        } else {
            val packageName = app.packageName
            executor.execute {
                val state = try {
                    context.packageManager.getApplicationIcon(packageName).constantState
                } catch (_: Exception) {
                    null
                }
                if (state != null) iconCache.put(packageName, state)
                handler.post {
                    if (holder.itemView.tag == packageName && state != null) {
                        holder.icon.setImageDrawable(state.newDrawable(context.resources).mutate())
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: AppHolder) {
        holder.itemView.animate().cancel()
        holder.itemView.tag = null
        holder.itemView.alpha = 1f
        holder.itemView.translationY = 0f
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
        holder.icon.setImageDrawable(null)
        super.onViewRecycled(holder)
    }

    class AppHolder(
        itemView: View,
        val icon: ImageView,
        val label: TextView
    ) : RecyclerView.ViewHolder(itemView) {
        var animationGeneration = -1
    }
}

private fun View.setSmoothPressFeedback() {
    setOnTouchListener { target, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                target.animate().cancel()
                target.animate()
                    .scaleX(0.97f)
                    .scaleY(0.97f)
                    .setDuration(90)
                    .setInterpolator(NEXA_EASE)
                    .start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                target.animate().cancel()
                target.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(190)
                    .setInterpolator(NEXA_EASE)
                    .start()
            }
        }
        false
    }
}

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

private fun Context.roundedBackground(color: Int, radius: Int) =
    android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
