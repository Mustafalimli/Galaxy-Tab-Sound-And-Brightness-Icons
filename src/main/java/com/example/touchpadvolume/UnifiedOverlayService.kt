package com.example.volumescroll

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Birleşik arka plan servisi.
 *
 * MOD 1 (görünmez şeritler): ses ve parlaklık için iki dikdörtgen şerit; normalde tamamen şeffaf,
 *        üzerlerinde touchpad iki parmak kaydırma ile seviye değişir.
 * MOD 2 (yüzen topçuklar): iki yarı saydam top; tıklayınca altından dikey slider açılır.
 * KALİBRASYON: şeritler/toplar görünür ve sürüklenebilir olur; üst-ortadaki onay barından
 *        (uygulama dışındayken bile) "Onayla ve Gizle" ile kapatılır.
 */
class UnifiedOverlayService : Service() {

    private lateinit var settings: AppSettings
    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var config: AppConfig

    private val strips = mutableMapOf<OverlayTarget, StripOverlay>()
    private val bubbles = mutableMapOf<OverlayTarget, BubbleOverlay>()
    private var banner: CalibrationBanner? = null

    private var overlaysActive = false
    private var appliedMode = -1
    private var lastNotifiedCalibration: Boolean? = null
    private var lastNotifiedMode = -1

