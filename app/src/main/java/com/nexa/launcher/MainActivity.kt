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
import android.media.MediaMetadataRetriever
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
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.VideoView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.SeekBar
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
    private lateinit var batteryIcon: BatteryIconView
    private lateinit var launcherRoot: LinearLayout
    private lateinit var screenRoot: FrameLayout
    private var videoWallpaper: VideoView? = null
    private var appearancePreviewUpdater: (() -> Unit)? = null
    private var activityResumed = false
    private var trimLoopPending = false
    private val videoTrimTick: Runnable = object : Runnable {
        override fun run() {
            val player = videoWallpaper ?: return
            if (!activityResumed || videoWallpaper !== player) return
            val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
            val start = prefs.getInt("video_trim_start", 0)
            val end = prefs.getInt("video_trim_end", 0)
            val loop = prefs.getBoolean("video_loop", true)
            if (end <= start) return
            if (player.isPlaying && player.currentPosition >= end && !trimLoopPending) {
                if (!loop) {
                    player.pause()
                    return
                }
                trimLoopPending = true
                player.pause()
                player.seekTo(start)
                val waitForSeek = object : Runnable {
                    var attempts = 0
                    override fun run() {
                        if (!trimLoopPending || videoWallpaper !== player || !activityResumed || isFinishing || isDestroyed) {
                            trimLoopPending = false
                            return
                        }
                        val position = player.currentPosition
                        if (position < end && position <= start + 1000) {
                            trimLoopPending = false
                            player.start()
                            mainHandler.removeCallbacks(videoTrimTick)
                            mainHandler.postDelayed(videoTrimTick, 100)
                        } else if (attempts++ < 75) {
                            mainHandler.postDelayed(this, 40)
                        } else {
                            attempts = 0
                            player.seekTo(start)
                            mainHandler.postDelayed(this, 100)
                        }
                    }
                }
                mainHandler.postDelayed(waitForSeek, 80)
                return
            }
            if (!trimLoopPending) mainHandler.postDelayed(this, 80)
        }
    }
    private fun localized(value: String): String {
        val language = resources.configuration.locales[0].language
        if (language != "th") return value
        val translations = mapOf(
            "Today" to "วันนี้",
            "apps" to "แอป",
            "results" to "ผลลัพธ์",
            "Battery --%" to "แบตเตอรี่ --%",
            "Battery" to "แบตเตอรี่",
            "Search apps" to "ค้นหาแอป",
            "Favorites" to "รายการโปรด",
            "Hold an app to manage it" to "กดแอปค้างไว้เพื่อจัดการ",
            "Recently opened" to "เปิดล่าสุด",
            "All apps" to "แอปทั้งหมด",
            "All Apps" to "แอปทั้งหมด",
            "Games" to "เกม",
            "Movies & TV" to "ภาพยนตร์และทีวี",
            "Music" to "เพลง",
            "Tools" to "เครื่องมือ",
            "Loading" to "กำลังโหลด",
            "Pin apps here" to "ปักหมุดแอปไว้ที่นี่",
            "Your recent apps appear here" to "แอปที่เปิดล่าสุดจะแสดงที่นี่",
            "No hidden apps." to "ไม่มีแอปที่ซ่อนอยู่",
            "Hidden apps" to "แอปที่ซ่อน",
            "Close" to "ปิด",
            "Remove from favorites" to "นำออกจากรายการโปรด",
            "Change favorite button color" to "เปลี่ยนสีปุ่มรายการโปรด",
            "Reset favorite button color" to "รีเซ็ตสีปุ่มรายการโปรด",
            "Add to favorites" to "เพิ่มในรายการโปรด",
            "App info" to "ข้อมูลแอป",
            "Uninstall" to "ถอนการติดตั้ง",
            "Hide app" to "ซ่อนแอป",
            "Unable to open app info." to "ไม่สามารถเปิดข้อมูลแอปได้",
            "Android could not open the uninstall screen. Try App info, then Uninstall." to "Android ไม่สามารถเปิดหน้าถอนการติดตั้งได้ ลองเปิดข้อมูลแอปแล้วเลือกถอนการติดตั้ง",
            "Android Settings could not be opened." to "ไม่สามารถเปิดการตั้งค่า Android ได้",
            "Settings" to "การตั้งค่า",
            "Make NEXA feel like yours" to "ปรับแต่ง NEXA ในแบบของคุณ",
            "Done" to "เสร็จสิ้น",
            "Personalization" to "การปรับแต่ง",
            "Change the look, colors, and wallpaper" to "เปลี่ยนรูปลักษณ์ สี และวอลเปเปอร์",
            "Appearance studio" to "ปรับแต่งรูปลักษณ์",
            "Appearance Studio" to "ปรับแต่งรูปลักษณ์",
            "Photo and video wallpapers, crop, transparency, and styles" to "วอลเปเปอร์รูปภาพและวิดีโอ การครอบตัด ความโปร่งใส และสไตล์",
            "App tile background" to "พื้นหลังไอคอนแอป",
            "Remove or soften the boxes behind app icons" to "ลบหรือปรับความเข้มของพื้นหลังไอคอนแอป",
            "No boxes" to "ไม่มีพื้นหลัง",
            "Very subtle" to "จางมาก",
            "Soft" to "จาง",
            "Normal" to "ปกติ",
            "Cancel" to "ยกเลิก",
            "Display" to "หน้าจอ",
            "Battery indicator" to "ตัวแสดงแบตเตอรี่",
            "Change its size, low-battery threshold, and colors" to "ปรับขนาด ระดับแบตเตอรี่ต่ำ และสี",
            "Low battery threshold" to "ระดับแบตเตอรี่ต่ำ",
            "Medium battery threshold" to "ระดับแบตเตอรี่ปานกลาง",
            "Low battery color" to "สีเมื่อแบตเตอรี่ต่ำ",
            "Medium battery color" to "สีเมื่อแบตเตอรี่ปานกลาง",
            "Normal battery color" to "สีแบตเตอรี่ปกติ",
            "Time format" to "รูปแบบเวลา",
            "Choose 12-hour AM/PM or 24-hour time" to "เลือกรูปแบบเวลา 12 ชั่วโมง AM/PM หรือ 24 ชั่วโมง",
            "12-hour (AM/PM)" to "12 ชั่วโมง (AM/PM)",
            "24-hour" to "24 ชั่วโมง",
            "Adjust how NEXA fills your device screen" to "ปรับการแสดงผล NEXA ให้พอดีกับหน้าจอ",
            "Screen fit" to "การปรับขนาดหน้าจอ",
            "Use the full display area and remove unused top and bottom space" to "ใช้พื้นที่หน้าจอทั้งหมดและตัดพื้นที่ว่างด้านบนและล่าง",
            "Full screen" to "เต็มหน้าจอ",
            "System safe area" to "พื้นที่ปลอดภัยของระบบ",
            "Launcher" to "ลอนเชอร์",
            "Manage your apps and home screen" to "จัดการแอปและหน้าจอหลัก",
            "Refresh apps" to "รีเฟรชแอป",
            "Rescan installed applications" to "สแกนแอปที่ติดตั้งใหม่",
            "Refreshing apps…" to "กำลังรีเฟรชแอป…",
            "Choose which apps appear in NEXA" to "เลือกแอปที่จะแสดงใน NEXA",
            "Default home app" to "แอปหน้าจอหลักเริ่มต้น",
            "Choose NEXA as your Android launcher" to "ตั้ง NEXA เป็นลอนเชอร์ Android",
            "Open Android Settings and choose Default apps, then Home app." to "เปิดการตั้งค่า Android แล้วเลือกแอปเริ่มต้น จากนั้นเลือกแอปหน้าจอหลัก",
            "System" to "ระบบ",
            "Android settings and information" to "การตั้งค่าและข้อมูล Android",
            "Android Settings" to "การตั้งค่า Android",
            "Open your device settings" to "เปิดการตั้งค่าอุปกรณ์",
            "About NEXA" to "เกี่ยวกับ NEXA",
            "Version 0.4.0 · Lightweight Android launcher" to "เวอร์ชัน 0.4.0 · ลอนเชอร์ Android ที่มีน้ำหนักเบา",
            "NEXA Launcher is designed to keep your home screen fast, smooth, and customizable." to "NEXA Launcher ออกแบบมาเพื่อให้หน้าจอหลักรวดเร็ว ลื่นไหล และปรับแต่งได้",
            "Your apps  ·  Games  ·  Tools" to "แอปของคุณ · เกม · เครื่องมือ",
            "Choose photo wallpaper" to "เลือกรูปภาพวอลเปเปอร์",
            "Choose video wallpaper" to "เลือกวิดีโอวอลเปเปอร์",
            "Trim video start and end" to "ตัดช่วงเริ่มต้นและสิ้นสุดวิดีโอ",
            "Video loop mode" to "โหมดวนวิดีโอ",
            "Video position" to "ตำแหน่งวิดีโอ",
            "Video rotation" to "การหมุนวิดีโอ",
            "Video size and screen fit" to "ขนาดวิดีโอและการปรับหน้าจอ",
            "Crop and position photo" to "ครอบตัดและจัดตำแหน่งรูปภาพ",
            "Background color" to "สีพื้นหลัง",
            "Accent color" to "สีเน้น",
            "Wallpaper visibility" to "ความโปร่งใสของวอลเปเปอร์",
            "Custom background color" to "กำหนดสีพื้นหลังเอง",
            "Custom accent color" to "กำหนดสีเน้นเอง",
            "Remove wallpaper" to "ลบวอลเปเปอร์",
            "No media picker is available." to "ไม่มีตัวเลือกไฟล์สื่อให้ใช้งาน",
            "Midnight" to "เที่ยงคืน",
            "Deep purple" to "ม่วงเข้ม",
            "Ocean" to "มหาสมุทร",
            "Forest" to "ป่า",
            "Warm charcoal" to "เทาถ่านโทนอุ่น",
            "Pure black" to "ดำสนิท",
            "Slate" to "สีหินชนวน",
            "Rose" to "กุหลาบ",
            "Amethyst" to "อเมทิสต์",
            "Emerald" to "มรกต",
            "Sunset" to "พระอาทิตย์ตก",
            "Navy" to "กรมท่า",
            "Violet" to "ม่วง",
            "Cyan" to "ฟ้าไซแอน",
            "Mint" to "มิ้นต์",
            "Orange" to "ส้ม",
            "Pink" to "ชมพู",
            "Blue" to "น้ำเงิน",
            "Red" to "แดง",
            "Gold" to "ทอง",
            "Lime" to "เขียวมะนาว",
            "Teal" to "เขียวอมฟ้า",
            "Lavender" to "ลาเวนเดอร์",
            "White" to "ขาว",
            "Subtle" to "นุ่มนวล",
            "Balanced" to "สมดุล",
            "Vivid" to "สดใส",
            "Strong" to "เข้ม",
            "Choose a video wallpaper first." to "โปรดเลือกวิดีโอวอลเปเปอร์ก่อน",
            "This video format cannot be read by the device media decoder." to "ตัวถอดรหัสสื่อของอุปกรณ์ไม่รองรับรูปแบบวิดีโอนี้",
            "Start time in seconds" to "เวลาเริ่มต้น (วินาที)",
            "End time in seconds" to "เวลาสิ้นสุด (วินาที)",
            "Trim video wallpaper" to "ตัดวิดีโอวอลเปเปอร์",
            "Set the section that plays repeatedly. Times are in seconds." to "กำหนดช่วงวิดีโอที่จะเล่นซ้ำ เวลาเป็นวินาที",
            "Save" to "บันทึก",
            "Enter a valid range within the video length." to "โปรดระบุช่วงเวลาที่ถูกต้องภายในความยาววิดีโอ",
            "Video playback" to "การเล่นวิดีโอ",
            "Loop continuously" to "เล่นวนซ้ำต่อเนื่อง",
            "Play once" to "เล่นครั้งเดียว",
            "Horizontal position" to "ตำแหน่งแนวนอน",
            "Vertical position" to "ตำแหน่งแนวตั้ง",
            "Reset" to "รีเซ็ต",
            "Auto fit full screen" to "ปรับให้เต็มหน้าจออัตโนมัติ",
            "Fit entire video" to "แสดงวิดีโอทั้งหมด",
            "Video screen fit" to "การปรับวิดีโอให้พอดีหน้าจอ",
            "Rotate video" to "หมุนวิดีโอ",
            "Center crop" to "ครอบตัดตรงกลาง",
            "Fit entire photo" to "แสดงรูปภาพทั้งหมด",
            "Crop from top" to "ครอบตัดจากด้านบน",
            "Crop from bottom" to "ครอบตัดจากด้านล่าง",
            "Photo crop and position" to "การครอบตัดและจัดตำแหน่งรูปภาพ",
            "Apply" to "นำไปใช้",
            "Enter a valid color such as #8B7CFF." to "โปรดป้อนรหัสสีที่ถูกต้อง เช่น #8B7CFF",
            "Background" to "พื้นหลัง",
            "Visibility" to "ความโปร่งใส",
            "Unable to use that media file. Try choosing another file." to "ไม่สามารถใช้ไฟล์สื่อนี้ได้ โปรดลองเลือกไฟล์อื่น",
            "OK" to "ตกลง",
            "Brightness" to "ความสว่าง",
            "Add custom tab" to "เพิ่มแท็บเอง",
            "Add to custom tab" to "เพิ่มลงในแท็บที่กำหนดเอง",
            "Create" to "สร้าง",
            "Tab name" to "ชื่อแท็บ",
            "Rename tab" to "เปลี่ยนชื่อแท็บ",
            "Delete tab" to "ลบแท็บ",
            "Choose a tab" to "เลือกแท็บ",
            "This tab already exists." to "มีแท็บนี้อยู่แล้ว",
            "Create a custom tab first using +." to "โปรดสร้างแท็บเองก่อนโดยกด +",
            "Add" to "เพิ่ม"
        )
        return translations[value] ?: value
    }

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
    private val builtInCategories = listOf("All Apps", "Games", "Movies & TV", "Music", "Tools")
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
                val use24Hour = getSharedPreferences("nexa_launcher", MODE_PRIVATE).getBoolean("clock_24_hour", false)
                clock.text = SimpleDateFormat(if (use24Hour) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date())
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
        screenRoot = FrameLayout(this).apply {
            setBackgroundColor(selectedBackground)
            addView(launcherRoot, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
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
            text = localized("Today")
            textSize = 13f
            setTextColor(MUTED)
        }

        hero.addView(clock)
        val heroInfo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroInfo.addView(date, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val batteryBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(SURFACE_HIGH, 14)
            setPadding(dp(13), dp(9), dp(14), dp(9))
        }
        batteryIcon = BatteryIconView(this)
        val batterySize = getSharedPreferences("nexa_launcher", MODE_PRIVATE).getInt("battery_size", 30)
        batteryBadge.addView(batteryIcon, LinearLayout.LayoutParams(dp(batterySize + 4), dp((batterySize * 0.62f).toInt().coerceAtLeast(18))).apply {
            rightMargin = dp(9)
        })
        batteryLabel = TextView(this).apply {
            text = localized("Battery --%")
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        batteryBadge.addView(batteryLabel)
        heroInfo.addView(batteryBadge)
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
            hint = localized("Search apps")
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

        launcherRoot.addView(sectionHeader(localized("Favorites"), localized("Hold an app to manage it")))
        favoriteContainer = horizontalStrip()
        val favoriteScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            addView(favoriteContainer, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        launcherRoot.addView(favoriteScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48)
        ).apply {
            topMargin = dp(9)
            bottomMargin = dp(18)
        })

        launcherRoot.addView(sectionHeader(localized("Recently opened"), ""))
        recentContainer = horizontalStrip()
        val recentScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            addView(recentContainer, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        launcherRoot.addView(recentScroll, LinearLayout.LayoutParams(
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
            sectionTitle(localized("All apps")),
            LinearLayout.LayoutParams(0, dp(32), 1f)
        )
        appCount = TextView(this).apply {
            text = localized("Loading")
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

        setContentView(screenRoot)
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
        if (!::batteryLabel.isInitialized || !::batteryIcon.isInitialized) return
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) {
            batteryLabel.text = "--%"
            batteryIcon.level = 0
            batteryIcon.invalidate()
            return
        }
        val percent = (level * 100f / scale).toInt().coerceIn(0, 100)
        val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
        val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
        val threshold = prefs.getInt("battery_low_threshold", 20)
        val low = percent <= threshold
        val mediumThreshold = maxOf(threshold + 5, prefs.getInt("battery_medium_threshold", 50))
        val medium = !low && percent <= mediumThreshold
        val colorKey = when {
            low -> "battery_low_color"
            medium -> "battery_medium_color"
            else -> "battery_normal_color"
        }
        val defaultColor = when {
            low -> 0xFFFF5964.toInt()
            medium -> 0xFFFFC857.toInt()
            else -> 0xFF69D9A5.toInt()
        }
        val color = prefs.getInt(colorKey, defaultColor)
        batteryIcon.level = percent
        batteryIcon.batteryColor = color
        batteryIcon.charging = charging
        batteryIcon.invalidate()
        batteryLabel.text = "$percent%"
        batteryLabel.setTextColor(color)
    }

    private fun showBatterySettings() {
        val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val sizeLabel = TextView(this).apply {
            text = localized("Battery icon size")
            textSize = 13f
            setTextColor(TEXT)
        }
        panel.addView(sizeLabel)
        val sizeValue = TextView(this).apply {
            text = "${prefs.getInt("battery_size", 30)}"
            textSize = 12f
            setTextColor(MUTED)
        }
        panel.addView(sizeValue)
        val sizeSeek = SeekBar(this).apply {
            max = 30
            progress = (prefs.getInt("battery_size", 30) - 20).coerceIn(0, 30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    sizeValue.text = (progress + 20).toString()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        panel.addView(sizeSeek)
        val thresholdLabel = TextView(this).apply {
            text = localized("Low battery warning threshold")
            textSize = 13f
            setTextColor(TEXT)
        }
        panel.addView(thresholdLabel)
        val thresholdValue = TextView(this).apply {
            text = "${prefs.getInt("battery_low_threshold", 20)}%"
            textSize = 12f
            setTextColor(MUTED)
        }
        panel.addView(thresholdValue)
        val thresholdSeek = SeekBar(this).apply {
            max = 45
            progress = prefs.getInt("battery_low_threshold", 20).coerceIn(5, 50) - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    thresholdValue.text = "${progress + 5}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        panel.addView(thresholdSeek)
        val mediumThresholdLabel = TextView(this).apply {
            text = localized("Medium battery threshold")
            textSize = 13f
            setTextColor(TEXT)
        }
        panel.addView(mediumThresholdLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val mediumThresholdValue = TextView(this).apply {
            text = "${prefs.getInt("battery_medium_threshold", 50)}%"
            textSize = 12f
            setTextColor(MUTED)
        }
        panel.addView(mediumThresholdValue)
        val mediumThresholdSeek = SeekBar(this).apply {
            max = 60
            progress = (prefs.getInt("battery_medium_threshold", 50) - 25).coerceIn(0, 60)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    mediumThresholdValue.text = "${progress + 25}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        panel.addView(mediumThresholdSeek)
        val normalColor = TextView(this).apply {
            text = localized("Battery color")
            textSize = 14f
            setTextColor(TEXT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(SURFACE, 12)
            setOnClickListener {
                showCustomColor("Battery color", prefs.getInt("battery_normal_color", 0xFF69D9A5.toInt())) { color ->
                    prefs.edit().putInt("battery_normal_color", color).apply()
                    updateBatteryStatus(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
                }
            }
        }
        panel.addView(normalColor, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) })
        val lowColor = TextView(this).apply {
            text = localized("Low battery color")
            textSize = 14f
            setTextColor(TEXT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(SURFACE, 12)
            setOnClickListener {
                showCustomColor("Low battery color", prefs.getInt("battery_low_color", 0xFFFF5964.toInt())) { color ->
                    prefs.edit().putInt("battery_low_color", color).apply()
                    updateBatteryStatus(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
                }
            }
        }
        panel.addView(lowColor, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        val mediumColor = TextView(this).apply {
            text = localized("Medium battery color")
            textSize = 14f
            setTextColor(TEXT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(SURFACE, 12)
            setOnClickListener {
                showCustomColor("Medium battery color", prefs.getInt("battery_medium_color", 0xFFFFC857.toInt())) { color ->
                    prefs.edit().putInt("battery_medium_color", color).apply()
                    updateBatteryStatus(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
                }
            }
        }
        panel.addView(mediumColor, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        AlertDialog.Builder(this)
            .setTitle(localized("Battery indicator"))
            .setView(panel)
            .setPositiveButton(localized("Apply")) { _, _ ->
                prefs.edit()
                    .putInt("battery_size", sizeSeek.progress + 20)
                    .putInt("battery_low_threshold", thresholdSeek.progress + 5)
                    .putInt("battery_medium_threshold", maxOf(thresholdSeek.progress + 10, mediumThresholdSeek.progress + 25))
                    .apply()
                if (::batteryIcon.isInitialized) {
                    val size = sizeSeek.progress + 20
                    batteryIcon.layoutParams = batteryIcon.layoutParams.apply {
                        width = dp(size + 4)
                        height = dp((size * 0.62f).toInt().coerceAtLeast(18))
                    }
                    updateBatteryStatus(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
                }
            }
            .setNegativeButton(localized("Cancel"), null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        val fullScreen = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
            .getBoolean("full_screen_layout", true)
        val base = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val edgeToEdge = if (fullScreen) {
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        } else {
            0
        }
        window.decorView.systemUiVisibility = base or edgeToEdge
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        videoWallpaper?.start()
        if (videoWallpaper != null) {
            mainHandler.removeCallbacks(videoTrimTick)
            mainHandler.postDelayed(videoTrimTick, 150)
        }
        if (::recentContainer.isInitialized && allApps.isNotEmpty()) {
            renderQuickSections()
        }
        if (::clock.isInitialized) {
            mainHandler.removeCallbacks(clockTick)
            clockTick.run()
        }
    }

    override fun onPause() {
        activityResumed = false
        trimLoopPending = false
        super.onPause()
        mainHandler.removeCallbacks(clockTick)
        mainHandler.removeCallbacks(videoTrimTick)
        videoWallpaper?.pause()
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
        appCount.text = localized("Loading")
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
            "${filtered.size} ${localized("apps")}"
        } else {
            "${filtered.size} ${localized("results")}"
        }
    }

    private fun buildCategoryTabs() {
        categoryTabs.removeAllViews()
        (builtInCategories + getCustomCategories()).forEach { category ->
            val selected = category == currentCategory
            val tab = TextView(this).apply {
                text = localized(category)
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (selected) BACKGROUND else TEXT)
                gravity = Gravity.CENTER
                background = roundedBackground(if (selected) selectedAccent else SURFACE_HIGH, 18)
                setPadding(dp(15), 0, dp(15), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (currentCategory == category) return@setOnClickListener
                    currentCategory = category
                    updateCategoryTabSelection(true)
                    showFilteredApps(animate = true)
                }
                setSmoothPressFeedback()
            }
            categoryTabs.addView(tab, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34)
            ).apply { marginEnd = dp(8) })
            if (category in getCustomCategories()) {
                tab.setOnLongClickListener {
                    showCustomTabActions(category)
                    true
                }
            }
        }
        val addTab = TextView(this).apply {
            text = "+"
            textSize = 20f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            background = roundedBackground(SURFACE_HIGH, 18)
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { createCustomTab() }
            setSmoothPressFeedback()
        }
        categoryTabs.addView(addTab, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(34)
        ).apply { marginEnd = dp(8) })
    }

    private fun updateCategoryTabSelection(animate: Boolean) {
        val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
        selectedAccent = prefs.getInt("accent_color", ACCENT)
        val categories = builtInCategories + getCustomCategories()
        for (index in categories.indices) {
            val tab = categoryTabs.getChildAt(index) as? TextView ?: continue
            val selected = categories[index] == currentCategory
            val targetColor = if (selected) selectedAccent else SURFACE_HIGH
            val targetTextColor = if (selected) BACKGROUND else TEXT
            tab.animate().cancel()
            tab.setTextColor(targetTextColor)
            if (animate && tab.isLaidOut) {
                val startColor = (tab.background as? android.graphics.drawable.GradientDrawable)
                    ?.let { drawable ->
                        val state = drawable.constantState?.newDrawable()?.mutate()
                        val bitmap = android.graphics.drawable.DrawableCompat.wrap(state ?: drawable)
                        val color = if (selected) SURFACE_HIGH else selectedAccent
                        color
                    } ?: targetColor
                val animator = android.animation.ValueAnimator.ofArgb(startColor, targetColor)
                animator.duration = 170L
                animator.interpolator = NEXA_EASE
                animator.addUpdateListener { value ->
                    tab.background = roundedBackground(value.animatedValue as Int, 18)
                }
                animator.start()
            } else {
                tab.background = roundedBackground(targetColor, 18)
            }
        }
    }

    private fun getCustomCategories(): List<String> {
        val raw = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
            .getString("custom_app_tabs", "[]").orEmpty()
        return try {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).map { array.optString(it) }
                .filter { it.isNotBlank() && it !in builtInCategories }
                .distinct()
                .take(40)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun customTabKey(category: String): String =
        "custom_tab_apps_" + android.util.Base64.encodeToString(
            category.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
        )

    private fun createCustomTab() {
        val field = EditText(this).apply {
            hint = localized("Tab name")
            setSingleLine(true)
            setTextColor(TEXT)
            setHintTextColor(MUTED)
        }
        AlertDialog.Builder(this)
            .setTitle(localized("Add custom tab"))
            .setView(field)
            .setNegativeButton(localized("Cancel"), null)
            .setPositiveButton(localized("Create")) { _, _ ->
                val name = field.text.toString().trim().take(24)
                if (name.isBlank()) return@setPositiveButton
                val existing = getCustomCategories()
                if (name in builtInCategories || name in existing) {
                    showMessage("This tab already exists.")
                    return@setPositiveButton
                }
                val updated = org.json.JSONArray().apply {
                    (existing + name).forEach { put(it) }
                }
                getSharedPreferences("nexa_launcher", MODE_PRIVATE).edit()
                    .putString("custom_app_tabs", updated.toString())
                    .apply()
                currentCategory = name
                buildCategoryTabs()
                showFilteredApps(animate = true)
            }
            .show()
    }

    private fun showCustomTabActions(category: String) {
        val options = arrayOf(localized("Rename tab"), localized("Delete tab"))
        AlertDialog.Builder(this)
            .setTitle(category)
            .setItems(options) { _, which ->
                if (which == 0) renameCustomTab(category) else deleteCustomTab(category)
            }
            .show()
    }

    private fun renameCustomTab(oldName: String) {
        val field = EditText(this).apply {
            setSingleLine(true)
            setText(oldName)
            setSelection(text.length)
            setTextColor(TEXT)
        }
        AlertDialog.Builder(this)
            .setTitle(localized("Rename tab"))
            .setView(field)
            .setNegativeButton(localized("Cancel"), null)
            .setPositiveButton(localized("Save")) { _, _ ->
                val newName = field.text.toString().trim().take(24)
                val existing = getCustomCategories()
                if (newName.isBlank()) return@setPositiveButton
                if (newName != oldName && (newName in builtInCategories || newName in existing)) {
                    showMessage("This tab already exists.")
                    return@setPositiveButton
                }
                val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
                val apps = prefs.getString(customTabKey(oldName), "").orEmpty()
                val updated = org.json.JSONArray().apply {
                    existing.map { if (it == oldName) newName else it }.forEach { put(it) }
                }
                prefs.edit()
                    .putString("custom_app_tabs", updated.toString())
                    .putString(customTabKey(newName), apps)
                    .remove(customTabKey(oldName))
                    .apply()
                if (currentCategory == oldName) currentCategory = newName
                buildCategoryTabs()
                showFilteredApps(animate = true)
            }
            .show()
    }

    private fun deleteCustomTab(category: String) {
        val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
        val updated = org.json.JSONArray().apply {
            getCustomCategories().filter { it != category }.forEach { put(it) }
        }
        prefs.edit()
            .putString("custom_app_tabs", updated.toString())
            .remove(customTabKey(category))
            .apply()
        if (currentCategory == category) currentCategory = "All Apps"
        buildCategoryTabs()
        showFilteredApps(animate = true)
    }

    private fun addAppToCustomTab(app: LauncherApp) {
        val tabs = getCustomCategories()
        if (tabs.isEmpty()) {
            showMessage("Create a custom tab first using +.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(localized("Choose a tab"))
            .setItems(tabs.toTypedArray()) { _, index ->
                val tab = tabs[index]
                val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
                val packages = prefs.getString(customTabKey(tab), "").orEmpty()
                    .split("|").filter { it.isNotBlank() }.toMutableSet()
                packages.add(app.packageName)
                prefs.edit().putString(customTabKey(tab), packages.joinToString("|")).apply()
                currentCategory = tab
                buildCategoryTabs()
                showFilteredApps(animate = true)
            }
            .setNegativeButton(localized("Cancel"), null)
            .show()
    }

    private fun matchesCategory(app: LauncherApp, category: String): Boolean {
        if (category == "All Apps") return true
        if (category in getCustomCategories()) {
            val packages = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
                .getString(customTabKey(category), "").orEmpty()
                .split("|").filter { it.isNotBlank() }
            return app.packageName in packages
        }
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
        renderAppStrip(favoriteContainer, getFavorites(), "Pin apps here", true)
        val recent = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
            .getString("recent_apps", "")
            .orEmpty()
            .split("|")
            .filter { it.isNotBlank() }
            .mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
            .take(8)
        renderAppStrip(recentContainer, recent, "Your recent apps appear here")
    }

    private fun renderAppStrip(container: LinearLayout, apps: List<LauncherApp>, emptyText: String, favoriteMode: Boolean = false) {
        container.removeAllViews()
        if (apps.isEmpty()) {
            container.addView(TextView(this).apply {
                text = localized(emptyText)
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
                background = roundedBackground(if (favoriteMode) getSharedPreferences("nexa_launcher", MODE_PRIVATE).getInt("favorite_color_" + app.packageName, SURFACE_HIGH) else SURFACE_HIGH, 14)
                setPadding(dp(15), 0, dp(15), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { launchApp(app) }
                setSmoothPressFeedback()
                setOnLongClickListener {
                    if (favoriteMode) showFavoriteActions(app) else showAppActions(app)
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
            .setTitle(localized("Hidden apps"))
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
            .setNegativeButton(localized("Close"), null)
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

    private fun showFavoriteActions(app: LauncherApp) {
        val options = arrayOf(
            localized("Change favorite button color"),
            localized("Reset favorite button color"),
            localized("Remove from favorites")
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
                val key = "favorite_color_" + app.packageName
                when (which) {
                    0 -> showCustomColor("Change favorite button color", prefs.getInt(key, SURFACE_HIGH)) { color ->
                        prefs.edit().putInt(key, color).apply()
                        renderQuickSections()
                    }
                    1 -> {
                        prefs.edit().remove(key).apply()
                        renderQuickSections()
                    }
                    2 -> toggleFavorite(app)
                }
            }
            .show()
    }

    private fun showAppActions(app: LauncherApp) {
        val favorite = getFavorites().any { it.packageName == app.packageName }
        val options = arrayOf(
            if (favorite) localized("Remove from favorites") else localized("Add to favorites"),
            localized("Add to custom tab"),
            localized("App info"),
            localized("Uninstall"),
            localized("Hide app")
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleFavorite(app)
                    1 -> addAppToCustomTab(app)
                    2 -> openAppInfo(app)
                    3 -> uninstallApp(app)
                    4 -> hideApp(app)
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
            setOnClickListener { setContentView(screenRoot) }
            setSmoothPressFeedback()
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        heading.addView(TextView(this).apply {
            text = localized("Settings")
            textSize = 25f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(TEXT)
        })
        heading.addView(TextView(this).apply {
            text = localized("Make NEXA feel like yours")
            textSize = 12f
            setTextColor(MUTED)
        })
        header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val done = TextView(this).apply {
            text = localized("Done")
            textSize = 13f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            background = roundedBackground(selectedAccent, 14)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { setContentView(screenRoot) }
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
                text = localized(name)
                textSize = 17f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(TEXT)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(18)
                bottomMargin = dp(4)
            })
            content.addView(TextView(this).apply {
                text = localized(detail)
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
                text = localized(title)
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(TEXT)
            })
            row.addView(TextView(this).apply {
                text = localized(detail)
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
        item("Appearance studio", "Photo and video wallpapers, crop, transparency, and styles") { showAppearanceSettings() }
        item("App tile background", "Remove or soften the boxes behind app icons") {
            val choices = arrayOf("No boxes", "Very subtle", "Soft", "Normal").map(::localized).toTypedArray()
            val values = intArrayOf(0, 28, 65, 115)
            val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
            val current = prefs.getInt("app_card_alpha", 65)
            val selected = values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 2
            AlertDialog.Builder(this)
                .setTitle(localized("App tile background"))
                .setSingleChoiceItems(choices, selected) { dialog, which ->
                    prefs.edit().putInt("app_card_alpha", values[which]).apply()
                    appAdapter.notifyDataSetChanged()
                    dialog.dismiss()
                }
                .setNegativeButton(localized("Cancel"), null)
                .show()
        }
        section("Display", "Adjust how NEXA fills your device screen")
        item("Battery indicator", "Change its size, low-battery threshold, and colors") {
            showBatterySettings()
        }
        item("Time format", "Choose 12-hour AM/PM or 24-hour time") {
            val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
            val use24Hour = prefs.getBoolean("clock_24_hour", false)
            val choices = arrayOf("12-hour (AM/PM)", "24-hour").map(::localized).toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(localized("Time format"))
                .setSingleChoiceItems(choices, if (use24Hour) 1 else 0) { dialog, which ->
                    prefs.edit().putBoolean("clock_24_hour", which == 1).apply()
                    clockTick.run()
                    dialog.dismiss()
                }
                .setNegativeButton(localized("Cancel"), null)
                .show()
        }
        item("Screen fit", "Use the full display area and remove unused top and bottom space") {
            val choices = arrayOf("Full screen", "System safe area").map(::localized).toTypedArray()
            val current = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
                .getBoolean("full_screen_layout", true)
            AlertDialog.Builder(this)
                .setTitle(localized("Screen fit"))
                .setSingleChoiceItems(choices, if (current) 0 else 1) { dialog, which ->
                    getSharedPreferences("nexa_launcher", MODE_PRIVATE).edit()
                        .putBoolean("full_screen_layout", which == 0)
                        .apply()
                    hideSystemBars()
                    setContentView(screenRoot)
                    window.decorView.post { hideSystemBars() }
                    dialog.dismiss()
                }
                .setNegativeButton(localized("Cancel"), null)
                .show()
        }
        section("Launcher", "Manage your apps and home screen")
        item("Refresh apps", "Rescan installed applications") { loadApps(); showMessage(localized("Refreshing apps…")) }
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
        val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        val preview = FrameLayout(this).apply {
            background = roundedBackground(prefs.getInt("background_color", BACKGROUND), 18)
            clipToOutline = true
        }
        val previewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        preview.addView(previewImage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val previewOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(11), dp(13), dp(10))
        }
        previewOverlay.addView(TextView(this).apply {
            text = "NEXA"
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(TEXT)
        })
        previewOverlay.addView(TextView(this).apply {
            text = SimpleDateFormat(
                if (prefs.getBoolean("clock_24_hour", false)) "HH:mm" else "h:mm a",
                Locale.getDefault()
            ).format(Date())
            textSize = 27f
            setTextColor(TEXT)
            setPadding(0, dp(10), 0, dp(4))
        })
        previewOverlay.addView(TextView(this).apply {
            text = localized("Your apps  ·  Games  ·  Tools")
            textSize = 10f
            setTextColor(TEXT)
            background = roundedBackground(prefs.getInt("accent_color", ACCENT), 10)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        })
        preview.addView(previewOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        panel.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(170)
        ).apply { bottomMargin = dp(12) })

        fun refreshPreview() {
            val current = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
            val backgroundColor = current.getInt("background_color", BACKGROUND)
            preview.setBackgroundColor(backgroundColor)
            val videoText = current.getString("video_wallpaper_uri", null)
            val photoText = current.getString("wallpaper_uri", null)
            val bitmap = try {
                when {
                    !videoText.isNullOrBlank() -> {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(this, Uri.parse(videoText))
                            retriever.getFrameAtTime(0)
                        } finally {
                            retriever.release()
                        }
                    }
                    !photoText.isNullOrBlank() -> {
                        contentResolver.openInputStream(Uri.parse(photoText))?.use {
                            BitmapFactory.decodeStream(it)
                        }
                    }
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
            previewImage.setImageBitmap(bitmap)
            previewImage.alpha = current.getInt("wallpaper_alpha", 190) / 255f
            val rotation = current.getInt("video_rotation", 0).toFloat()
            previewImage.rotation = if (!videoText.isNullOrBlank()) rotation else 0f
            val autoFit = current.getBoolean("video_auto_fit", true)
            val positionX = current.getInt("video_position_x", 0)
            val positionY = current.getInt("video_position_y", 0)
            previewImage.scaleX = if (autoFit) 1.2f else 1f
            previewImage.scaleY = if (autoFit) 1.2f else 1f
            previewImage.translationX = positionX * dp(1).toFloat()
            previewImage.translationY = positionY * dp(1).toFloat()
            previewOverlay.getChildAt(2).background = roundedBackground(
                current.getInt("accent_color", ACCENT), 10
            )
        }

        val options = arrayOf(
            "Choose photo wallpaper",
            "Choose video wallpaper",
            "Trim video start and end",
            "Video loop mode",
            "Video position",
            "Video rotation",
            "Video size and screen fit",
            "Crop and position photo",
            "Background color",
            "Accent color",
            "Wallpaper visibility",
            "Custom background color",
            "Custom accent color",
            "Remove wallpaper"
        )
        options.forEachIndexed { index, title ->
            val row = TextView(this).apply {
                text = localized(title)
                textSize = 14f
                setTextColor(TEXT)
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBackground(SURFACE, 12)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    when (index) {
                        0, 1 -> {
                            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = if (index == 0) "image/*" else "video/*"
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            }
                            try {
                                startActivityForResult(intent, if (index == 0) 4101 else 4102)
                            } catch (_: Exception) {
                                showMessage("No media picker is available.")
                            }
                        }
                        2 -> showVideoTrimEditor()
                        3 -> showVideoLoopOptions()
                        4 -> showVideoPositionOptions()
                        5 -> showVideoRotationOptions()
                        6 -> showVideoAutoFitOptions()
                        7 -> showPhotoCropOptions()
                        8 -> showColorChoices("Background color", listOf(
                            "Midnight" to 0xFF0D0F14.toInt(),
                            "Deep purple" to 0xFF171126.toInt(),
                            "Ocean" to 0xFF0B1722.toInt(),
                            "Forest" to 0xFF0D1A16.toInt(),
                            "Warm charcoal" to 0xFF211A18.toInt(),
                            "Pure black" to 0xFF000000.toInt(),
                            "Slate" to 0xFF202936.toInt(),
                            "Rose" to 0xFF28151F.toInt(),
                            "Amethyst" to 0xFF241638.toInt(),
                            "Emerald" to 0xFF102820.toInt(),
                            "Sunset" to 0xFF302016.toInt(),
                            "Navy" to 0xFF101B35.toInt()
                        )) { color ->
                            selectedBackground = color
                            prefs.edit().putInt("background_color", color).apply()
                            applySavedAppearance()
                            refreshPreview()
                        }
                        9 -> showColorChoices("Accent color", listOf(
                            "Violet" to 0xFF9D8CFF.toInt(),
                            "Cyan" to 0xFF54D6E8.toInt(),
                            "Mint" to 0xFF65D6A4.toInt(),
                            "Orange" to 0xFFFFAD66.toInt(),
                            "Pink" to 0xFFFF82B2.toInt(),
                            "Blue" to 0xFF6EA8FF.toInt(),
                            "Red" to 0xFFFF6B75.toInt(),
                            "Gold" to 0xFFFFD166.toInt(),
                            "Lime" to 0xFFB8E36B.toInt(),
                            "Teal" to 0xFF42D6C5.toInt(),
                            "Lavender" to 0xFFC5A3FF.toInt(),
                            "White" to 0xFFE8ECF5.toInt()
                        )) { color ->
                            selectedAccent = color
                            prefs.edit().putInt("accent_color", color).apply()
                            updateCategoryTabSelection(false)
                            refreshPreview()
                        }
                        10 -> showColorChoices("Wallpaper visibility", listOf(
                            "Subtle" to 45, "Balanced" to 82, "Vivid" to 135, "Strong" to 190
                        )) { alpha ->
                            prefs.edit().putInt("wallpaper_alpha", alpha).apply()
                            applySavedAppearance()
                            refreshPreview()
                        }
                        11 -> showCustomColor("Custom background color", selectedBackground) { color ->
                            selectedBackground = color
                            prefs.edit().putInt("background_color", color).apply()
                            applySavedAppearance()
                            refreshPreview()
                        }
                        12 -> showCustomColor("Custom accent color", selectedAccent) { color ->
                            selectedAccent = color
                            prefs.edit().putInt("accent_color", color).apply()
                            updateCategoryTabSelection(false)
                            refreshPreview()
                        }
                        13 -> {
                            prefs.edit().remove("wallpaper_uri").remove("video_wallpaper_uri").apply()
                            applySavedAppearance()
                            refreshPreview()
                        }
                    }
                }
                setSmoothPressFeedback()
            }
            panel.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply { bottomMargin = dp(6) })
        }
        appearancePreviewUpdater = { refreshPreview() }
        refreshPreview()
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(panel)
        }
        AlertDialog.Builder(this)
            .setTitle(localized("Appearance Studio"))
            .setView(scroll)
            .setNegativeButton(localized("Close"), null)
            .show()
    }

    private fun createSettingPreview(): Pair<FrameLayout, () -> Unit> {
        val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
        val frame = FrameLayout(this).apply {
            background = roundedBackground(prefs.getInt("background_color", BACKGROUND), 16)
            clipToOutline = true
        }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(image, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(TextView(this@MainActivity).apply {
                text = "NEXA"
                textSize = 13f
                setTextColor(TEXT)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = "9:41"
                textSize = 27f
                setTextColor(TEXT)
                setPadding(0, dp(8), 0, dp(3))
            })
            addView(TextView(this@MainActivity).apply {
                text = localized("Your apps  ·  Games  ·  Tools")
                textSize = 10f
                setTextColor(TEXT)
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = roundedBackground(prefs.getInt("accent_color", ACCENT), 9)
            })
        }
        frame.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val refresh = {
            val current = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
            frame.setBackgroundColor(current.getInt("background_color", BACKGROUND))
            val video = current.getString("video_wallpaper_uri", null)
            val photo = current.getString("wallpaper_uri", null)
            val bitmap = try {
                when {
                    !video.isNullOrBlank() -> MediaMetadataRetriever().let { retriever ->
                        try {
                            retriever.setDataSource(this, Uri.parse(video))
                            retriever.getFrameAtTime(0)
                        } finally {
                            retriever.release()
                        }
                    }
                    !photo.isNullOrBlank() -> contentResolver.openInputStream(Uri.parse(photo))?.use {
                        BitmapFactory.decodeStream(it)
                    }
                    else -> null
                }
            } catch (_: Exception) { null }
            image.setImageBitmap(bitmap)
            image.alpha = current.getInt("wallpaper_alpha", 190) / 255f
            image.rotation = if (!video.isNullOrBlank()) current.getInt("video_rotation", 0).toFloat() else 0f
            val fit = current.getBoolean("video_auto_fit", true)
            image.scaleX = if (fit) 1.2f else 1f
            image.scaleY = if (fit) 1.2f else 1f
            image.translationX = current.getInt("video_position_x", 0) * dp(1).toFloat()
            image.translationY = current.getInt("video_position_y", 0) * dp(1).toFloat()
            image.scaleType = when (current.getInt("wallpaper_crop_mode", 0)) {
                1 -> ImageView.ScaleType.FIT_CENTER
                2 -> ImageView.ScaleType.CENTER_CROP
                3 -> ImageView.ScaleType.CENTER_CROP
                else -> ImageView.ScaleType.CENTER_CROP
            }
            overlay.getChildAt(2).background = roundedBackground(current.getInt("accent_color", ACCENT), 9)
        }
        frame.post { refresh() }
        return Pair(frame, refresh)
    }

    private fun settingPanel(preview: FrameLayout): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
            addView(preview, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(155)
            ).apply { bottomMargin = dp(12) })
        }
    }

    private fun showVideoTrimEditor() {
        val uriText = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
            .getString("video_wallpaper_uri", null)
        if (uriText.isNullOrBlank()) {
            showMessage("Choose a video wallpaper first.")
            return
        }
        val retriever = MediaMetadataRetriever()
        val duration = try {
            retriever.setDataSource(this, Uri.parse(uriText))
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        if (duration < 1000L) {
            showMessage("This video format cannot be read by the device media decoder.")
            return
        }
        val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
        val startField = EditText(this).apply {
            hint = localized("Start time in seconds")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setText((prefs.getInt("video_trim_start", 0) / 1000f).toString().trimEnd('0').trimEnd('.'))
        }
        val endField = EditText(this).apply {
            hint = localized("End time in seconds")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            val saved = prefs.getInt("video_trim_end", 0)
            setText(if (saved > 0) (saved / 1000f).toString() else (duration / 1000f).toString())
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(TextView(this@MainActivity).apply {
                text = localized("Video length: %.1f seconds").format(Locale.US, duration / 1000f)
                setTextColor(MUTED)
                textSize = 12f
            })
            addView(startField)
            addView(endField)
        }
        AlertDialog.Builder(this)
            .setTitle(localized("Trim video wallpaper"))
            .setMessage(localized("Set the section that plays repeatedly. Times are in seconds."))
            .setView(panel)
            .setPositiveButton(localized("Save")) { _, _ ->
                val start = (startField.text.toString().toFloatOrNull()?.times(1000f))?.toInt()
                val end = (endField.text.toString().toFloatOrNull()?.times(1000f))?.toInt()
                if (start == null || end == null || start < 0 || end <= start || end > duration) {
                    showMessage("Enter a valid range within the video length.")
                } else {
                    prefs.edit().putInt("video_trim_start", start).putInt("video_trim_end", end).apply()
                    applySavedAppearance()
                }
            }
            .setNeutralButton(localized("Use full video")) { _, _ ->
                prefs.edit().remove("video_trim_start").remove("video_trim_end").apply()
                applySavedAppearance()
                appearancePreviewUpdater?.invoke()
            }
            .setNegativeButton(localized("Cancel"), null)
            .show()
    }

    private fun showVideoLoopOptions() {
        val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
        val loop = prefs.getBoolean("video_loop", true)
        AlertDialog.Builder(this)
            .setTitle(localized("Video playback"))
            .setSingleChoiceItems(arrayOf("Loop continuously", "Play once").map(::localized).toTypedArray(), if (loop) 0 else 1) { dialog, which ->
                prefs.edit().putBoolean("video_loop", which == 0).apply()
                applySavedAppearance()
                appearancePreviewUpdater?.invoke()
                dialog.dismiss()
            }
            .setNegativeButton(localized("Cancel"), null)
            .show()
    }

    private fun showVideoPositionOptions() {
        val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
        val (preview, refresh) = createSettingPreview()
        val panel = settingPanel(preview)
        fun addAxis(label: String, key: String) {
            panel.addView(TextView(this).apply {
                text = localized(label)
                setTextColor(TEXT)
                textSize = 13f
            })
            panel.addView(SeekBar(this).apply {
                max = 200
                progress = prefs.getInt(key, 0) + 100
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val value = progress - 100
                        prefs.edit().putInt(key, value).apply()
                        videoWallpaper?.let {
                            if (key == "video_position_x") it.translationX = value * resources.displayMetrics.widthPixels / 100f
                            else it.translationY = value * resources.displayMetrics.heightPixels / 100f
                        }
                        refresh()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            })
        }
        addAxis(localized("Horizontal position"), "video_position_x")
        addAxis(localized("Vertical position"), "video_position_y")
        AlertDialog.Builder(this)
            .setTitle(localized("Video position"))
            .setView(ScrollView(this).apply { addView(panel) })
            .setPositiveButton(localized("Done"), null)
            .setNeutralButton(localized("Reset")) { _, _ ->
                prefs.edit().putInt("video_position_x", 0).putInt("video_position_y", 0).apply()
                applySavedAppearance()
                refresh()
            }
            .show()
    }

    private fun showVideoAutoFitOptions() {
        val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
        val (preview, refresh) = createSettingPreview()
        val panel = settingPanel(preview)
        arrayOf("Auto fit full screen", "Fit entire video").map(::localized).toTypedArray().forEachIndexed { index, label ->
            val row = TextView(this).apply {
                text = localized(label)
                textSize = 14f
                setTextColor(TEXT)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = roundedBackground(SURFACE, 10)
                setOnClickListener {
                    prefs.edit().putBoolean("video_auto_fit", index == 0).apply()
                    applySavedAppearance()
                    refresh()
                }
            }
            panel.addView(row, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(6) })
        }
        AlertDialog.Builder(this)
            .setTitle(localized("Video screen fit"))
            .setView(panel)
            .setPositiveButton(localized("Done"), null)
            .show()
    }

    private fun showVideoRotationOptions() {
        val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
        val (preview, refresh) = createSettingPreview()
        val panel = settingPanel(preview)
        arrayOf(0, 90, 180, 270).forEach { angle ->
            panel.addView(TextView(this).apply {
                text = "$angle°"
                textSize = 14f
                setTextColor(TEXT)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBackground(SURFACE, 10)
                setOnClickListener {
                    prefs.edit().putInt("video_rotation", angle).apply()
                    videoWallpaper?.rotation = angle.toFloat()
                    refresh()
                }
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(5) })
        }
        AlertDialog.Builder(this)
            .setTitle(localized("Rotate video"))
            .setView(panel)
            .setPositiveButton(localized("Done"), null)
            .show()
    }

    private fun showPhotoCropOptions() {
        val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
        val (preview, refresh) = createSettingPreview()
        val panel = settingPanel(preview)
        arrayOf("Center crop", "Fit entire photo", "Crop from top", "Crop from bottom").map(::localized).toTypedArray().forEachIndexed { index, label ->
            panel.addView(TextView(this).apply {
                text = localized(label)
                textSize = 14f
                setTextColor(TEXT)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBackground(SURFACE, 10)
                setOnClickListener {
                    prefs.edit().putInt("wallpaper_crop_mode", index).apply()
                    applySavedAppearance()
                    refresh()
                }
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(5) })
        }
        AlertDialog.Builder(this)
            .setTitle(localized("Photo crop and position"))
            .setView(panel)
            .setPositiveButton(localized("Done"), null)
            .show()
    }



    private fun showCustomColor(title: String, current: Int, onSelected: (Int) -> Unit) {
        var selected = current
        val hsv = FloatArray(3)
        Color.colorToHSV(current, hsv)
        val (preview, refresh) = createSettingPreview()
        val panel = settingPanel(preview)
        val selectedLabel = TextView(this).apply {
            text = String.format("#%06X", 0xFFFFFF and selected)
            textSize = 14f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        panel.addView(selectedLabel, LinearLayout.LayoutParams(-1, dp(38)))
        val wheel = ColorWheelView(this, current) { color ->
            selected = color
            selectedLabel.text = String.format("#%06X", 0xFFFFFF and selected)
            preview.setBackgroundColor(selected)
        }
        panel.addView(wheel, LinearLayout.LayoutParams(-1, dp(270)))
        val brightnessLabel = TextView(this).apply {
            text = localized("Brightness")
            textSize = 12f
            setTextColor(MUTED)
        }
        panel.addView(brightnessLabel)
        val brightness = SeekBar(this).apply {
            max = 100
            progress = (hsv[2] * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    wheel.setBrightness(progress / 100f)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        panel.addView(brightness, LinearLayout.LayoutParams(-1, dp(42)))
        preview.setBackgroundColor(current)
        AlertDialog.Builder(this)
            .setTitle(localized(title))
            .setView(ScrollView(this).apply { addView(panel) })
            .setPositiveButton(localized("Apply")) { _, _ ->
                onSelected(selected)
                refresh()
            }
            .setNegativeButton(localized("Cancel"), null)
            .show()
    }

    private fun showColorChoices(title: String, colors: List<Pair<String, Int>>, onSelected: (Int) -> Unit) {
        if (title.contains("Visibility", true)) {
            val (preview, refresh) = createSettingPreview()
            val panel = settingPanel(preview)
            colors.forEach { (label, color) ->
                panel.addView(TextView(this).apply {
                    text = localized(label)
                    textSize = 14f
                    setTextColor(TEXT)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = roundedBackground(SURFACE, 10)
                    setOnClickListener {
                        onSelected(color)
                        val image = preview.getChildAt(0) as? ImageView
                        image?.alpha = color / 255f
                    }
                }, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(5) })
            }
            AlertDialog.Builder(this)
                .setTitle(localized(title))
                .setView(ScrollView(this).apply { addView(panel) })
                .setPositiveButton(localized("Done"), null)
                .show()
            return
        }
        var selected = colors.firstOrNull()?.second ?: return
        val (preview, refresh) = createSettingPreview()
        val panel = settingPanel(preview)
        val grid = GridLayout(this).apply {
            columnCount = 3
            useDefaultMargins = true
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        colors.forEach { (label, color) ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(7), dp(5), dp(7))
                background = roundedBackground(SURFACE, 10)
                addView(View(this@MainActivity).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(color)
                        cornerRadius = dp(9).toFloat()
                        setStroke(dp(1), MUTED)
                    }
                }, LinearLayout.LayoutParams(-1, dp(38)))
                addView(TextView(this@MainActivity).apply {
                    text = localized(label)
                    textSize = 11f
                    setTextColor(TEXT)
                    gravity = Gravity.CENTER
                    maxLines = 1
                }, LinearLayout.LayoutParams(-1, dp(30)))
                setOnClickListener {
                    selected = color
                    onSelected(color)
                    preview.setBackgroundColor(color)
                    for (i in 0 until grid.childCount) {
                        grid.getChildAt(i).alpha = if (grid.getChildAt(i) === this) 1f else 0.62f
                    }
                    alpha = 1f
                    refresh()
                }
            }
            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(82)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        panel.addView(grid, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle(localized(title))
            .setView(ScrollView(this).apply { addView(panel) })
            .setPositiveButton(localized("Done"), null)
            .show()
    }

    private fun applySavedAppearance() {
        val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
        selectedBackground = prefs.getInt("background_color", BACKGROUND)
        selectedAccent = prefs.getInt("accent_color", ACCENT)
        val videoUri = prefs.getString("video_wallpaper_uri", null)
        val photoUri = prefs.getString("wallpaper_uri", null)
        videoWallpaper?.let {
            screenRoot.removeView(it)
            it.stopPlayback()
        }
        trimLoopPending = false
        mainHandler.removeCallbacks(videoTrimTick)
        videoWallpaper = null
        if (!videoUri.isNullOrBlank()) {
            launcherRoot.setBackgroundColor(Color.TRANSPARENT)
            val player = VideoView(this).apply {
                setVideoURI(Uri.parse(videoUri))
                setOnPreparedListener { media ->
                    val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
                    val startAt = prefs.getInt("video_trim_start", 0)
                    val endAt = prefs.getInt("video_trim_end", 0)
                    media.isLooping = false
                    media.setVolume(0f, 0f)
                    val rotationAngle = prefs.getInt("video_rotation", 0).toFloat()
                    rotation = rotationAngle
                    val autoFit = prefs.getBoolean("video_auto_fit", true)
                    if (autoFit) {
                        post {
                            val rotated = rotationAngle.toInt() % 180 != 0
                            val sourceWidth = if (rotated) media.videoHeight.toFloat() else media.videoWidth.toFloat()
                            val sourceHeight = if (rotated) media.videoWidth.toFloat() else media.videoHeight.toFloat()
                            val targetWidth = screenRoot.width.toFloat().coerceAtLeast(1f)
                            val targetHeight = screenRoot.height.toFloat().coerceAtLeast(1f)
                            if (sourceWidth > 0f && sourceHeight > 0f) {
                                val scale = maxOf(targetWidth / sourceWidth, targetHeight / sourceHeight)
                                scaleX = scale
                                scaleY = scale
                            }
                        }
                    } else {
                        scaleX = 1f
                        scaleY = 1f
                    }
                    translationX = prefs.getInt("video_position_x", 0).toFloat() * resources.displayMetrics.widthPixels / 100f
                    translationY = prefs.getInt("video_position_y", 0).toFloat() * resources.displayMetrics.heightPixels / 100f
                    if (startAt > 0) seekTo(startAt)
                    start()
                    mainHandler.removeCallbacks(videoTrimTick)
                    if (endAt > startAt) {
                        mainHandler.removeCallbacks(videoTrimTick)
                        mainHandler.postDelayed(videoTrimTick, 150)
                    }
                }
                setOnCompletionListener {
                    val loopPrefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
                    val loopEnabled = loopPrefs.getBoolean("video_loop", true)
                    val loopStart = loopPrefs.getInt("video_trim_start", 0)
                    if (loopEnabled && activityResumed && videoWallpaper === this@apply) {
                        pause()
                        seekTo(loopStart)
                        postDelayed({
                            if (videoWallpaper === this@apply && activityResumed && !isFinishing && !isDestroyed) {
                                start()
                                mainHandler.removeCallbacks(videoTrimTick)
                                if (loopPrefs.getInt("video_trim_end", 0) > loopStart) {
                                    mainHandler.postDelayed(videoTrimTick, 100)
                                }
                            }
                        }, 300)
                    }
                }
            }
            videoWallpaper = player
            screenRoot.addView(player, 0, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            return
        }
        if (photoUri.isNullOrBlank()) {
            launcherRoot.setBackgroundColor(selectedBackground)
            return
        }
        try {
            val uri = Uri.parse(photoUri)
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
            val image = BitmapDrawable(resources, bitmap).apply {
                alpha = prefs.getInt("wallpaper_alpha", 190)
                gravity = when (prefs.getInt("wallpaper_crop_mode", 0)) {
                    1 -> Gravity.CENTER
                    2 -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    3 -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    else -> Gravity.FILL
                }
            }
            launcherRoot.background = LayerDrawable(arrayOf(ColorDrawable(selectedBackground), image))
        } catch (_: Exception) {
            launcherRoot.setBackgroundColor(selectedBackground)
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode != 4101 && requestCode != 4102) || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val prefs = getSharedPreferences("nexa_launcher", MODE_PRIVATE)
            if (requestCode == 4102) {
                prefs.edit().putString("video_wallpaper_uri", uri.toString()).remove("wallpaper_uri").remove("video_trim_start").remove("video_trim_end").apply()
            } else {
                prefs.edit().putString("wallpaper_uri", uri.toString()).remove("video_wallpaper_uri").apply()
            }
            applySavedAppearance()
        } catch (_: Exception) {
            showMessage("Unable to use that media file. Try choosing another file.")
        }
    }

    private fun showMessage(message: String) {
        AlertDialog.Builder(this)
            .setMessage(localized(message))
            .setPositiveButton(localized("OK"), null)
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
                text = localized(detail)
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

private class BatteryIconView(context: Context) : View(context) {
    var level = 100
    var batteryColor = Color.WHITE
    var charging = false
    private val bodyPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        color = Color.argb(220, 255, 255, 255)
    }
    private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val boltPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = android.graphics.Paint.Style.FILL
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val inset = context.dp(2).toFloat()
        val terminalWidth = context.dp(3).toFloat()
        val right = width - terminalWidth - inset
        val rect = android.graphics.RectF(inset, inset, right, height - inset)
        val inner = context.dp(3).toFloat()
        fillPaint.color = batteryColor
        val fillRight = rect.left + inner + (rect.width() - inner * 2).coerceAtLeast(0f) * (level.coerceIn(0, 100) / 100f)
        canvas.drawRoundRect(
            android.graphics.RectF(rect.left + inner, rect.top + inner, fillRight, rect.bottom - inner),
            context.dp(2).toFloat(), context.dp(2).toFloat(), fillPaint
        )
        canvas.drawRoundRect(rect, context.dp(4).toFloat(), context.dp(4).toFloat(), bodyPaint)
        canvas.drawRoundRect(
            android.graphics.RectF(right, height * 0.34f, width - inset, height * 0.66f),
            context.dp(1).toFloat(), context.dp(1).toFloat(), bodyPaint
        )
        if (charging) {
            val bolt = android.graphics.Path().apply {
                moveTo(width * 0.57f, height * 0.16f)
                lineTo(width * 0.39f, height * 0.55f)
                lineTo(width * 0.52f, height * 0.55f)
                lineTo(width * 0.43f, height * 0.84f)
                lineTo(width * 0.66f, height * 0.42f)
                lineTo(width * 0.53f, height * 0.42f)
                close()
            }
            canvas.drawPath(bolt, boltPaint)
        }
    }
}

private class ColorWheelView(
    context: Context,
    initialColor: Int,
    private val onColorChanged: (Int) -> Unit
) : View(context) {
    private val wheelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val selectorPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = context.dp(3).toFloat()
        color = Color.WHITE
    }
    private val selectorShadow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = context.dp(5).toFloat()
        color = Color.BLACK
    }
    private val hsv = FloatArray(3)
    private var brightness = 1f
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var selectorX = 0f
    private var selectorY = 0f

    init {
        Color.colorToHSV(initialColor, hsv)
        brightness = hsv[2]
        updateSelector()
        isFocusable = true
        contentDescription = "Color wheel"
    }

    fun setBrightness(value: Float) {
        brightness = value.coerceIn(0.05f, 1f)
        hsv[2] = brightness
        updateSelector()
        invalidate()
        onColorChanged(Color.HSVToColor(hsv))
    }

    private fun updateSelector() {
        val angle = Math.toRadians(hsv[0].toDouble())
        val distance = radius * hsv[1]
        selectorX = centerX + kotlin.math.cos(angle).toFloat() * distance
        selectorY = centerY + kotlin.math.sin(angle).toFloat() * distance
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        centerX = width / 2f
        centerY = height / 2f
        radius = (minOf(width, height) / 2f - context.dp(18)).coerceAtLeast(1f)
        val colors = IntArray(361) { index ->
            Color.HSVToColor(floatArrayOf((index % 360).toFloat(), 1f, brightness))
        }
        val positions = FloatArray(361) { it / 360f }
        wheelPaint.shader = android.graphics.SweepGradient(centerX, centerY, colors, positions)
        canvas.drawCircle(centerX, centerY, radius, wheelPaint)
        overlayPaint.shader = android.graphics.RadialGradient(
            centerX, centerY, radius,
            intArrayOf(Color.WHITE, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, radius, overlayPaint)
        wheelPaint.shader = null
        overlayPaint.shader = null
        updateSelector()
        canvas.drawCircle(selectorX, selectorY, context.dp(9).toFloat(), selectorShadow)
        canvas.drawCircle(selectorX, selectorY, context.dp(9).toFloat(), selectorPaint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                val dx = event.x - centerX
                val dy = event.y - centerY
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                hsv[0] = ((Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0).toFloat()
                hsv[1] = (distance / radius.coerceAtLeast(1f)).coerceIn(0f, 1f)
                hsv[2] = brightness
                updateSelector()
                invalidate()
                onColorChanged(Color.HSVToColor(hsv))
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
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
        if (animate) animationGeneration++
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
            val alpha = context.getSharedPreferences("nexa_launcher", Context.MODE_PRIVATE).getInt("app_card_alpha", 65)
            background = if (alpha == 0) android.graphics.drawable.ColorDrawable(Color.TRANSPARENT) else android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(alpha, 23, 27, 36))
                cornerRadius = context.dp(16).toFloat()
            }
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
        val tileAlpha = context.getSharedPreferences("nexa_launcher", Context.MODE_PRIVATE)
            .getInt("app_card_alpha", 65)
        holder.itemView.background = if (tileAlpha == 0) {
            android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        } else {
            android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(tileAlpha, 23, 27, 36))
                cornerRadius = context.dp(16).toFloat()
            }
        }
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
                .setStartDelay((position.coerceAtMost(12)) * 7L)
                .setDuration(180)
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
