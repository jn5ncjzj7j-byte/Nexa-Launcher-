package com.nexa.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import java.util.concurrent.Executors

private const val BACKGROUND = 0xFF101116.toInt()
private const val SURFACE = 0xFF1B1E27.toInt()
private const val TEXT = 0xFFF4F5FA.toInt()
private const val MUTED = 0xFF9296A5.toInt()
private const val ACCENT = 0xFF9B8CFF.toInt()

data class LauncherApp(
    val label: String,
    val packageName: String
)

class MainActivity : Activity() {
    private lateinit var appAdapter: AppAdapter
    private lateinit var appList: RecyclerView
    private lateinit var search: EditText
    private lateinit var recentContainer: LinearLayout
    private var allApps: List<LauncherApp> = emptyList()
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(dp(22), dp(20), dp(22), dp(12))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val brand = TextView(this).apply {
            text = "NEXA"
            textSize = 27f
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = 0.08f
        }

        val settings = TextView(this).apply {
            text = "⋮"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(TEXT)
            setPadding(dp(16), 0, dp(4), 0)
            setOnClickListener { showSettings() }
        }

        top.addView(brand, LinearLayout.LayoutParams(0, dp(42), 1f))
        top.addView(settings, LinearLayout.LayoutParams(dp(42), dp(42)))
        root.addView(top)

        val subtitle = TextView(this).apply {
            text = "Your home, made lighter."
            textSize = 13f
            setTextColor(MUTED)
        }
        root.addView(subtitle)

        search = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(MUTED)
            setTextColor(TEXT)
            textSize = 15f
            singleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = roundedBackground(SURFACE, 14)
            setPadding(dp(16), 0, dp(16), 0)
        }
        val searchParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(50)
        ).apply {
            topMargin = dp(22)
            bottomMargin = dp(22)
        }
        root.addView(search, searchParams)

        val recentTitle = sectionTitle("Recently opened")
        root.addView(recentTitle)

        val recentScroll = ScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
        }
        recentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        recentScroll.addView(recentContainer)
        root.addView(
            recentScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(20)
            }
        )

        val appsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appsHeader.addView(
            sectionTitle("All apps"),
            LinearLayout.LayoutParams(0, dp(30), 1f)
        )
        val appCount = TextView(this).apply {
            text = "Loading"
            textSize = 12f
            setTextColor(MUTED)
            tag = "app_count"
        }
        appsHeader.addView(appCount)
        root.addView(appsHeader)

        appAdapter = AppAdapter(this) { app -> launchApp(app) }
        appList = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, gridColumns())
            adapter = appAdapter
            setHasFixedSize(true)
            itemAnimator = null
            setItemViewCacheSize(10)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        root.addView(
            appList,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(8)
            }
        )

        setContentView(root)

        search.addTextChangedListener(SimpleTextWatcher { query ->
            showFilteredApps(query)
        })

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        if (::recentContainer.isInitialized && allApps.isNotEmpty()) {
            renderRecentApps()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    private fun loadApps() {
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
                .sortedBy { it.label.lowercase(Locale.getDefault()) }

            mainHandler.post {
                allApps = loaded
                appAdapter.submitList(loaded)
                findViewWithTag<TextView>("app_count")?.text = loaded.size.toString()
                renderRecentApps()
            }
        }
    }

    private fun showFilteredApps(query: String) {
        val filtered = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { it.label.contains(query.trim(), true) }
        }
        appAdapter.submitList(filtered)
        findViewWithTag<TextView>("app_count")?.text = filtered.size.toString()
    }

    private fun renderRecentApps() {
        recentContainer.removeAllViews()
        val recent = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
            .getString("recent_apps", "")
            .orEmpty()
            .split("|")
            .filter { it.isNotBlank() }
            .mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
            .take(8)

        if (recent.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Apps you open will appear here"
                textSize = 12f
                setTextColor(MUTED)
            }
            recentContainer.addView(empty)
            return
        }

        recent.forEach { app ->
            val chip = TextView(this).apply {
                text = app.label
                textSize = 12f
                setTextColor(TEXT)
                maxLines = 1
                background = roundedBackground(SURFACE, 18)
                setPadding(dp(15), dp(10), dp(15), dp(10))
                setOnClickListener { launchApp(app) }
            }
            recentContainer.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(40)
                ).apply {
                    marginEnd = dp(8)
                }
            )
        }
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
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }
    }

    private fun showSettings() {
        val options = arrayOf("Refresh app list", "About NEXA Launcher")
        AlertDialog.Builder(this)
            .setTitle("NEXA Launcher")
            .setItems(options) { _, which ->
                if (which == 0) {
                    loadApps()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("NEXA Launcher")
                        .setMessage("A lightweight home screen built for everyday Android devices.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .show()
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 17f
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
        return (width / 112f).toInt().coerceIn(3, 6)
    }

    private fun <T : View> findViewWithTag(tagValue: String): T? {
        fun search(view: View): T? {
            if (view.tag == tagValue) return view as? T
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    val found = search(view.getChildAt(index))
                    if (found != null) return found
                }
            }
            return null
        }
        return search(window.decorView)
    }
}

private class AppAdapter(
    private val context: Context,
    private val onClick: (LauncherApp) -> Unit
) : RecyclerView.Adapter<AppAdapter.AppHolder>() {
    private val items = ArrayList<LauncherApp>()
    private val iconCache = object : LruCache<String, Drawable.ConstantState>(48) {}
    private val executor = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())

    init {
        setHasStableIds(true)
    }

    fun submitList(apps: List<LauncherApp>) {
        items.clear()
        items.addAll(apps)
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long =
        items[position].packageName.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppHolder {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(context.dp(6), context.dp(10), context.dp(6), context.dp(8))
            background = context.roundedBackground(SURFACE, 14)
            isClickable = true
            isFocusable = true
        }

        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(
            icon,
            LinearLayout.LayoutParams(context.dp(48), context.dp(48))
        )

        val label = TextView(context).apply {
            textSize = 11f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(34)
            ).apply {
                topMargin = context.dp(7)
            }
        )

        return AppHolder(root, icon, label)
    }

    override fun onBindViewHolder(holder: AppHolder, position: Int) {
        val app = items[position]
        holder.label.text = app.label
        holder.icon.setImageDrawable(null)
        holder.itemView.tag = app.packageName
        holder.itemView.setOnClickListener { onClick(app) }

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

                if (state != null) {
                    iconCache.put(packageName, state)
                }

                handler.post {
                    if (holder.itemView.tag == packageName && state != null) {
                        holder.icon.setImageDrawable(
                            state.newDrawable(context.resources).mutate()
                        )
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: AppHolder) {
        holder.itemView.tag = null
        holder.icon.setImageDrawable(null)
        super.onViewRecycled(holder)
    }

    class AppHolder(
        itemView: View,
        val icon: ImageView,
        val label: TextView
    ) : RecyclerView.ViewHolder(itemView)
}

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

private fun Context.roundedBackground(color: Int, radius: Int) =
    android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

private class SimpleTextWatcher(
    private val onChanged: (String) -> Unit
) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        onChanged(s?.toString().orEmpty())
    }
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