    private val lastScrollAt = mutableMapOf<OverlayTarget, Long>()
    private var lastToastTime = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    // Ayarlardan herhangi biri değiştiği anda pencereler anında güncellenir (servisi yeniden başlatmadan).
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applyConfig(settings.read())
    }

    // Sistem panelinden parlaklık değişirse topçuk üzerindeki % değeri senkron kalsın.
    private val brightnessObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            bubbles.values.forEach { it.syncFromSystem() }
        }
    }

    // Donanım tuşları / sistem paneli ile ses değişirse topçuk üzerindeki % değeri senkron kalsın.
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            bubbles.values.forEach { it.syncFromSystem() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings.get(this)
        windowManager = getSystemService(WindowManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        config = settings.read()
        createNotificationChannel()
        settings.registerChangeListener(prefListener)
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,
            brightnessObserver
        )
        ContextCompat.registerReceiver(
            this,
            volumeReceiver,
            IntentFilter(VOLUME_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isAlive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() sonrası 5 sn içinde startForeground çağrılmalı.
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_CALIBRATION ->
                settings.setCalibrationMode(!settings.read().isCalibrationMode)
        }

        if (!Settings.canDrawOverlays(this)) {
            shutdown()
            return START_NOT_STICKY
        }

        settings.setServiceRunning(true)
        overlaysActive = true
        applyConfig(settings.read())
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Ekran döndürüldü: tüm pencereleri yeni ekran sınırlarına göre yeniden kısıtla.
        reclampToScreen()
        mainHandler.postDelayed({ reclampToScreen() }, 400)
    }

    override fun onDestroy() {
        settings.unregisterChangeListener(prefListener)
        contentResolver.unregisterContentObserver(brightnessObserver)
        try {
            unregisterReceiver(volumeReceiver)
        } catch (_: IllegalArgumentException) {
            // Zaten kayıtlı değil.
        }
        mainHandler.removeCallbacksAndMessages(null)
        overlaysActive = false
        releaseModeOverlays()
        banner?.hide()
        banner = null
        isAlive = false
        super.onDestroy()
    }

    // ------------------------------------------------------------------ servis yaşam döngüsü

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(settings.read()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        true
    } catch (e: Exception) {
        false
    }

    private fun shutdown() {
        // Servis kapanırken kalibrasyon açık kalmasın (bir sonraki başlatmada şeritler görünmesin).
        settings.setCalibrationMode(false)
        settings.setServiceRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun reclampToScreen() {
        settings.reclampAll()
        applyConfig(settings.read())
    }

    private fun releaseModeOverlays() {
        strips.values.forEach { it.release() }
        strips.clear()
        bubbles.values.forEach { it.release() }
        bubbles.clear()
    }

    /** Mod yöneticisi: aktif moda göre doğru pencereleri kurar, eskilerini kaldırır. */
    private fun applyConfig(cfg: AppConfig) {
        config = cfg
        if (!overlaysActive) return

        if (appliedMode != cfg.activeMode) {
            // Mod değişti: eski modun pencerelerini windowManager.removeView ile kaldır.
            releaseModeOverlays()
            appliedMode = cfg.activeMode
        }

        if (cfg.activeMode == AppMode.INVISIBLE_STRIPS) {
            for (target in OverlayTarget.values()) {
                if (cfg.stripEnabled(target)) {
                    strips.getOrPut(target) { StripOverlay(target) }.applyConfig()
                } else {
                    strips.remove(target)?.release()
                }
            }
        } else {
            for (target in OverlayTarget.values()) {
                if (cfg.bubbleEnabled(target)) {
                    bubbles.getOrPut(target) { BubbleOverlay(target) }.applyConfig()
                } else {
                    bubbles.remove(target)?.release()
                }
            }
        }

        if (cfg.isCalibrationMode) {
            if (banner == null) banner = CalibrationBanner()
            banner?.show()
        } else {
            banner?.hide()
        }

        if (lastNotifiedCalibration != cfg.isCalibrationMode || lastNotifiedMode != cfg.activeMode) {
            lastNotifiedCalibration = cfg.isCalibrationMode
            lastNotifiedMode = cfg.activeMode
            notificationManager.notify(NOTIFICATION_ID, buildNotification(cfg))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun showWriteSettingsToast() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastToastTime < 3000) return
        lastToastTime = now
        Toast.makeText(
            this,
            "Parlaklık için 'Sistem ayarlarını değiştir' izni gerekli. Uygulamayı açıp izni verin.",
            Toast.LENGTH_LONG
        ).show()
    }

    // ------------------------------------------------------------------ ses ve parlaklık motoru

    private fun maxVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private fun currentBrightness(): Int = try {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (e: Settings.SettingNotFoundException) {
        128
    }

    /** Seviye 0..1 aralığında. */
    private fun readLevel(target: OverlayTarget): Float =
        if (target == OverlayTarget.VOLUME) {
            val maxVol = maxVolume()
            if (maxVol > 0) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVol.toFloat() else 0f
        } else {
            currentBrightness() / 255f
        }

    /**
     * Touchpad kaydırma için adım adım değişim.
     * Ses: adjustStreamVolume (RAISE/LOWER). Parlaklık: ≈%4 (10/255) adım.
     * @return uygulanan yeni seviye (0..1) veya yapılamadıysa null.
     */
    private fun stepLevel(target: OverlayTarget, raise: Boolean): Float? {
        if (target == OverlayTarget.VOLUME) {
            val flags = if (config.showSystemVolumeUI) AudioManager.FLAG_SHOW_UI else 0
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                flags
            )
            return readLevel(OverlayTarget.VOLUME)
        }
        return setBrightnessRaw(
            currentBrightness() + if (raise) BRIGHTNESS_STEP else -BRIGHTNESS_STEP
        )
    }

    /**
     * Slider sürükleme için yüzdelik (0..1) değişim.
     * Ses: setStreamVolume. Parlaklık: 0..255 SCREEN_BRIGHTNESS.
     */
    private fun setLevel(target: OverlayTarget, fraction: Float): Float? {
        val f = fraction.coerceIn(0f, 1f)
        if (target == OverlayTarget.VOLUME) {
            val maxVol = maxVolume()
            val value = (f * maxVol).roundToInt().coerceIn(0, maxVol)
            val flags = if (config.showSystemVolumeUI) AudioManager.FLAG_SHOW_UI else 0
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, flags)
            return if (maxVol > 0) value / maxVol.toFloat() else 0f
        }
        return setBrightnessRaw((f * 255f).roundToInt())
    }

    /**
     * Parlaklığı 0..255 aralığında ayarlar; izin yoksa null döner.
     * Otomatik parlaklık manuele çekilir. Tamamen siyah ekranda kalmamak için alt sınır 1'dir.
     */
    private fun setBrightnessRaw(target: Int): Float? {
        if (!Settings.System.canWrite(this)) {
            showWriteSettingsToast()
            return null
        }
        val mode = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        } catch (e: Settings.SettingNotFoundException) {
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        }
        if (mode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
        }
        val value = target.coerceIn(1, 255)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        return value / 255f
    }

    /**
     * ACTION_SCROLL olayını cooldown ve yön ayarlarına göre işler.
     * @return seviye değiştiyse yeni seviye, aksi halde null.
     */
    private fun processScroll(target: OverlayTarget, event: MotionEvent): Float? {
        val delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (delta == 0f) return null
        if (target == OverlayTarget.BRIGHTNESS && !Settings.System.canWrite(this)) {
            showWriteSettingsToast()
            return null
        }
        val now = SystemClock.elapsedRealtime()
        val last = lastScrollAt[target] ?: 0L
        if (now - last < config.scrollCooldownMs) return null
        lastScrollAt[target] = now

        // AXIS_VSCROLL > 0 = yukarı kaydırma. Varsayılan: yukarı = artır.
        val raise = (delta > 0f) != config.invertScroll
        return stepLevel(target, raise)
    }

    // ------------------------------------------------------------------ bildirim

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Touchpad Kontrol Servisi",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ses ve parlaklık kontrol servisinin çalıştığını gösterir"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(cfg: AppConfig): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        val toggleCalibration = PendingIntent.getService(
            this, 1,
            Intent(this, UnifiedOverlayService::class.java).setAction(ACTION_TOGGLE_CALIBRATION),
            flags
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, UnifiedOverlayService::class.java).setAction(ACTION_STOP),
            flags
        )

        val modeName =
            if (cfg.activeMode == AppMode.INVISIBLE_STRIPS) "Görünmez Şerit Modu" else "Yüzen Topçuk Modu"
        val text = if (cfg.isCalibrationMode) {
            "$modeName — Düzenleme açık: kutuları sürükleyin, bitince onaylayın"
        } else {
            "$modeName aktif"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Touchpad ses ve parlaklık kontrolü çalışıyor")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                android.R.drawable.ic_menu_edit,
                if (cfg.isCalibrationMode) "Onayla (Gizle)" else "Ayarla (Göster)",
                toggleCalibration
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Servisi Kapat", stop)
            .build()
    }

    // ================================================================== MOD 1: görünmez şerit

    /**
     * Ses veya parlaklık için tek bir dikdörtgen şerit penceresi.
     * Normalde %100 şeffaftır (Color.TRANSPARENT); kalibrasyonda renkli, çerçeveli ve sürüklenebilir olur.
     *
     * Not: Android, aynı imleç konumundaki hem tıklamayı hem de kaydırmayı en üstteki dokunulabilir
     * pencereye iletir. Kaydırmayı alabilmek için şerit dokunulabilir olmalıdır; bu yüzden şeridin kapladığı
     * dar bölgedeki tıklamalar altındaki uygulamaya geçmez. Şeridi ekranın az kullanılan bir kenarına koyun.
     */
    private inner class StripOverlay(val target: OverlayTarget) {

        private val ctx: Context = this@UnifiedOverlayService

        private val view = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setAutoSizeTextTypeUniformWithConfiguration(8, 20, 1, TypedValue.COMPLEX_UNIT_SP)
            setBackgroundColor(Color.TRANSPARENT)
        }

        private val params = WindowManager.LayoutParams(
            AppSettings.DEFAULT_STRIP_WIDTH,
            AppSettings.MIN_STRIP_HEIGHT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // (0,0) = ekranın sol üst köşesi; x/y değerleri piksel cinsinden öngörülebilir çalışır.
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        private var attached = false
        private var appliedCalibration: Boolean? = null

        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0

        init {
            view.setOnTouchListener { _, event -> handleTouch(event) }
            view.setOnGenericMotionListener { _, event -> handleGenericMotion(event) }
        }

        fun applyConfig() {
            val cfg = config
            val box = cfg.stripBox(target)
            params.x = box.x
            params.y = box.y
            params.width = box.width
            params.height = box.height

            if (appliedCalibration != cfg.isCalibrationMode) {
                appliedCalibration = cfg.isCalibrationMode
                styleFor(cfg.isCalibrationMode)
            }

            if (attached) {
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: IllegalArgumentException) {
                    // Pencere henüz eklenmemiş ya da kaldırılmış.
                }
            } else {
                attach()
            }
        }

        private fun styleFor(calibrating: Boolean) {
            if (calibrating) {
                val isVolume = target == OverlayTarget.VOLUME
                view.text = if (isVolume) "SES ŞERİDİ - Sürükle" else "PARLAKLIK ŞERİDİ - Sürükle"
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    // Ses: yarı saydam mavi/turkuaz + sarı çerçeve. Parlaklık: yarı saydam turuncu + beyaz çerçeve.
                    setColor(if (isVolume) Color.argb(120, 0, 188, 212) else Color.argb(120, 255, 152, 0))
                    setStroke(dp(3), if (isVolume) Color.YELLOW else Color.WHITE)
                }
            } else {
                view.text = ""
                view.setBackgroundColor(Color.TRANSPARENT)
            }
        }

        private fun attach() {
            try {
                windowManager.addView(view, params)
                attached = true
            } catch (e: Exception) {
                // Overlay izni kaldırılmış olabilir; servis bir sonraki başlatmada kontrol eder.
            }
        }

        fun release() {
            if (attached) {
                try {
                    windowManager.removeView(view)
                } catch (_: IllegalArgumentException) {
                    // Zaten kaldırılmış.
                }
                attached = false
            }
        }

        /** Yalnızca kalibrasyon modunda sürükle-bırak; aksi halde dokunma işlenmez. */
        private fun handleTouch(event: MotionEvent): Boolean {
            if (!config.isCalibrationMode) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    settings.setStripGeometry(
                        target,
                        x = startX + (event.rawX - downRawX).roundToInt(),
                        y = startY + (event.rawY - downRawY).roundToInt()
                    )
                }
            }
            return true
        }

        /** Touchpad iki parmak kaydırma (ACTION_SCROLL). Kalibrasyonda yanlışlıkla değişmesin diye yok sayılır. */
        private fun handleGenericMotion(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_SCROLL) return false
            if (!config.isCalibrationMode) processScroll(target, event)
            return true
        }
    }

    // ================================================================== MOD 2: yüzen topçuk

    /**
     * Bir topçuk + altında açılan dikey slider içeren tek bir overlay penceresi.
     * Kalibrasyonda: düzenleme çerçevesi, slider önizlemesi açık, tüm pencere sürüklenebilir.
     * Kalibrasyon dışında: konum kilitli; tıklama slider'ı açar/kapatır.
     */
    private inner class BubbleOverlay(val target: OverlayTarget) {

        private val ctx: Context = this@UnifiedOverlayService
        private val accent = if (target == OverlayTarget.VOLUME) COLOR_VOLUME else COLOR_BRIGHTNESS
        private val icon = if (target == OverlayTarget.VOLUME) ICON_VOLUME else ICON_BRIGHTNESS
        private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

        private val bubbleView = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            includeFontPadding = false
        }

        private val slider = CustomVerticalSliderView(ctx, accent).apply {
            visibility = View.GONE
        }

        private val root = BubbleRootLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                bubbleView,
                LinearLayout.LayoutParams(AppSettings.DEFAULT_BUBBLE_SIZE, AppSettings.DEFAULT_BUBBLE_SIZE)
            )
            addView(
                slider,
                LinearLayout.LayoutParams(
                    AppSettings.DEFAULT_BUBBLE_SIZE,
                    AppSettings.DEFAULT_DROPDOWN_HEIGHT
                ).apply { topMargin = AppSettings.DROPDOWN_GAP_PX }
            )
        }

        private val params = WindowManager.LayoutParams(
            AppSettings.DEFAULT_BUBBLE_SIZE,
            AppSettings.DEFAULT_BUBBLE_SIZE,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        private var attached = false
        private var expanded = false     // kullanıcı topçuğa tıklayıp slider'ı açtı mı
        private var highlighted = false  // topçuk aktif (0.95 opaklık) durumda mı

        private var appliedSize = -1
        private var appliedDropdown = -1
        private var appliedCalibration: Boolean? = null

        // Sürükleme (yalnızca kalibrasyonda) ve tıklama algılama
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0

        private val collapseRunnable = Runnable { collapse() }

        init {
            bubbleView.setOnTouchListener { _, event -> handleBubbleTouch(event) }

            val scrollListener = View.OnGenericMotionListener { _, event -> handleScroll(event) }
            bubbleView.setOnGenericMotionListener(scrollListener)
            slider.setOnGenericMotionListener(scrollListener)
            root.setOnGenericMotionListener(scrollListener)

            root.setOnTouchListener { _, event -> handleRootTouch(event) }

            slider.onInteraction = { touchActivity() }
            slider.onUserChange = { fraction -> onSliderChanged(fraction) }
        }

        // -------------------------------------------------------------- yapılandırma

        fun applyConfig() {
            val cfg = config

            if (appliedSize != cfg.bubbleSizePx || appliedDropdown != cfg.dropdownSliderHeightPx) {
                appliedSize = cfg.bubbleSizePx
                appliedDropdown = cfg.dropdownSliderHeightPx
                bubbleView.layoutParams = LinearLayout.LayoutParams(cfg.bubbleSizePx, cfg.bubbleSizePx)
                slider.layoutParams =
                    LinearLayout.LayoutParams(cfg.bubbleSizePx, cfg.dropdownSliderHeightPx)
                        .apply { topMargin = AppSettings.DROPDOWN_GAP_PX }
                bubbleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, cfg.bubbleSizePx * 0.2f)
            }

            if (appliedCalibration != cfg.isCalibrationMode) {
                appliedCalibration = cfg.isCalibrationMode
                mainHandler.removeCallbacks(collapseRunnable)
                expanded = false
                highlighted = false
                root.interceptAll = cfg.isCalibrationMode
                styleFor(cfg.isCalibrationMode)
                if (attached) refreshDisplay(readLevel(target))
            }

            syncSliderVisibility()
            updateWindowGeometry()
            updateAlpha()
            attach()
        }

        private fun styleFor(calibrating: Boolean) {
            bubbleView.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(230, 32, 33, 36))
                if (calibrating) {
                    // Belirgin düzenleme çerçevesi (kesikli sarı)
                    setStroke(dp(4), Color.YELLOW, dp(8).toFloat(), dp(5).toFloat())
                } else {
                    setStroke(dp(2), accent)
                }
            }
            root.background = if (calibrating) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.argb(40, 255, 255, 255))
                    setStroke(dp(2), Color.WHITE, dp(8).toFloat(), dp(4).toFloat())
                }
            } else {
                null
            }
        }

        /** Dikey slider: kullanıcı açtıysa veya kalibrasyon önizlemesi için görünür. */
        private fun isOpen(): Boolean = expanded || config.isCalibrationMode

        private fun syncSliderVisibility() {
            slider.visibility = if (isOpen()) View.VISIBLE else View.GONE
        }

        private fun updateWindowGeometry() {
            val cfg = config
            val screenHeight = settings.screenSize().second
            val size = cfg.bubbleSizePx
            val totalHeight = size + AppSettings.DROPDOWN_GAP_PX + cfg.dropdownSliderHeightPx
            val baseY = cfg.bubbleY(target)

            params.width = size
            params.height = if (isOpen()) totalHeight else size
            params.x = cfg.bubbleX(target)
            // Slider aşağıya sığmıyorsa pencere geçici olarak yukarı kaydırılır (kayıtlı konum değişmez).
            params.y = if (expanded && !cfg.isCalibrationMode) {
                baseY.coerceAtMost(max(0, screenHeight - totalHeight))
            } else {
                baseY
            }

            if (attached) {
                try {
                    windowManager.updateViewLayout(root, params)
                } catch (_: IllegalArgumentException) {
                    // Pencere henüz eklenmemiş ya da kaldırılmış.
                }
            }
        }

        private fun updateAlpha() {
            root.alpha = when {
                config.isCalibrationMode -> 1f
                highlighted || expanded -> ACTIVE_ALPHA
                else -> config.bubbleIdleAlpha
            }
        }

        private fun attach() {
            if (attached) return
            try {
                windowManager.addView(root, params)
                attached = true
                refreshDisplay(readLevel(target))
            } catch (e: Exception) {
                // Overlay izni kaldırılmış olabilir; servis bir sonraki başlatmada kontrol eder.
            }
        }

        fun release() {
            mainHandler.removeCallbacks(collapseRunnable)
            expanded = false
            highlighted = false
            if (attached) {
                try {
                    windowManager.removeView(root)
                } catch (_: IllegalArgumentException) {
                    // Zaten kaldırılmış.
                }
                attached = false
            }
        }

        fun syncFromSystem() {
            if (attached) refreshDisplay(readLevel(target))
        }

        // -------------------------------------------------------------- aç / kapat

        private fun expand() {
            expanded = true
            highlighted = true
            refreshDisplay(readLevel(target))
            syncSliderVisibility()
            updateWindowGeometry()
            updateAlpha()
            scheduleCollapse()
        }

        fun collapse() {
            mainHandler.removeCallbacks(collapseRunnable)
            expanded = false
            highlighted = false
            syncSliderVisibility()
            updateWindowGeometry()
            updateAlpha()
        }

        private fun toggle() {
            if (expanded) {
                collapse()
                return
            }
            if (target == OverlayTarget.BRIGHTNESS && !Settings.System.canWrite(ctx)) {
                showWriteSettingsToast()
                return
            }
            expand()
        }

        private fun scheduleCollapse() {
            mainHandler.removeCallbacks(collapseRunnable)
            mainHandler.postDelayed(collapseRunnable, config.autoCollapseSec * 1000L)
        }

        /** Her dokunma / kaydırmada topçuk aktif görünür ve otomatik kapanma süresi sıfırlanır. */
        private fun touchActivity() {
            if (!highlighted) {
                highlighted = true
                updateAlpha()
            }
            scheduleCollapse()
        }

        // -------------------------------------------------------------- dokunma

        /**
         * Kök pencere dokunmaları:
         * - ACTION_OUTSIDE: pencere dışına tıklandı → slider kapanır (kalibrasyonda yok sayılır).
         * - Kalibrasyonda tüm pencere sürüklenebilir (BubbleRootLayout tüm dokunmaları yakalar).
         */
        private fun handleRootTouch(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                if (!config.isCalibrationMode && (expanded || highlighted)) collapse()
                return true
            }
            if (config.isCalibrationMode) return dragWindow(event)
            return false
        }

        private fun dragWindow(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    settings.setBubblePosition(
                        target,
                        x = startX + (event.rawX - downRawX).roundToInt(),
                        y = startY + (event.rawY - downRawY).roundToInt()
                    )
                }
            }
            return true
        }

        /** Kalibrasyon dışında: eşik (touch slop) altındaki basış-bırakış tıklamadır → slider aç/kapat. */
        private fun handleBubbleTouch(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    if (expanded) scheduleCollapse()
                }
                MotionEvent.ACTION_UP -> {
                    if (hypot(event.rawX - downRawX, event.rawY - downRawY) <= touchSlop) toggle()
                }
            }
            return true
        }

        // -------------------------------------------------------------- touchpad kaydırma

        /** İmleç topçuk veya slider üzerindeyken iki parmak kaydırma (ACTION_SCROLL). */
        private fun handleScroll(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_SCROLL) return false
            if (config.isCalibrationMode) return true

            val level = processScroll(target, event)
            if (level != null) {
                refreshDisplay(level)
                touchActivity()
            }
            return true
        }

        // -------------------------------------------------------------- slider

        private fun onSliderChanged(fraction: Float) {
            val level = setLevel(target, fraction)
            if (level != null) refreshDisplay(level)
        }

        // -------------------------------------------------------------- görünüm

        private fun refreshDisplay(level: Float) {
            bubbleView.text = labelFor(level)
            slider.progress = level
        }

        private fun labelFor(level: Float): CharSequence {
            val text = SpannableString("$icon\n${(level * 100f).roundToInt()}%")
            text.setSpan(RelativeSizeSpan(1.35f), 0, icon.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            return text
        }
    }

    // ================================================================== kalibrasyon onay barı

    /**
     * Kalibrasyon sırasında ekranın üst-ortasında beliren küçük yüzen kutu.
     * Uygulama dışındayken bile "Onayla ve Gizle" ile isCalibrationMode = false yapar.
     */
    private inner class CalibrationBanner {

        private val ctx: Context = this@UnifiedOverlayService

        private val message = TextView(ctx).apply {
            text = "Düzenleme Modu Aktif: Kutuları sürükleyin veya uygulamadan boyutlandırın"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxWidth = dp(320)
        }

        private val confirmButton = TextView(ctx).apply {
            text = "[ \u2713 Onayla ve Gizle ]"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.rgb(46, 125, 50)) // yeşil
            }
            setOnClickListener { settings.setCalibrationMode(false) }
        }

        private val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(235, 32, 33, 36))
                setStroke(dp(2), Color.rgb(255, 167, 38))
            }
            addView(message, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(12) })
            addView(confirmButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        private val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(32) // kamera çentiğinin ve durum çubuğunun altında kalsın
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        private var attached = false

        fun show() {
            if (attached) return
            try {
                windowManager.addView(container, params)
                attached = true
            } catch (e: Exception) {
                // Overlay izni kaldırılmış olabilir.
            }
        }

        fun hide() {
            if (!attached) return
            try {
                windowManager.removeView(container)
            } catch (_: IllegalArgumentException) {
                // Zaten kaldırılmış.
            }
            attached = false
        }
    }

    companion object {
        const val ACTION_STOP = "com.example.volumescroll.action.STOP"
        const val ACTION_TOGGLE_CALIBRATION = "com.example.volumescroll.action.TOGGLE_CALIBRATION"

        private const val CHANNEL_ID = "unified_overlay_channel"
        private const val NOTIFICATION_ID = 4103
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"

        private const val ICON_VOLUME = "\uD83D\uDD0A"       // 🔊
        private const val ICON_BRIGHTNESS = "\u2600\uFE0F"   // ☀️

        private val COLOR_VOLUME = Color.rgb(0, 188, 212)      // Mavi / turkuaz
        private val COLOR_BRIGHTNESS = Color.rgb(255, 193, 7)  // Sarı / kehribar

        private const val ACTIVE_ALPHA = 0.95f
        private const val BRIGHTNESS_STEP = 10 // 255 üzerinden ≈ %4

        /** Servis bu süreçte gerçekten çalışıyor mu? (Arayüzün bayat durumu düzeltmesi için) */
        @Volatile
        var isAlive: Boolean = false
            private set

        fun start(context: Context) {
            AppSettings.get(context).setServiceRunning(true)
            ContextCompat.startForegroundService(
                context,
                Intent(context, UnifiedOverlayService::class.java)
            )
        }

        fun stop(context: Context) {
            val settings = AppSettings.get(context)
            settings.setCalibrationMode(false)
            settings.setServiceRunning(false)
            context.stopService(Intent(context, UnifiedOverlayService::class.java))
        }
    }
}

