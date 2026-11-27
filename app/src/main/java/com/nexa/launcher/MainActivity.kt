package com.nexa.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
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
    private var batteryReceiverRegistered = false
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
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.show(android.view.WindowInsets.Type.statusBars())
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
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
            setOnClickListener { openAndroidSettings() }
            setSmoothPressFeedback()
            setOnLongClickListener {
                showSettings()
                true
            }
        }

        top.addView(brand, LinearLayout.LayoutParams(0, dp(44), 1f))
        top.addView(settings, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(top)

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
        root.addView(hero, LinearLayout.LayoutParams(
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
        root.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52)
        ).apply { bottomMargin = dp(20) })

        root.addView(sectionHeader("Favorites", "Hold an app to manage it"))
        favoriteContainer = horizontalStrip()
        root.addView(favoriteContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48)
        ).apply {
            topMargin = dp(9)
            bottomMargin = dp(18)
        })

        root.addView(sectionHeader("Recently opened", ""))
        recentContainer = horizontalStrip()
        root.addView(recentContainer, LinearLayout.LayoutParams(
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
        root.addView(appsHeader)

        val categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        categoryTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        categoryScroll.addView(categoryTabs)
        root.addView(categoryScroll, LinearLayout.LayoutParams(
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
        root.addView(appList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { topMargin = dp(8) })

        setContentView(root)

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
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageRemovedReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(batteryChangedReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(packageRemovedReceiver, packageFilter)
            @Suppress("DEPRECATION")
            registerReceiver(batteryChangedReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        batteryReceiverRegistered = true

        clockTick.run()
        loadApps()
    }

    private fun updateBatteryStatus(intent: Intent?) {
        if (!::batteryLabel.isInitialized || intent == null) return
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            batteryLabel.text = "Battery --%"
            return
        }
        val percent = (level * 100f / scale).toInt()
        val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
        batteryLabel.text = if (charging) "⚡ $percent%" else "Battery $percent%"
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
        unregisterReceiver(packageRemovedReceiver)
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
                background = roundedBackground(if (selected) ACCENT else SURFACE_HIGH, 18)
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

    private fun showSettings() {
        val options = arrayOf(
            "Android Settings",
            "Refresh apps",
            "Manage hidden apps",
            "Manage default home app",
            "About NEXA"
        )
        AlertDialog.Builder(this)
            .setTitle("NEXA settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openAndroidSettings()
                    1 -> loadApps()
                    2 -> showHiddenApps()
                    3 -> {
                        try {
                            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                        } catch (_: Exception) {
                            showMessage("Open Android Settings and choose Default apps, then Home app.")
                        }
                    }
                    4 -> AlertDialog.Builder(this)
                        .setTitle("NEXA Launcher")
                        .setMessage("A lightweight Android home screen focused on speed, clarity, and control. Version 0.3.0")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .show()
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
        val width = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (width / 104f).toInt().coerceIn(3, 6)
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
            setPadding(context.dp(5), context.dp(10), context.dp(5), context.dp(8))
            background = context.roundedBackground(SURFACE, 16)
            isClickable = true
            isFocusable = true
        }

        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(icon, LinearLayout.LayoutParams(context.dp(46), context.dp(46)))

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
                .setStartDelay(position * 22L)
                .setDuration(220)
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