/**
 * Kalibrasyonda tüm dokunmaları yakalayıp (alt görünümlere vermeden) pencerenin tamamını
 * sürüklenebilir yapan kök yerleşim.
 */
class BubbleRootLayout(context: Context) : LinearLayout(context) {
    var interceptAll: Boolean = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        interceptAll || super.onInterceptTouchEvent(ev)
}

/**
 * Canvas ile çizilen dikey slider: yuvarlatılmış kanal, doluluk çubuğu ve tutamaç.
 * Fare/parmak sürükleme (ACTION_DOWN / ACTION_MOVE) ile kontrol edilir; touchpad kaydırması
 * (ACTION_SCROLL) üst katmanda [UnifiedOverlayService] tarafından dinlenir.
 */
class CustomVerticalSliderView(
    context: Context,
    private val accentColor: Int
) : View(context) {

    /** 0f (en alt) .. 1f (en üst) */
    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    /** Kullanıcı slider'a dokunup sürüklediğinde yeni 0..1 değeriyle çağrılır. */
    var onUserChange: ((Float) -> Unit)? = null

    /** Her dokunmada (otomatik kapanma süresini sıfırlamak için) çağrılır. */
    var onInteraction: (() -> Unit)? = null

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(179, 32, 33, 36)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(51, 255, 255, 255)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
    }
    private val rect = RectF()

    private fun trackWidth(): Float = width * 0.38f
    private fun thumbRadius(): Float = width * 0.26f
    private fun trackTop(): Float = thumbRadius() + 6f
    private fun trackBottom(): Float = height - thumbRadius() - 6f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Arka plan kapsülü
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, w / 2f, w / 2f, backgroundPaint)

        val trackW = trackWidth()
        val left = (w - trackW) / 2f
        val right = left + trackW
        val top = trackTop()
        val bottom = trackBottom()
        val radius = trackW / 2f

        // Boş kanal
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        // Doluluk çubuğu (alttan yukarı)
        val thumbY = bottom - progress * (bottom - top)
        rect.set(left, thumbY, right, bottom)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        // Tutamaç
        val thumbR = thumbRadius()
        thumbRingPaint.strokeWidth = max(3f, w * 0.03f)
        canvas.drawCircle(w / 2f, thumbY, thumbR, thumbPaint)
        canvas.drawCircle(w / 2f, thumbY, thumbR, thumbRingPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.y)
            }
            MotionEvent.ACTION_MOVE -> updateFromTouch(event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun updateFromTouch(y: Float) {
        val top = trackTop()
        val bottom = trackBottom()
        if (bottom <= top) return
        val fraction = 1f - ((y - top) / (bottom - top)).coerceIn(0f, 1f)
        onInteraction?.invoke()
        progress = fraction
        onUserChange?.invoke(fraction)
    }
}
